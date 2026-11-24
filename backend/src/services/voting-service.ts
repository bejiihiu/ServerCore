import type {
  ApplicationInfo,
  ApplicationRepository,
  AuditSink,
  Clock,
  InlineKeyboard,
  Lang,
  TelegramApi,
  UserRepository,
  VotingMessageRepository,
  VoteRepository,
} from "./ports.js"
import type { AdmissionNotifier } from "./admission-notifier.js"
import { t } from "./i18n.js"
import { formatMoscowTime } from "./moscow-time.js"
import { decideOutcome, hasQuorum, MAX_VOTING_EXTENSIONS } from "./voting-math.js"
import { formatAnnouncement, formatResultLine, voteKeyboard } from "./voting-format.js"

export type CastVoteReason =
  | "VOTER_NOT_FOUND"
  | "NO_APPLICATION"
  | "NOT_IN_VOTING"
  | "EXPIRED"
  | "NOT_ACTIVE"
  | "SELF_VOTE"
  | "NOT_IN_GROUP"
  | "ALREADY_VOTED"

export type CastVoteOutcome =
  | { ok: true; application: ApplicationInfo; decision: "ACCEPT" | "REJECT" }
  | { ok: false; reason: CastVoteReason }

/** context of the button press, used for the ephemeral confirmation */
export type VoteCallbackContext = {
  callbackQueryId: string
  /** chat the vote button lives in (the managed group) */
  chatId: number
}

export type VotingDecision = {
  applicationId: string
  minecraftUuid: string
  telegramId: string
  status: "APPROVED" | "REJECTED" | "EXPIRED"
  votesFor: number
  votesAgainst: number
  votesCast: number
  eligibleVoters: number
  quorumReached: boolean
}

export type ExpiredSweepResult = {
  decisions: VotingDecision[]
  extended: number
}

/**
 * voting lifecycle: open a voting with a frozen voter count, publish the
 * vote message into the closed group, accept protected votes, auto-extend
 * once on quorum shortfall, then decide.
 *
 * protections: one vote per uuid (compound unique index), no self-vote,
 * only active accounts sitting in the group may vote, repeated callbacks
 * change nothing.
 */
export class VotingService {
  constructor(
    private readonly deps: {
      users: UserRepository
      applications: ApplicationRepository
      votes: VoteRepository
      messages: VotingMessageRepository
      audit: AuditSink
      telegram: TelegramApi
      notifier: AdmissionNotifier
      clock: Clock
      /** closed group chat id, null disables group publication + membership check */
      groupChatId: number | null
      /** language for group/admin-facing texts */
      defaultLanguage: Lang
      /** bot api 9.4 button icons, only active with premium on the bot owner */
      voteButtonIcons: { acceptId: string | null; rejectId: string | null }
      /** message effect id played on the approval celebration message */
      approvalEffectId: string | null
    },
  ) {}

  private keyboardFor(
    applicationId: string,
    counts: { votesFor: number; votesAgainst: number },
  ): InlineKeyboard {
    return voteKeyboard(this.deps.defaultLanguage, applicationId, counts, this.deps.voteButtonIcons)
  }

  /**
   * publishes the vote message into the group and stores it so the keyboard
   * can be updated live. the eligible count must already be frozen on the
   * application.
   */
  async openVoting(application: ApplicationInfo): Promise<void> {
    const applicant = await this.deps.users.findByUuid(application.minecraftUuid)
    const username = applicant?.username ?? application.minecraftUuid
    const lang = this.deps.defaultLanguage
    const text = formatAnnouncement({
      lang,
      username,
      answers: application.answers,
      expiresAt: application.voting.expiresAt,
    })

    const message = await this.deps.telegram.sendGroupMessage(
      text,
      this.keyboardFor(application.id, {
        votesFor: application.voting.votesFor,
        votesAgainst: application.voting.votesAgainst,
      }),
    )
    if (message) {
      await this.deps.messages.saveMany([{ ...message, applicationId: application.id }])
    }
    // without a configured group the voting still runs — the message just
    // is not published and nobody can press the buttons yet
    await this.deps.audit.record({
      actorType: "SYSTEM",
      actorId: "voting",
      action: "voting.created",
      targetType: "application",
      targetId: application.id,
      details: {
        eligibleVoters: application.voting.eligibleVoters,
        publishedInGroup: message !== null,
      },
    })
    // key event fan-out: dm to every linked account + admin chat copy
    await this.deps.notifier.voteOpened({ username })
  }

