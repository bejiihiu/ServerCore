import type {
  ApplicationDraftRepository,
  ApplicationInfo,
  ApplicationRepository,
  AuditEvent,
  AuditSink,
  ChatMembership,
  Clock,
  DraftInfo,
  GroupInviteInfo,
  GroupInviteRepository,
  InlineKeyboard,
  Lang,
  NewApplication,
  NewGroupInvite,
  UserInfo,
  UserRepository,
  VoterInfo,
  VotingMessageInfo,
  VotingMessageRepository,
  VoteRepository,
} from "../../src/services/ports.js"
import type { TelegramApi } from "../../src/services/ports.js"
import type { ApplicationStatus, UserStatus, VoteDecision } from "../../src/database/models/index.js"
import { hasQuorum } from "../../src/services/voting-math.js"

/** fixed clock: tests move time by mutating `now` */
export class FixedClock implements Clock {
  now: number
  constructor(now = 1_700_000_000_000) {
    this.now = now
  }
  nowMs(): number {
    return this.now
  }
  advance(ms: number): void {
    this.now += ms
  }
}

const LIVE_STATUSES: ApplicationStatus[] = ["PENDING", "VOTING"]

type StoredApplication = {
  id: string
  minecraftUuid: string
  telegramId: string
  answers: { question: string; answer: string }[]
  status: ApplicationStatus
  voting: {
    startedAt: Date
    expiresAt: Date
    eligibleVoters: number
    votesFor: number
    votesAgainst: number
    abstentions: number
    extensionCount: number
    votesCast: number | null
    quorumReached: boolean | null
    decidedAt: Date | null
  }
  createdAt: Date
}

export class InMemoryUserRepository implements UserRepository {
  /** array-backed: one uuid may hold several telegram accounts (anti-alt) */
  readonly users: UserInfo[] = []

  add(user: UserInfo): void {
    this.users.push({ ...user })
  }

  async findByUuid(uuid: string): Promise<UserInfo | null> {
    const found = this.users.find((u) => u.uuid === uuid)
    return found ? { ...found } : null
  }

  async findByTelegramId(telegramId: string): Promise<UserInfo | null> {
    const found = this.users.find((u) => u.telegramId === telegramId)
    return found ? { ...found } : null
  }

  async listEligibleVoters(): Promise<VoterInfo[]> {
    // deduplicate by uuid: several telegram accounts of one uuid are one voter
    const seen = new Set<string>()
    return this.users
      .filter((u) => u.status === "ACTIVE" && u.telegramId.length > 0 && !seen.has(u.uuid) && seen.add(u.uuid))
      .map((u) => ({ uuid: u.uuid, telegramId: u.telegramId, username: u.username }))
  }

  async setStatus(uuid: string, from: UserStatus[], to: UserStatus): Promise<boolean> {
    const target = this.users.find((u) => u.uuid === uuid)
    if (!target || !from.includes(target.status)) return false
    target.status = to
    return true
  }

  async setLastApplicationAt(uuid: string, at: Date): Promise<void> {
    const target = this.users.find((u) => u.uuid === uuid)
    if (target) target.lastApplicationAt = at
  }

  async markGroupLinked(uuid: string, at: Date): Promise<boolean> {
    const target = this.users.find((u) => u.uuid === uuid)
    if (!target) return false
    target.groupLinkedAt = at
    return true
  }

  async setLanguage(uuid: string, language: Lang): Promise<void> {
    const target = this.users.find((u) => u.uuid === uuid)
    if (target) target.language = language
  }
}

export class InMemoryApplicationRepository implements ApplicationRepository {
  readonly apps = new Map<string, StoredApplication>()
  private nextId = 1

