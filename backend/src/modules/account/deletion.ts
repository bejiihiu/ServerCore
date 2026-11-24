import { createHash } from "node:crypto"
import type { Logger } from "../../logging/logger.js"
import type { Gateway } from "../../websocket/gateway.js"
import type { AuditRepository, AuthRequestRepository, SessionRepository, TelegramLinkRepository, UserRepository } from "./repository.js"
import { AccountError } from "./types.js"
import { GroupInviteModel } from "../../database/models/group-invite.js"
import type { NotificationFanout } from "../notification/notification-port.js"
import { t, type Locale } from "../../services/i18n.js"

/**
 * how deletion talks to the telegram group. kicking needs an admin bot in the
 * group; invites are the one-time links the backend created for the user.
 * the group chat id comes from config and stays empty until the group exists.
 */
export interface TelegramGroupPort {
  kickFromGroup(telegramId: string): Promise<void>
  /** revokes the user's unused invite links, returns how many were killed */
  revokeUnusedInvites(telegramId: string): Promise<number>
}

/**
 * revokes the minecraft side: tells every connected game server to drop the
 * player's live session. the plugin answers with its own cleanup (kick to
 * lobby / spectator restore) — our job is only the revoke signal.
 */
export interface SessionRevoker {
  revokeLiveSessions(minecraftUuid: string): Promise<void>
}

/** real session revoker: ACCOUNT_DELETED broadcast over the gateway */
export class GatewaySessionRevoker implements SessionRevoker {
  private readonly gateway: Gateway

  constructor(gateway: Gateway) {
    this.gateway = gateway
  }

  async revokeLiveSessions(minecraftUuid: string): Promise<void> {
    const servers = this.gateway.connectedServers
    if (servers.length === 0) {
      // nobody is connected: there is no live session to revoke on any server.
      // absence of servers is not a deletion blocker
      return
    }
    const results = await Promise.allSettled(
      servers.map((serverId) =>
        this.gateway.sendTo(serverId, "ACCOUNT_DELETED", { minecraftUuid, reason: "SELF_REQUESTED" }, { awaitAck: true }),
      ),
    )
    const rejected = results.find((r) => r.status === "rejected")
    if (rejected !== undefined) {
      throw rejected.reason
    }
  }
}

/** real group port on the bot api */
export class BotApiTelegramGroupPort implements TelegramGroupPort {
  private readonly api: {
    banChatMember(chatId: string | number, userId: number): Promise<unknown>
    unbanChatMember(chatId: string | number, userId: number, options?: { only_if_banned?: boolean }): Promise<unknown>
    revokeChatInviteLink(chatId: string | number, link: string): Promise<unknown>
  }
  private readonly chatId: string

  constructor(
    api: {
      banChatMember(chatId: string | number, userId: number): Promise<unknown>
      unbanChatMember(chatId: string | number, userId: number, options?: { only_if_banned?: boolean }): Promise<unknown>
      revokeChatInviteLink(chatId: string | number, link: string): Promise<unknown>
    },
    chatId: string,
  ) {
    this.api = api
    this.chatId = chatId
  }

  /** kick = ban + immediate unban: member gone, can't return without an invite */
  async kickFromGroup(telegramId: string): Promise<void> {
    if (this.chatId === "") {
      // group not configured yet: fail closed so the account stays
      // DELETION_PENDING and the pipeline can be retried once it exists
      throw new AccountError("TELEGRAM_GROUP_UNAVAILABLE", "telegram group chat is not configured")
    }
    const userId = Number(telegramId)
    if (!Number.isInteger(userId)) {
      throw new AccountError("TELEGRAM_GROUP_UNAVAILABLE", `telegram id ${telegramId} is not numeric, cannot kick`)
    }
    await this.api.banChatMember(this.chatId, userId)
    await this.api.unbanChatMember(this.chatId, userId, { only_if_banned: true })
  }