  /** verifies the voter actually sits in the managed group */
  private async isInGroup(telegramId: string): Promise<boolean> {
    const chatId = this.deps.groupChatId
    if (chatId === null) {
      // group not configured: nothing to check against, membership gate is off
      return true
    }
    try {
      const membership = await this.deps.telegram.getChatMember(chatId, telegramId)
      return membership === "member" || membership === "administrator" || membership === "creator" || membership === "restricted"
    } catch {
      // api failure must not silently let a vote through
      return false
    }
  }

  /** casts one vote with all protections, idempotent on repeats */
  async castVote(params: {
    applicationId: string
    voterTelegramId: string
    decision: "ACCEPT" | "REJECT"
    /** button press context for the ephemeral confirmation */
    callback?: VoteCallbackContext
  }): Promise<CastVoteOutcome> {
    const voter = await this.deps.users.findByTelegramId(params.voterTelegramId)
    if (!voter) return { ok: false, reason: "VOTER_NOT_FOUND" }
    const application = await this.deps.applications.findById(params.applicationId)
    if (!application) return { ok: false, reason: "NO_APPLICATION" }
    if (application.status !== "VOTING") return { ok: false, reason: "NOT_IN_VOTING" }
    if (application.voting.expiresAt.getTime() <= this.deps.clock.nowMs()) {
      return { ok: false, reason: "EXPIRED" }
    }
    if (voter.status !== "ACTIVE") return { ok: false, reason: "NOT_ACTIVE" }
    if (voter.uuid === application.minecraftUuid) return { ok: false, reason: "SELF_VOTE" }
    if (!(await this.isInGroup(params.voterTelegramId))) {
      return { ok: false, reason: "NOT_IN_GROUP" }
    }

    // one uuid — one vote, enforced by the compound unique index; a second
    // telegram account of the same uuid resolves to the same voterUuid
    const result = await this.deps.votes.insert({
      applicationId: application.id,
      voterUuid: voter.uuid,
      decision: params.decision,
      createdAt: new Date(this.deps.clock.nowMs()),
    })
    if (result === "duplicate") {
      // repeated callback: nothing changes, the voter's ephemeral note is
      // refreshed in place instead of spamming a new group message
      await this.updateVoterEphemeral(application.id, params, t(this.deps.defaultLanguage, "vote.feedbackAlready"))
      return { ok: false, reason: "ALREADY_VOTED" }
    }

    const counters = await this.deps.applications.incrementVoteCounters(application.id, params.decision)
    if (!counters) {
      // the voting got decided between our insert and the counter bump; the
      // vote stays stored but does not change the published result
      await this.deps.audit.record({
        actorType: "PLAYER",
        actorId: voter.uuid,
        action: "voting.vote_counter_skipped",
        targetType: "application",
        targetId: application.id,
        details: { decision: params.decision },
      })
      return { ok: false, reason: "NOT_IN_VOTING" }
    }

    await this.refreshKeyboards(application.id, counters)
    await this.confirmVoteEphemeral(application, counters, params)
    await this.deps.audit.record({
      actorType: "PLAYER",
      actorId: voter.uuid,
      action: "voting.vote_cast",
      targetType: "application",
      targetId: application.id,
      details: { decision: params.decision },
    })
    return { ok: true, application, decision: params.decision }
  }

  /**
   * bot api 10.2 ephemeral confirmation: visible only to the voter, inside
   * the group, so other voters see no spam. tells the voter how many more
   * votes the quorum needs.
   */
  private async confirmVoteEphemeral(
    application: ApplicationInfo,
    counters: { votesFor: number; votesAgainst: number },
    params: { voterTelegramId: string; decision: "ACCEPT" | "REJECT"; callback?: VoteCallbackContext },
  ): Promise<void> {
    if (!params.callback) return
    const lang = this.deps.defaultLanguage
    const verdict = t(lang, params.decision === "ACCEPT" ? "vote.verdictAccept" : "vote.verdictReject")
    const votesCast = counters.votesFor + counters.votesAgainst
    const remaining = application.voting.eligibleVoters - votesCast
    const text =
      remaining > 0
        ? t(lang, "vote.confirmation", { verdict, remaining })
        : t(lang, "vote.confirmationQuorum", { verdict })
    const ephemeralId = await this.deps.telegram.sendEphemeralReply(
      params.callback.callbackQueryId,
      params.callback.chatId,
      text,
    )
    if (ephemeralId !== null) {
      await this.deps.messages.saveMany([
        {
          applicationId: application.id,
          telegramId: params.voterTelegramId,
          chatId: params.callback.chatId,
          messageId: ephemeralId,
          kind: "ephemeral",
        },
      ])
    }
  }