  async insert(app: NewApplication): Promise<{ id: string } | "duplicate-live"> {
    // emulate the partial unique index on (minecraftUuid, status in live)
    for (const existing of this.apps.values()) {
      if (existing.minecraftUuid === app.minecraftUuid && LIVE_STATUSES.includes(existing.status)) {
        return "duplicate-live"
      }
    }
    const id = String(this.nextId++)
    this.apps.set(id, {
      id,
      minecraftUuid: app.minecraftUuid,
      telegramId: app.telegramId,
      answers: app.answers.map((a) => ({ ...a })),
      status: "VOTING",
      voting: {
        ...app.voting,
        votesFor: 0,
        votesAgainst: 0,
        abstentions: 0,
        extensionCount: 0,
        votesCast: null,
        quorumReached: null,
        decidedAt: null,
      },
      createdAt: app.voting.startedAt,
    })
    return { id }
  }

  async findById(id: string): Promise<ApplicationInfo | null> {
    const app = this.apps.get(id)
    return app ? cloneApp(app) : null
  }

  async findLiveByUuid(uuid: string): Promise<ApplicationInfo | null> {
    for (const app of this.apps.values()) {
      if (app.minecraftUuid === uuid && LIVE_STATUSES.includes(app.status)) return cloneApp(app)
    }
    return null
  }

  async findLatestByUuid(uuid: string): Promise<ApplicationInfo | null> {
    let latest: StoredApplication | null = null
    for (const app of this.apps.values()) {
      if (app.minecraftUuid !== uuid) continue
      if (!latest || app.createdAt > latest.createdAt) latest = app
    }
    return latest ? cloneApp(latest) : null
  }

  async setStatus(id: string, from: ApplicationStatus[], to: ApplicationStatus): Promise<boolean> {
    const app = this.apps.get(id)
    if (!app || !from.includes(app.status)) return false
    app.status = to
    return true
  }

  async incrementVoteCounters(
    id: string,
    decision: VoteDecision,
  ): Promise<{ votesFor: number; votesAgainst: number } | null> {
    const app = this.apps.get(id)
    if (!app || app.status !== "VOTING") return null
    if (decision === "ACCEPT") app.voting.votesFor += 1
    else app.voting.votesAgainst += 1
    return { votesFor: app.voting.votesFor, votesAgainst: app.voting.votesAgainst }
  }

  async saveDecision(
    id: string,
    decision: { status: ApplicationStatus; votesCast: number; quorumReached: boolean; decidedAt: Date },
  ): Promise<boolean> {
    const app = this.apps.get(id)
    // mirrors the store guard: only a still-running voting can be decided
    if (!app || app.status !== "VOTING") return false
    app.status = decision.status
    app.voting.votesCast = decision.votesCast
    app.voting.quorumReached = decision.quorumReached
    app.voting.decidedAt = decision.decidedAt
    return true
  }

  async extendVoting(id: string, newExpiresAt: Date): Promise<boolean> {
    const app = this.apps.get(id)
    if (!app || app.status !== "VOTING" || app.voting.extensionCount !== 0) return false
    app.voting.expiresAt = newExpiresAt
    app.voting.extensionCount = 1
    return true
  }

  async findExpiredVotings(now: Date): Promise<ApplicationInfo[]> {
    return [...this.apps.values()]
      .filter((a) => a.status === "VOTING" && a.voting.expiresAt.getTime() <= now.getTime())
      .sort((a, b) => a.voting.expiresAt.getTime() - b.voting.expiresAt.getTime())
      .map(cloneApp)
  }

  /** test-only: emulates an application document being deleted outright */
  delete(id: string): void {
    this.apps.delete(id)
  }
}

function cloneApp(app: StoredApplication): ApplicationInfo {
  return {
    id: app.id,
    minecraftUuid: app.minecraftUuid,
    telegramId: app.telegramId,
    answers: app.answers.map((a) => ({ ...a })),
    status: app.status,
    voting: { ...app.voting },
    createdAt: app.createdAt,
  }
}

export class InMemoryVoteRepository implements VoteRepository {
  readonly votes = new Map<string, { voterUuid: string; decision: VoteDecision }>()