  async revokeUnusedInvites(telegramId: string): Promise<number> {
    // the one-time invite links issued for this account at approval time:
    // revoke what is still live through the api, then flip the db status.
    // an api failure does not stop the flip — the link dies at its expiry
    // anyway, and the db record must not keep claiming the invite is usable
    const issued = await GroupInviteModel.find({ telegramId, status: "ISSUED" }).lean()
    let revoked = 0
    for (const invite of issued) {
      try {
        await this.api.revokeChatInviteLink(invite.chatId, invite.url)
      } catch {
        // already gone or already used — the db status flip still happens
      }
      await GroupInviteModel.updateOne({ name: invite.name }, { $set: { status: "REVOKED" } })
      revoked += 1
    }
    return revoked
  }
}

export type DeletionStep =
  | "STATUS_SET"
  | "AUTH_REQUESTS_CANCELLED"
  | "SESSION_REVOKED"
  | "GROUP_KICK"
  | "INVITES_REVOKED"
  | "UNLINKED"
  | "ANONYMIZED"
  | "AUDIT"

export type DeletionOutcome = {
  steps: DeletionStep[]
  cancelledAuthRequests: number
  revokedInvites: number
  deletedUsernameHash: string
}

/**
 * the account deletion pipeline. order matters: personal data lives until
 * every critical revoke has succeeded, so an aborted deletion always leaves a
 * recoverable account in DELETION_PENDING instead of a half-anonymized one.
 *
 * offline-mode note: the uuid is derived from the nickname, so the telegram
 * link is the identity anchor — removing it here (plus anonymizing the
 * username) is what actually severs identity, not the uuid tombstone, which
 * only marks the slot as used.
 *
 * 1. identity check (requester telegram id must be the linked one)
 * 2. status -> DELETION_PENDING (blocks new sessions and auth)
 * 3. cancel pending auth requests
 * 4. revoke live minecraft sessions (gateway broadcast)
 * 5. kick from the telegram group + revoke unused invites
 * 6. unlink telegram
 * 7. anonymize personal data (username -> deleted-<hash>)
 * 8. anonymized audit record
 */
export class AccountDeletionService {
  private readonly users: UserRepository
  private readonly links: TelegramLinkRepository
  private readonly sessions: SessionRepository
  private readonly authRequests: AuthRequestRepository
  private readonly audit: AuditRepository
  private readonly group: TelegramGroupPort
  private readonly sessionRevoker: SessionRevoker
  private readonly notifier: NotificationFanout
  private readonly logger: Logger

  constructor(deps: {
    users: UserRepository
    links: TelegramLinkRepository
    sessions: SessionRepository
    authRequests: AuthRequestRepository
    audit: AuditRepository
    group: TelegramGroupPort
    sessionRevoker: SessionRevoker
    notifier: NotificationFanout
    logger: Logger
  }) {
    this.users = deps.users
    this.links = deps.links
    this.sessions = deps.sessions
    this.authRequests = deps.authRequests
    this.audit = deps.audit
    this.group = deps.group
    this.sessionRevoker = deps.sessionRevoker
    this.notifier = deps.notifier
    this.logger = deps.logger
  }

