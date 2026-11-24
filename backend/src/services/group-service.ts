import type {
  ApplicationInfo,
  AuditSink,
  Clock,
  GroupInviteInfo,
  GroupInviteRepository,
  Lang,
  TelegramApi,
  UserRepository,
} from "./ports.js"
import type { AdmissionNotifier } from "./admission-notifier.js"
import { t } from "./i18n.js"

export type IssueInviteOutcome =
  | { ok: true; invite: GroupInviteInfo }
  | { ok: false; reason: "GROUP_NOT_CONFIGURED" | "NO_TELEGRAM" | "API_ERROR" }

export type HandleJoinOutcome =
  | { ok: true; activated: boolean; userUuid: string }
  | { ok: false; reason: "GROUP_NOT_CONFIGURED" | "FOREIGN_CHAT" | "NO_INVITE_NAME" | "INVITE_NOT_FOUND" | "INVITE_MISMATCH" | "INVITE_ALREADY_USED" }

export type RemoveMemberOutcome =
  | { ok: true; kicked: boolean; revokedInvites: number }
  | { ok: false; reason: "GROUP_NOT_CONFIGURED" | "NO_USER" }

/**
 * telegram group integration: one-time invites after approval, join tracking
 * via chat_member updates, activation, and the kick helper used by account
 * deletion (ban + unban pair, unused invites revoked).
 */
export class GroupService {
  constructor(
    private readonly deps: {
      users: UserRepository
      invites: GroupInviteRepository
      telegram: TelegramApi
      notifier: AdmissionNotifier
      audit: AuditSink
      clock: Clock
      /** closed group chat id, null disables the group flow entirely */
      groupChatId: number | null
      /** how long an issued invite stays valid */
      inviteTtlMs: number
      /** language for group/admin-facing texts */
      defaultLanguage: Lang
    },
  ) {}

  /**
   * creates a one-time invite (member_limit: 1) named after the application
   * so the chat_member update can be cross-checked against the record.
   * the applicant gets the link; the admin chat gets a duplicate event.
   */
  async issueInvite(application: ApplicationInfo): Promise<IssueInviteOutcome> {
    const chatId = this.deps.groupChatId
    if (chatId === null) return { ok: false, reason: "GROUP_NOT_CONFIGURED" }

    const name = `adm-${application.id}`
    const lang = this.deps.defaultLanguage
    try {
      const link = await this.deps.telegram.createChatInviteLink(chatId, {
        name,
        memberLimit: 1,
        expireDate: Math.floor((this.deps.clock.nowMs() + this.deps.inviteTtlMs) / 1000),
      })
      const issuedAt = new Date(this.deps.clock.nowMs())
      const expiresAt = new Date(issuedAt.getTime() + this.deps.inviteTtlMs)
      await this.deps.invites.insert({
        name,
        applicationId: application.id,
        minecraftUuid: application.minecraftUuid,
        telegramId: application.telegramId,
        chatId,
        url: link.url,
        issuedAt,
        expiresAt,
      })
      await this.deps.audit.record({
        actorType: "SYSTEM",
        actorId: "group",
        action: "group.invite_issued",
        targetType: "group_invite",
        targetId: name,
        details: { applicationId: application.id, minecraftUuid: application.minecraftUuid },
      })
      const applicant = await this.deps.users.findByUuid(application.minecraftUuid)
      const langUser = applicant?.language ?? lang
      await this.deps.telegram.sendUserMessage(
        application.telegramId,
        t(langUser, "group.inviteText", {
          url: link.url,
          hours: Math.round(this.deps.inviteTtlMs / 3_600_000),
        }),
      )
      await this.deps.notifier.inviteIssued({
        username: applicant?.username ?? application.minecraftUuid,
        inviteName: name,
      })
      const invite: GroupInviteInfo = {
        name,
        applicationId: application.id,
        minecraftUuid: application.minecraftUuid,
        telegramId: application.telegramId,
        chatId,
        url: link.url,
        status: "ISSUED",
        issuedAt,
        usedAt: null,
        expiresAt,
      }
      return { ok: true, invite }
    } catch {
      return { ok: false, reason: "API_ERROR" }
    }
  }