  /** edits the voter's existing ephemeral note in place, best effort */
  private async updateVoterEphemeral(
    applicationId: string,
    params: { voterTelegramId: string; callback?: VoteCallbackContext },
    text: string,
  ): Promise<void> {
    if (!params.callback) return
    const messages = await this.deps.messages.listByApplication(applicationId)
    const mine = messages.find(
      (m) => m.kind === "ephemeral" && m.telegramId === params.voterTelegramId,
    )
    if (!mine) return
    await this.deps.telegram.editEphemeralMessageText(
      mine.chatId,
      Number(params.voterTelegramId),
      mine.messageId,
      text,
    )
  }

  /** re-renders [Принять (n)] [Отклонить (m)] on the group vote message */
  async refreshKeyboards(
    applicationId: string,
    counters: { votesFor: number; votesAgainst: number },
  ): Promise<void> {
    const keyboard = this.keyboardFor(applicationId, counters)
    const messages = await this.deps.messages.listByApplication(applicationId)
    for (const message of messages) {
      if (message.kind !== "group") continue
      await this.deps.telegram.editMessageReplyMarkup(message.chatId, message.messageId, keyboard)
    }
  }

  /**
   * decides a finished voting: quorum check, status transition, result
   * publication, applicant notification + admin copy. the caller must have
   * verified the voting actually expired.
   */
  async decide(application: ApplicationInfo): Promise<VotingDecision> {
    const counts = await this.deps.votes.countByDecision(application.id)
    const votesCast = counts.votesFor + counts.votesAgainst
    const quorumReached = hasQuorum(votesCast, application.voting.eligibleVoters)
    const outcome = decideOutcome(counts.votesFor, counts.votesAgainst, application.voting.eligibleVoters)
    const now = new Date(this.deps.clock.nowMs())
    const lang = this.deps.defaultLanguage

    await this.deps.applications.saveDecision(application.id, {
      status: outcome,
      votesCast,
      quorumReached,
      decidedAt: now,
    })

    // approved accounts move on to APPROVED, the rest go back so they can
    // apply again later (still blocked by the 3-month cooldown)
    const nextUserStatus = outcome === "APPROVED" ? "APPROVED" : "UNREGISTERED"
    await this.deps.users.setStatus(application.minecraftUuid, ["VOTING"], nextUserStatus)

    const resultLine = formatResultLine(lang, {
      status: outcome,
      votesFor: counts.votesFor,
      votesAgainst: counts.votesAgainst,
      votesCast,
      eligibleVoters: application.voting.eligibleVoters,
    })
    const applicant = await this.deps.users.findByUuid(application.minecraftUuid)
    const announcement = formatAnnouncement({
      lang,
      username: applicant?.username ?? application.minecraftUuid,
      answers: application.answers,
      expiresAt: application.voting.expiresAt,
    })
    await this.closeVoterMessages(application.id, `${announcement}\n\n${resultLine}`)

    // key event fan-out: result dm to every linked account + admin verdict
    await this.deps.notifier.voteDecided({
      username: applicant?.username ?? application.minecraftUuid,
      status: outcome,
      votesFor: counts.votesFor,
      votesAgainst: counts.votesAgainst,
      votesCast,
      eligibleVoters: application.voting.eligibleVoters,
    })

    if (outcome === "EXPIRED") {
      await this.deps.telegram.sendUserMessage(
        application.telegramId,
        t(lang, "result.expiredToApplicant"),
      )
    } else if (outcome === "REJECTED") {
      await this.deps.telegram.sendUserMessage(
        application.telegramId,
        t(lang, "result.rejectedToApplicant", { for: counts.votesFor, against: counts.votesAgainst }),
      )
    } else {
      // the celebration gets a message effect (bot api 7.4) when configured
      await this.deps.telegram.sendUserMessage(
        application.telegramId,
        t(lang, "result.approvedToApplicant", { for: counts.votesFor, against: counts.votesAgainst }),
        undefined,
        this.deps.approvalEffectId ? { effectId: this.deps.approvalEffectId } : undefined,
      )
    }

    await this.deps.audit.record({
      actorType: "SYSTEM",
      actorId: "voting",
      action: `voting.${outcome.toLowerCase()}`,
      targetType: "application",
      targetId: application.id,
      details: { votesFor: counts.votesFor, votesAgainst: counts.votesAgainst, votesCast, quorumReached },
    })

    return {
      applicationId: application.id,
      minecraftUuid: application.minecraftUuid,
      telegramId: application.telegramId,
      status: outcome,
      votesFor: counts.votesFor,
      votesAgainst: counts.votesAgainst,
      votesCast,
      eligibleVoters: application.voting.eligibleVoters,
      quorumReached,
    }
  }