  async insert(vote: {
    applicationId: string
    voterUuid: string
    decision: VoteDecision
    createdAt: Date
  }): Promise<"inserted" | "duplicate"> {
    const key = `${vote.applicationId}:${vote.voterUuid}`
    if (this.votes.has(key)) return "duplicate"
    this.votes.set(key, { voterUuid: vote.voterUuid, decision: vote.decision })
    return "inserted"
  }

  async countByDecision(applicationId: string): Promise<{ votesFor: number; votesAgainst: number }> {
    let votesFor = 0
    let votesAgainst = 0
    for (const [key, vote] of this.votes) {
      if (!key.startsWith(`${applicationId}:`)) continue
      if (vote.decision === "ACCEPT") votesFor += 1
      else votesAgainst += 1
    }
    return { votesFor, votesAgainst }
  }
}

export class InMemoryVotingMessageRepository implements VotingMessageRepository {
  readonly messages = new Map<string, VotingMessageInfo>() // keyed by `${chatId}:${messageId}`

  async saveMany(messages: VotingMessageInfo[]): Promise<void> {
    for (const message of messages) {
      this.messages.set(`${message.chatId}:${message.messageId}`, { ...message })
    }
  }

  async listByApplication(applicationId: string): Promise<VotingMessageInfo[]> {
    return [...this.messages.values()]
      .filter((m) => m.applicationId === applicationId)
      .map((m) => ({ ...m }))
  }

  async deleteByApplication(applicationId: string): Promise<void> {
    for (const [key, message] of this.messages) {
      if (message.applicationId === applicationId) this.messages.delete(key)
    }
  }
}

export class InMemoryGroupInviteRepository implements GroupInviteRepository {
  readonly invites = new Map<string, GroupInviteInfo>() // keyed by name

  async insert(invite: NewGroupInvite): Promise<void> {
    this.invites.set(invite.name, {
      ...invite,
      status: "ISSUED",
      usedAt: null,
    })
  }

  async findByName(name: string): Promise<GroupInviteInfo | null> {
    const invite = this.invites.get(name)
    return invite ? { ...invite } : null
  }

  async markUsed(name: string, usedAt: Date): Promise<"used" | "already-used" | "not-found"> {
    const invite = this.invites.get(name)
    if (!invite) return "not-found"
    if (invite.status !== "ISSUED") return "already-used"
    invite.status = "USED"
    invite.usedAt = usedAt
    return "used"
  }
}

export class InMemoryDraftRepository implements ApplicationDraftRepository {
  readonly drafts = new Map<string, DraftInfo>() // keyed by telegramId

  async save(draft: DraftInfo): Promise<void> {
    this.drafts.set(draft.telegramId, { ...draft, answers: [...draft.answers] })
  }

  async findByTelegramId(telegramId: string): Promise<DraftInfo | null> {
    const draft = this.drafts.get(telegramId)
    return draft ? { ...draft, answers: [...draft.answers] } : null
  }

  async deleteByTelegramId(telegramId: string): Promise<DraftInfo | null> {
    const draft = this.drafts.get(telegramId)
    if (!draft) return null
    this.drafts.delete(telegramId)
    return { ...draft, answers: [...draft.answers] }
  }

  async listAll(): Promise<DraftInfo[]> {
    return [...this.drafts.values()].map((d) => ({ ...d, answers: [...d.answers] }))
  }
}

export class RecordingAuditSink implements AuditSink {
  readonly events: AuditEvent[] = []
  async record(event: AuditEvent): Promise<void> {
    this.events.push(structuredClone(event))
  }
  actionsOf(action: string): AuditEvent[] {
    return this.events.filter((e) => e.action === action)
  }
}

/** membership state of every telegram account in the group */
export type MembershipState = ChatMembership | "absent"