  /**
   * runs the pipeline. a telegram id is required as proof of identity — the
   * caller (bot flow) always knows it from ctx. re-runs after a failed
   * attempt are supported: every step is idempotent.
   */
  async deleteAccount(
    minecraftUuid: string,
    requesterTelegramId: string,
    now = new Date(),
  ): Promise<DeletionOutcome> {
    const user = await this.users.findByUuid(minecraftUuid)
    if (!user) throw new AccountError("ACCOUNT_NOT_FOUND", `account ${minecraftUuid} not found`)

    // identity check: only the linked telegram account may delete
    if (user.telegramId !== "" && user.telegramId !== requesterTelegramId) {
      throw new AccountError("DELETION_FAILED", "requester is not the linked telegram account")
    }

    const locale: Locale = user.language
    const originalUsername = user.username
    const steps: DeletionStep[] = []
    const push = (step: DeletionStep): void => {
      steps.push(step)
    }

    // 2. freeze first so no new session or auth request slips in mid-pipeline.
    // already-pending is fine: a retry continues where the last run died
    if (user.status !== "DELETION_PENDING") {
      user.status = "DELETION_PENDING"
      user.updatedAt = now
      await this.users.update(user)
      push("STATUS_SET")
    }

    try {
      // 3. pending auth requests die first: cheapest revoke, nothing can use
      // them anymore
      const cancelledAuthRequests = await this.authRequests.cancelPendingForUuid(minecraftUuid, now)
      push("AUTH_REQUESTS_CANCELLED")

      // 4. minecraft access: kill the live session on every connected game
      // server. critical — failure aborts before any data is touched
      await this.sessionRevoker.revokeLiveSessions(minecraftUuid)
      push("SESSION_REVOKED")
      await this.endSessionRecord(minecraftUuid, now)

      // 5. telegram group revokes — only relevant for linked accounts
      let revokedInvites = 0
      if (user.telegramId !== "") {
        await this.group.kickFromGroup(user.telegramId)
        push("GROUP_KICK")
        revokedInvites = await this.group.revokeUnusedInvites(user.telegramId)
        push("INVITES_REVOKED")
      }

      // 6. drop the telegram <-> minecraft association
      const linkTelegramId = user.telegramId !== "" ? user.telegramId : requesterTelegramId
      const link = await this.links.findByTelegramId(linkTelegramId)
      if (link !== null && link.status === "LINKED") {
        link.status = "UNLINKED"
        link.unlinkedAt = now
        await this.links.update(link)
      }
      user.telegramId = ""
      user.telegramUsername = null
      user.telegramLinkedAt = null
      user.updatedAt = now
      await this.users.update(user)
      push("UNLINKED")

      // 7. critical revokes are done, the record can lose its personal data.
      // the tombstone stays: unique uuid index intact, slot marked as used
      const deletedUsernameHash = anonymizedUsername(minecraftUuid)
      user.username = deletedUsernameHash
      user.updatedAt = now
      await this.users.update(user)
      push("ANONYMIZED")

      // 8. audit entry with no personal data in it
      await this.audit.append({
        actorType: "PLAYER",
        actorId: deletedUsernameHash,
        action: "account.deleted",
        targetType: "user",
        targetId: minecraftUuid,
        details: { cancelledAuthRequests, revokedInvites },
        serverId: null,
        relatedSessionId: null,
        createdAt: now,
      })
      push("AUDIT")

      // tell the (former) owner it's done — the chat id works without a link
      await this.notifier.notifyUser(requesterTelegramId, t(locale, "bot.delete.done", { username: originalUsername }))
      await this.notifier.notifyAdmins(t(locale, "bot.delete.done", { username: originalUsername }))

      this.logger.info({ minecraftUuid, cancelledAuthRequests, revokedInvites }, "account deleted")
      return { steps, cancelledAuthRequests, revokedInvites, deletedUsernameHash }
    } catch (err: unknown) {
      // leave the account frozen in DELETION_PENDING: revokes can be retried,
      // and nothing personal has been destroyed yet
      this.logger.error({ err, minecraftUuid }, "account deletion aborted mid-pipeline")
      await this.notifier.notifyUser(requesterTelegramId, t(locale, "bot.delete.fail"))
      if (err instanceof AccountError) throw err
      throw new AccountError("DELETION_FAILED", "account deletion failed mid-pipeline", {
        completedSteps: steps,
      })
    }
  }

  private async endSessionRecord(minecraftUuid: string, now: Date): Promise<void> {
    const active = await this.sessions.findActiveByUuid(minecraftUuid)
    if (!active) return
    active.endedAt = now
    active.status = "DISCONNECTED"
    await this.sessions.update(active)
  }
}

/** stable anonymized username: deleted-<first 12 hex of sha256(uuid)> */
export function anonymizedUsername(minecraftUuid: string): string {
  const hash = createHash("sha256").update(minecraftUuid, "utf8").digest("hex")
  return `deleted-${hash.slice(0, 12)}`
}