  /**
   * chat_member handler for a join into the managed group. the invite name
   * from the update must match an issued record AND belong to the joining
   * telegram account, otherwise the join is audited as an anomaly.
   */
  async handleJoin(params: {
    chatId: number
    telegramId: string
    inviteLinkName: string | null
  }): Promise<HandleJoinOutcome> {
    const chatId = this.deps.groupChatId
    if (chatId === null) return { ok: false, reason: "GROUP_NOT_CONFIGURED" }
    if (params.chatId !== chatId) return { ok: false, reason: "FOREIGN_CHAT" }
    if (!params.inviteLinkName) {
      await this.auditJoinAnomaly(params.telegramId, "joined_without_invite")
      return { ok: false, reason: "NO_INVITE_NAME" }
    }

    const invite = await this.deps.invites.findByName(params.inviteLinkName)
    if (!invite) {
      await this.auditJoinAnomaly(params.telegramId, "invite_not_found", params.inviteLinkName)
      return { ok: false, reason: "INVITE_NOT_FOUND" }
    }
    if (invite.telegramId !== params.telegramId) {
      // the link was issued for somebody else — do not mark anything
      await this.auditJoinAnomaly(params.telegramId, "invite_mismatch", invite.name)
      return { ok: false, reason: "INVITE_MISMATCH" }
    }

    const used = await this.deps.invites.markUsed(invite.name, new Date(this.deps.clock.nowMs()))
    if (used !== "used") {
      await this.auditJoinAnomaly(params.telegramId, "invite_reuse", invite.name)
      return { ok: false, reason: "INVITE_ALREADY_USED" }
    }

    await this.deps.users.markGroupLinked(invite.minecraftUuid, new Date(this.deps.clock.nowMs()))
    await this.deps.audit.record({
      actorType: "TELEGRAM",
      actorId: params.telegramId,
      action: "group.joined",
      targetType: "user",
      targetId: invite.minecraftUuid,
      details: { chatId: params.chatId, inviteName: invite.name },
    })

    const activated = await this.activateIfEligible(invite.minecraftUuid)
    return { ok: true, activated, userUuid: invite.minecraftUuid }
  }

  /**
   * activation condition: account approved by voting, telegram linked and
   * joined to the group. guarded transition, so a second join event is inert.
   */
  private async activateIfEligible(uuid: string): Promise<boolean> {
    const user = await this.deps.users.findByUuid(uuid)
    if (!user) return false
    if (user.status !== "APPROVED") return false
    if (!user.groupLinkedAt) return false
    const activated = await this.deps.users.setStatus(uuid, ["APPROVED"], "ACTIVE")
    if (!activated) return false
    await this.deps.audit.record({
      actorType: "SYSTEM",
      actorId: "group",
      action: "user.activated",
      targetType: "user",
      targetId: uuid,
    })
    await this.deps.telegram.sendUserMessage(user.telegramId, t(user.language, "group.activated"))
    return true
  }

  private async auditJoinAnomaly(telegramId: string, kind: string, inviteName?: string): Promise<void> {
    await this.deps.audit.record({
      actorType: "TELEGRAM",
      actorId: telegramId,
      action: `anomaly.group_${kind}`,
      targetType: "group",
      targetId: String(this.deps.groupChatId ?? ""),
      details: inviteName === undefined ? {} : { inviteName },
    })
    await this.deps.notifier.anomaly({ kind, telegramId, inviteName })
  }

  /**
   * kick helper for account deletion: ban + immediate unban (the canonical
   * telegram kick pattern), then revoke every invite of this user that is
   * still unused. the ban/unban pair is required — telegram has no plain
   * "remove member" method for bots.
   */
  async removeMember(uuid: string): Promise<RemoveMemberOutcome> {
    const chatId = this.deps.groupChatId
    if (chatId === null) return { ok: false, reason: "GROUP_NOT_CONFIGURED" }
    const user = await this.deps.users.findByUuid(uuid)
    if (!user) return { ok: false, reason: "NO_USER" }

    let kicked = false
    if (user.groupLinkedAt) {
      try {
        await this.deps.telegram.banChatMember(chatId, user.telegramId)
        await this.deps.telegram.unbanChatMember(chatId, user.telegramId)
        kicked = true
      } catch {
        kicked = false
      }
    }

    const issued = await this.deps.invites.listIssuedByUuid(uuid)
    let revoked = 0
    for (const invite of issued) {
      try {
        await this.deps.telegram.revokeChatInviteLink(chatId, invite.url)
      } catch {
        // link may already be gone; the db status is still flipped below
      }
      await this.deps.invites.markRevoked(invite.name, new Date(this.deps.clock.nowMs()))
      revoked += 1
    }

    await this.deps.audit.record({
      actorType: "SYSTEM",
      actorId: "group",
      action: "group.member_kicked",
      targetType: "user",
      targetId: uuid,
      details: { kicked, revokedInvites: revoked },
    })
    await this.deps.notifier.memberKicked({
      username: user.username,
      kicked,
      revoked,
    })
    return { ok: true, kicked, revokedInvites: revoked }
  }
}