export class FakeTelegramApi implements TelegramApi {
  readonly sent: { target: string; text: string; keyboard?: InlineKeyboard; effectId?: string }[] = []
  readonly editedTexts: { chatId: number; messageId: number; text: string; keyboard?: InlineKeyboard }[] = []
  readonly editedKeyboards: { chatId: number; messageId: number; keyboard: InlineKeyboard }[] = []
  readonly ephemeralSent: { callbackQueryId: string; chatId: number; text: string; messageId: number }[] = []
  readonly ephemeralEdited: { chatId: number; receiverUserId: number; ephemeralMessageId: number; text: string }[] = []
  readonly ephemeralDeleted: { chatId: number; receiverUserId: number; ephemeralMessageId: number }[] = []
  readonly createdLinks: { chatId: number; name: string; memberLimit: number; expireDate: number }[] = []
  readonly revokedLinks: string[] = []
  readonly bans: string[] = []
  readonly unbans: string[] = []
  /** getChatMember fails when this is set — emulates api outage */
  membershipFailure = false
  membership: Map<string, MembershipState> = new Map()
  private nextMessageId = 1
  private readonly linkUrls = new Map<string, string>()

  constructor(
    private readonly opts: { groupChatId: number | null } = { groupChatId: -100_500 },
  ) {}

  async sendUserMessage(
    telegramId: string,
    text: string,
    keyboard?: InlineKeyboard,
    sendOpts?: { effectId?: string },
  ) {
    if (telegramId === "unreachable") return null
    this.sent.push({ target: `user:${telegramId}`, text, keyboard, effectId: sendOpts?.effectId })
    const messageId = this.nextMessageId++
    return { applicationId: "", telegramId, chatId: Number(telegramId), messageId, kind: "group" as const }
  }

  async sendGroupMessage(text: string, keyboard?: InlineKeyboard) {
    if (this.opts.groupChatId === null) return null
    this.sent.push({ target: "group", text, keyboard })
    const messageId = this.nextMessageId++
    return { applicationId: "", telegramId: "", chatId: this.opts.groupChatId, messageId, kind: "group" as const }
  }

  async editMessageReplyMarkup(chatId: number, messageId: number, keyboard: InlineKeyboard) {
    this.editedKeyboards.push({ chatId, messageId, keyboard })
    return true
  }

  async editMessageText(chatId: number, messageId: number, text: string, keyboard?: InlineKeyboard) {
    this.editedTexts.push({ chatId, messageId, text, keyboard })
    return true
  }

  async sendEphemeralReply(callbackQueryId: string, chatId: number, text: string) {
    const messageId = this.nextMessageId++
    this.ephemeralSent.push({ callbackQueryId, chatId, text, messageId })
    return messageId
  }

  async editEphemeralMessageText(
    chatId: number,
    receiverUserId: number,
    ephemeralMessageId: number,
    text: string,
  ) {
    this.ephemeralEdited.push({ chatId, receiverUserId, ephemeralMessageId, text })
    return true
  }

  async deleteEphemeralMessage(
    chatId: number,
    receiverUserId: number,
    ephemeralMessageId: number,
  ) {
    this.ephemeralDeleted.push({ chatId, receiverUserId, ephemeralMessageId })
    return true
  }

  async createChatInviteLink(chatId: number, opts: { name: string; memberLimit: number; expireDate: number }) {
    this.createdLinks.push({ ...opts, chatId })
    const url = `https://t.me/+${opts.name}`
    this.linkUrls.set(opts.name, url)
    return { url, name: opts.name }
  }

  async revokeChatInviteLink(_chatId: number, link: string) {
    this.revokedLinks.push(link)
  }

  async banChatMember(_chatId: number, userId: string) {
    this.bans.push(userId)
    this.membership.set(userId, "kicked")
  }

  async unbanChatMember(_chatId: number, userId: string) {
    this.unbans.push(userId)
    if (this.membership.get(userId) === "kicked") this.membership.set(userId, "left")
  }

