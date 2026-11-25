import type {
  ApplicationStatus,
  AuditActorType,
  UserStatus,
  VoteDecision,
} from "../database/models/index.js"
import type { Locale } from "./i18n.js"

/** supported interface languages — the shared i18n locale union */
export type Lang = Locale

/** a single questionnaire answer, keyed by question order */
export type AnswerPair = { question: string; answer: string }

/** plain serializable user snapshot, no mongoose docs cross the service layer */
export type UserInfo = {
  uuid: string
  username: string
  telegramId: string
  status: UserStatus
  lastApplicationAt: Date | null
  groupLinkedAt: Date | null
  language: Lang
}

/** a user allowed to vote right now: active account with a linked telegram */
export type VoterInfo = { uuid: string; telegramId: string; username: string }

export type VotingInfo = {
  startedAt: Date
  expiresAt: Date
  /** frozen at voting start, never recalculated afterwards */
  eligibleVoters: number
  votesFor: number
  votesAgainst: number
  abstentions: number
  /** how many times the voting was auto-extended for missing quorum */
  extensionCount: number
  /** filled in when the voting is decided */
  votesCast: number | null
  quorumReached: boolean | null
  decidedAt: Date | null
}

export type ApplicationInfo = {
  id: string
  minecraftUuid: string
  telegramId: string
  answers: AnswerPair[]
  status: ApplicationStatus
  voting: VotingInfo
  createdAt: Date
}

/** payload for creating an application, voting window already fixed */
export type NewApplication = {
  minecraftUuid: string
  telegramId: string
  answers: AnswerPair[]
  voting: { startedAt: Date; expiresAt: Date; eligibleVoters: number }
}

/** in-progress questionnaire, durable so it survives bot restarts */
export type DraftInfo = {
  telegramId: string
  minecraftUuid: string
  username: string
  /** index of the next unanswered question */
  step: number
  /** answers indexed by question order */
  answers: string[]
  language: Lang
}

export type VotingMessageInfo = {
  applicationId: string
  /** empty for group messages — they have no personal recipient */
  telegramId: string
  chatId: number
  messageId: number
  /** group = the public vote message, ephemeral = one voter's private confirmation */
  kind: "group" | "ephemeral"
}

export const GROUP_INVITE_STATUSES = ["ISSUED", "USED", "REVOKED"] as const
export type GroupInviteStatus = (typeof GROUP_INVITE_STATUSES)[number]

export type GroupInviteInfo = {
  name: string
  applicationId: string
  minecraftUuid: string
  telegramId: string
  chatId: number
  url: string
  status: GroupInviteStatus
  issuedAt: Date
  usedAt: Date | null
  expiresAt: Date
}

export type NewGroupInvite = {
  name: string
  applicationId: string
  minecraftUuid: string
  telegramId: string
  chatId: number
  url: string
  issuedAt: Date
  expiresAt: Date
}

export type AuditEvent = {
  actorType: AuditActorType
  actorId: string
  action: string
  targetType?: string | null
  targetId?: string | null
  details?: Record<string, unknown>
}

/** append-only audit writer, shared by every admission service. a rejection
 * propagates to the caller — failures here are not silent bookkeeping */
export interface AuditSink {
  record(event: AuditEvent): Promise<void>
}

/**
 * gateway push to the plugin side for admission state changes. the plugin's
 * state cache consumes APPLICATION_* messages to keep placeholders honest;
 * delivery is fire-and-forget — a disconnected server re-seeds its cache on
 * reconnect, so a missed frame self-heals.
 */
export interface PluginPushPort {
  applicationSubmitted(application: { id: string; minecraftUuid: string; telegramId: string }): void
  applicationDecided(
    application: { id: string; minecraftUuid: string },
    outcome: "APPROVED" | "REJECTED" | "EXPIRED",
  ): void
}

/** wire message type for one admission verdict; each outcome has its own frame */
export type ApplicationDecisionMessageType = "APPLICATION_APPROVED" | "APPLICATION_REJECTED" | "APPLICATION_EXPIRED"

