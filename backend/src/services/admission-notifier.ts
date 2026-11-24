import type { Locale } from "./i18n.js"
import { t } from "./i18n.js"
import type { NotificationService } from "./notification-service.js"
import { formatResultLine } from "./voting-format.js"
import type { Logger } from "../logging/logger.js"

/**
 * admission-side facade over the shared NotificationService: key lifecycle
 * events fan out to all linked users (dm) with an admin-chat copy, matching
 * the ops notification convention. targeted personal messages (applicant
 * dms, vote keyboards, ephemeral confirmations) stay on the interactive
 * bot api — they are not fan-outs.
 */
export class AdmissionNotifier {
  constructor(
    private readonly deps: {
      notifications: NotificationService | null
      adminChatIds: string[]
      locale: Locale
      logger: Logger
    },
  ) {}

  /** vote opened: everyone with a linked account learns a vote is running */
  async voteOpened(input: { username: string }): Promise<void> {
    await this.broadcast(
      t(this.deps.locale, "admission.dm.vote_opened", { username: input.username }),
      t(this.deps.locale, "admission.admin.vote_opened", {
        username: input.username,
        for: 0,
        against: 0,
      }),
    )
  }

  /** voting decided: result to all linked users, verdict copy to admins */
  async voteDecided(input: {
    username: string
    status: "APPROVED" | "REJECTED" | "EXPIRED"
    votesFor: number
    votesAgainst: number
    votesCast: number
    eligibleVoters: number
  }): Promise<void> {
    const result = formatResultLine(this.deps.locale, {
      status: input.status,
      votesFor: input.votesFor,
      votesAgainst: input.votesAgainst,
      votesCast: input.votesCast,
      eligibleVoters: input.eligibleVoters,
    })
    const adminVerdict =
      input.status === "APPROVED"
        ? t(this.deps.locale, "vote.resultApproved", { for: input.votesFor, against: input.votesAgainst })
        : input.status === "REJECTED"
          ? t(this.deps.locale, "vote.resultRejected", { for: input.votesFor, against: input.votesAgainst })
          : t(this.deps.locale, "vote.resultExpired", {
              cast: input.votesCast,
              eligible: input.eligibleVoters,
            })
    await this.broadcast(result, t(this.deps.locale, "admission.admin.vote_decided", { username: input.username, verdict: adminVerdict }))
  }

  /** quorum shortfall: the voting window moved, admins + users informed */
  async voteExtended(input: { username: string; endTimeText: string }): Promise<void> {
    await this.broadcast(
      t(this.deps.locale, "vote.extended", { endTime: input.endTimeText }),
      t(this.deps.locale, "admission.admin.vote_extended", {
        username: input.username,
        endTime: input.endTimeText,
      }),
    )
  }

  /** admin-only: one-time invite handed out */
  async inviteIssued(input: { username: string; inviteName: string }): Promise<void> {
    await this.notifyAdmins(
      t(this.deps.locale, "admission.admin.invite_issued", {
        username: input.username,
        name: input.inviteName,
      }),
    )
  }

  /** admin-only: account removed from the group */
  async memberKicked(input: { username: string; kicked: boolean; revoked: number }): Promise<void> {
    await this.notifyAdmins(
      t(this.deps.locale, "admission.admin.member_kicked", {
        username: input.username,
        kicked: input.kicked ? t(this.deps.locale, "admission.admin.yes") : t(this.deps.locale, "admission.admin.no"),
        revoked: input.revoked,
      }),
    )
  }

  /** admin-only: suspicious join/invite activity */
  async anomaly(input: { kind: string; telegramId: string; inviteName?: string }): Promise<void> {
    const invite =
      input.inviteName === undefined
        ? ""
        : t(this.deps.locale, "admission.admin.invite_ref", { invite: input.inviteName })
    await this.notifyAdmins(
      t(this.deps.locale, "admission.admin.anomaly", {
        kind: input.kind,
        telegram: input.telegramId,
        invite,
      }),
    )
  }

  private async broadcast(userText: string, adminText: string): Promise<void> {
    if (this.deps.notifications === null) return
    try {
      if (adminText === userText) {
        // identical copy: the broadcast already includes the admin chats
        await this.deps.notifications.broadcast(userText, this.deps.adminChatIds)
      } else {
        // distinct wording: admins get only their copy, they are linked users
        // too and the broadcast would deliver the user text a second time
        await this.deps.notifications.notifyAdmins(adminText, this.deps.adminChatIds)
        await this.deps.notifications.broadcast(userText)
      }
    } catch (err) {
      // a failed notification must never break the admission flow itself
      this.deps.logger.warn({ err }, "admission broadcast failed")
    }
  }

  private async notifyAdmins(text: string): Promise<void> {
    if (this.deps.notifications === null) return
    try {
      await this.deps.notifications.notifyAdmins(text, this.deps.adminChatIds)
    } catch (err) {
      // best effort by design
      this.deps.logger.warn({ err }, "admission admin notify failed")
    }
  }
}