  async getChatMember(_chatId: number, userId: string): Promise<ChatMembership> {
    if (this.membershipFailure) throw new Error("api down")
    const state = this.membership.get(userId)
    if (state === undefined || state === "absent") return "left"
    return state
  }

  urlOf(name: string): string {
    return this.linkUrls.get(name) ?? ""
  }

  groupSent(): { text: string; keyboard?: InlineKeyboard }[] {
    return this.sent.filter((s) => s.target === "group")
  }
}

/** recording MessageSender for NotificationService-based fan-outs in tests */
export class RecordingMessageSender {
  readonly sent: { chatId: string; text: string }[] = []
  failures = new Set<string>()

  async send(chatId: string, text: string): Promise<void> {
    if (this.failures.has(chatId)) throw new Error(`delivery to ${chatId} failed`)
    this.sent.push({ chatId, text })
  }

  to(chatId: string): string[] {
    return this.sent.filter((s) => s.chatId === chatId).map((s) => s.text)
  }
}

export function user(overrides: Partial<UserInfo> & { uuid: string }): UserInfo {
  return {
    username: `user-${overrides.uuid}`,
    telegramId: `tg-${overrides.uuid}`,
    status: "ACTIVE",
    lastApplicationAt: null,
    groupLinkedAt: null,
    language: "ru",
    ...overrides,
  }
}

export function makeVoters(count: number, startId = 1): UserInfo[] {
  return Array.from({ length: count }, (_, i) =>
    user({ uuid: `uuid-${startId + i}`, telegramId: `tg-${startId + i}` }),
  )
}

/** builds a voting application directly in the store, bypassing the flow */
export async function seedVotingApplication(
  apps: InMemoryApplicationRepository,
  opts: {
    uuid?: string
    telegramId?: string
    eligibleVoters?: number
    durationMs?: number
    now?: number
    quorumState?: { votesFor: number; votesAgainst: number }
    /** vote storage matching quorumState, when the votes collection matters */
    votes?: InMemoryVoteRepository
  } = {},
): Promise<ApplicationInfo> {
  const uuid = opts.uuid ?? "applicant"
  const now = opts.now ?? 1_700_000_000_000
  const durationMs = opts.durationMs ?? 3 * 3_600_000
  const insertResult = await apps.insert({
    minecraftUuid: uuid,
    telegramId: opts.telegramId ?? `tg-${uuid}`,
    answers: [{ question: "q1", answer: "a1" }],
    voting: {
      startedAt: new Date(now),
      expiresAt: new Date(now + durationMs),
      eligibleVoters: opts.eligibleVoters ?? 5,
    },
  })
  if (insertResult === "duplicate-live") throw new Error("seed duplicate")
  const app = await apps.findById(insertResult.id)
  if (!app) throw new Error("seed failed")
  if (opts.quorumState) {
    for (let i = 0; i < opts.quorumState.votesFor; i++) {
      const voterUuid = `seed-for-${i}`
      await apps.incrementVoteCounters(app.id, "ACCEPT")
      await opts.votes?.insert({
        applicationId: app.id,
        voterUuid,
        decision: "ACCEPT",
        createdAt: new Date(now),
      })
    }
    for (let i = 0; i < opts.quorumState.votesAgainst; i++) {
      const voterUuid = `seed-against-${i}`
      await apps.incrementVoteCounters(app.id, "REJECT")
      await opts.votes?.insert({
        applicationId: app.id,
        voterUuid,
        decision: "REJECT",
        createdAt: new Date(now),
      })
    }
  }
  return (await apps.findById(app.id))!
}

export function voteCount(app: ApplicationInfo): { votesFor: number; votesAgainst: number } {
  return { votesFor: app.voting.votesFor, votesAgainst: app.voting.votesAgainst }
}

export function assertQuorumUnchanged(app: ApplicationInfo): boolean {
  return hasQuorum(app.voting.votesFor + app.voting.votesAgainst, app.voting.eligibleVoters)
}