export function applicationDecisionMessageType(outcome: "APPROVED" | "REJECTED" | "EXPIRED"): ApplicationDecisionMessageType {
  if (outcome === "APPROVED") return "APPLICATION_APPROVED"
  if (outcome === "EXPIRED") return "APPLICATION_EXPIRED"
  return "APPLICATION_REJECTED"
}

/** time source injected wherever a decision depends on "now"; tests use
 * a fixed clock instead of sleeping, production passes Date.now */
export interface Clock {
  nowMs(): number
}

/**
 * account rows keyed by uuid and telegram id. the account module is the only
 * writer; mutations that change status are guarded transitions (see
 * `setStatus`) rather than blind updates, so a lost race reports false instead
 * of overwriting newer state.
 */
export interface UserRepository {
  findByUuid(uuid: string): Promise<UserInfo | null>
  findByTelegramId(telegramId: string): Promise<UserInfo | null>
  /** active accounts with a linked telegram, the voting pool */
  listEligibleVoters(): Promise<VoterInfo[]>
  /**
   * guarded status transition, atomic in the store: only fires when the
   * current status is one of `from`. false means the guard did not pass.
   */
  setStatus(uuid: string, from: UserStatus[], to: UserStatus): Promise<boolean>
  setLastApplicationAt(uuid: string, at: Date): Promise<void>
  markGroupLinked(uuid: string, at: Date): Promise<boolean>
  setLanguage(uuid: string, language: Lang): Promise<void>
}

/**
 * applications plus their embedded voting counters. admission services own
 * writes; every transition is guarded — only a live application can be
 * decided or extended, and only once. a caller that lost the race gets false
 * and must skip its side effects instead of repeating them.
 */
export interface ApplicationRepository {
  /**
   * inserts a new application. "duplicate-live" means the partial unique
   * index on (minecraftUuid, status in live statuses) rejected a second
   * live application — the db-level anti-double-submit guard.
   */
  insert(app: NewApplication): Promise<{ id: string } | "duplicate-live">
  findById(id: string): Promise<ApplicationInfo | null>
  /** an application that is still PENDING or VOTING, if any */
  findLiveByUuid(uuid: string): Promise<ApplicationInfo | null>
  findLatestByUuid(uuid: string): Promise<ApplicationInfo | null>
  /** guarded status change, atomic like setStatus */
  setStatus(id: string, from: ApplicationStatus[], to: ApplicationStatus): Promise<boolean>
  /**
   * atomically bumps the vote counters, only while the application is still
   * in VOTING. returns the updated counters, null when the guard failed.
   */
  incrementVoteCounters(
    id: string,
    decision: VoteDecision,
  ): Promise<{ votesFor: number; votesAgainst: number } | null>
  /**
   * stores the final decision fields on the voting. guarded like setStatus:
   * only a voting that is still VOTING can be decided, so a second pass that
   * lost the race gets false and must skip its side effects (dms, invites)
   */
  saveDecision(
    id: string,
    decision: { status: ApplicationStatus; votesCast: number; quorumReached: boolean; decidedAt: Date },
  ): Promise<boolean>
  /**
   * one-shot auto-extension of a running voting: shifts the expiry and
   * bumps the extension counter, only while still VOTING and not yet
   * extended. false when the guard did not pass.
   */
  extendVoting(id: string, newExpiresAt: Date): Promise<boolean>
  /** votings whose window ran out but that were never decided */
  findExpiredVotings(now: Date): Promise<ApplicationInfo[]>
}

/**
 * individual ballots. one vote per (application, voter) is a database
 * invariant — the compound unique index, not a service check — so insert
 * reports "duplicate" and the caller treats a repeated press as a no-op.
 */
export interface VoteRepository {
  /**
   * insert is deduplicated by (applicationId, voterUuid): "duplicate" means
   * this uuid already voted on this application and nothing changed.
   */
  insert(vote: {
    applicationId: string
    voterUuid: string
    decision: VoteDecision
    createdAt: Date
  }): Promise<"inserted" | "duplicate">
  countByDecision(applicationId: string): Promise<{ votesFor: number; votesAgainst: number }>
}

/**
 * telegram message ids tied to an application, so vote keyboards can be edited
 * or removed after a restart. this is delivery bookkeeping, not the tally —
 * losing rows only costs a stale keyboard, never a vote.
 */