  /**
   * quorum shortfall within the window: the voting is auto-extended once by
   * its original duration. the second shortfall is final (EXPIRED).
   */
  private async extend(application: ApplicationInfo): Promise<boolean> {
    const applicant = await this.deps.users.findByUuid(application.minecraftUuid)
    const applicantUsername = applicant?.username ?? application.minecraftUuid
    const durationMs = application.voting.expiresAt.getTime() - application.voting.startedAt.getTime()
    const newExpiresAt = new Date(application.voting.expiresAt.getTime() + durationMs)
    const extended = await this.deps.applications.extendVoting(application.id, newExpiresAt)
    if (!extended) return false

    const lang = this.deps.defaultLanguage
    const counts = await this.deps.votes.countByDecision(application.id)
    const endTimeText = formatMoscowTime(newExpiresAt)
    const text = t(lang, "vote.extended", { endTime: endTimeText })
    const messages = await this.deps.messages.listByApplication(application.id)
    for (const message of messages) {
      if (message.kind !== "group") continue
      // keep the vote keyboard alive during the extension
      await this.deps.telegram.editMessageText(
        message.chatId,
        message.messageId,
        text,
        this.keyboardFor(application.id, counts),
      )
    }
    await this.deps.audit.record({
      actorType: "SYSTEM",
      actorId: "voting",
      action: "voting.extended",
      targetType: "application",
      targetId: application.id,
      details: { newExpiresAt: newExpiresAt.toISOString() },
    })
    await this.deps.notifier.voteExtended({
      username: applicantUsername,
      endTimeText,
    })
    return true
  }

  /**
   * closes the voting artifacts: the group vote message gets the result
   * text (keyboard drops), per-voter ephemeral confirmations are deleted.
   */
  private async closeVoterMessages(applicationId: string, fullText: string): Promise<void> {
    const messages = await this.deps.messages.listByApplication(applicationId)
    for (const message of messages) {
      if (message.kind === "ephemeral") {
        await this.deps.telegram.deleteEphemeralMessage(
          message.chatId,
          Number(message.telegramId),
          message.messageId,
        )
        continue
      }
      // editing with no reply_markup drops the keyboard; failures are fine,
      // the result is already persisted
      await this.deps.telegram.editMessageText(message.chatId, message.messageId, fullText)
    }
    await this.deps.messages.deleteByApplication(applicationId)
  }

  /**
   * processes every voting whose window ran out. handles the auto-extension
   * state: first shortfall extends, second one decides. used by the
   * scheduler's catch-up scan, so votings that expired while the bot was
   * down are processed here too.
   */
  async decideExpired(): Promise<ExpiredSweepResult> {
    const now = new Date(this.deps.clock.nowMs())
    const expired = await this.deps.applications.findExpiredVotings(now)
    const decisions: VotingDecision[] = []
    let extended = 0
    for (const application of expired) {
      const counts = await this.deps.votes.countByDecision(application.id)
      const votesCast = counts.votesFor + counts.votesAgainst
      if (
        application.voting.extensionCount < MAX_VOTING_EXTENSIONS &&
        !hasQuorum(votesCast, application.voting.eligibleVoters)
      ) {
        if (await this.extend(application)) {
          extended += 1
          continue
        }
      }
      decisions.push(await this.decide(application))
    }
    return { decisions, extended }
  }

  /** application lookup for orchestrators (scheduler) */
  async getApplicationById(id: string): Promise<ApplicationInfo | null> {
    return this.deps.applications.findById(id)
  }
}