export interface VotingMessageRepository {
  saveMany(messages: VotingMessageInfo[]): Promise<void>
  listByApplication(applicationId: string): Promise<VotingMessageInfo[]>
  deleteByApplication(applicationId: string): Promise<void>
}

/**
 * single-use invite links, keyed by telegram's link name. `markUsed` is the
 * atomic gate that decides which join activates an account: two joins racing
 * on one link produce exactly one "used".
 */
export interface GroupInviteRepository {
  insert(invite: NewGroupInvite): Promise<void>
  findByName(name: string): Promise<GroupInviteInfo | null>
  /** marks an invite used, atomically: "already-used" means somebody beat us to it */
  markUsed(name: string, usedAt: Date): Promise<"used" | "already-used" | "not-found">
}

/**
 * durable questionnaire progress, so a half-filled form survives a bot
 * restart. the draft is claimed by exactly one delete (single-winner), which
 * is also the anti-double-submit gate for the whole application.
 */
export interface ApplicationDraftRepository {
  save(draft: DraftInfo): Promise<void>
  findByTelegramId(telegramId: string): Promise<DraftInfo | null>
  /**
   * atomic single-winner delete: returns the deleted draft or null when
   * there was nothing (or somebody else already claimed it). this is the
   * anti-double-submit gate for submitting.
   */
  deleteByTelegramId(telegramId: string): Promise<DraftInfo | null>
  /** all live drafts, used to offer resume after a bot restart */
  listAll(): Promise<DraftInfo[]>
}

export type InlineButton = {
  text: string
  data: string
  /** bot api 9.4 button style, rendered where supported */
  style?: "danger" | "success" | "primary"
  /**
   * bot api 9.4 custom emoji icon in front of the label. requires telegram
   * premium on the bot owner; the api layer retries without it on failure
   */
  iconCustomEmojiId?: string
}
export type InlineKeyboard = InlineButton[][]

/** chat member statuses we accept as "in the group" */
export type ChatMembership = "member" | "administrator" | "creator" | "restricted" | "left" | "kicked"

export type InviteLinkRequest = {
  name: string
  memberLimit: number
  /** unix seconds */
  expireDate: number
}

/**
 * the slice of the telegram bot api the admission flow needs. real impl wraps
 * grammY; tests use a recording fake so services stay bot-independent.
 */
export interface TelegramApi {
  /** sends to a user's private chat; null when the user is unreachable */
  sendUserMessage(
    telegramId: string,
    text: string,
    keyboard?: InlineKeyboard,
    opts?: { effectId?: string },
  ): Promise<VotingMessageInfo | null>
  /** publishes into the managed group; null when no group is configured */
  sendGroupMessage(text: string, keyboard?: InlineKeyboard): Promise<VotingMessageInfo | null>
  editMessageReplyMarkup(chatId: number, messageId: number, keyboard: InlineKeyboard): Promise<boolean>
  editMessageText(chatId: number, messageId: number, text: string, keyboard?: InlineKeyboard): Promise<boolean>
  /**
   * bot api 10.2 ephemeral reply: the message lands in the group but is
   * visible only to the user who pressed the button. returns the ephemeral
   * message id, null on failure (delivery is best effort by design).
   */
  sendEphemeralReply(callbackQueryId: string, chatId: number, text: string): Promise<number | null>
  editEphemeralMessageText(
    chatId: number,
    receiverUserId: number,
    ephemeralMessageId: number,
    text: string,
  ): Promise<boolean>
  deleteEphemeralMessage(
    chatId: number,
    receiverUserId: number,
    ephemeralMessageId: number,
  ): Promise<boolean>
  createChatInviteLink(
    chatId: number,
    opts: InviteLinkRequest,
  ): Promise<{ url: string; name: string }>
  revokeChatInviteLink(chatId: number, link: string): Promise<void>
  banChatMember(chatId: number, userId: string): Promise<void>
  unbanChatMember(chatId: number, userId: string): Promise<void>
  /** chat_member lookup used to verify the voter actually sits in the group */
  getChatMember(chatId: number, userId: string): Promise<ChatMembership>
}