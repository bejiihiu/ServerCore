import mongoose from "mongoose"
import {
  ApplicationDraftModel,
  ApplicationModel,
  AuditLogModel,
  ConversationStateModel,
  GroupInviteModel,
  UserModel,
  VotingMessageModel,
  VoteModel,
} from "./models/index.js"
import type { ApplicationStatus, UserStatus, VoteDecision } from "./models/index.js"
import type {
  ApplicationDraftRepository,
  ApplicationInfo,
  ApplicationRepository,
  AuditEvent,
  AuditSink,
  DraftInfo,
  GroupInviteInfo,
  GroupInviteRepository,
  Lang,
  NewApplication,
  NewGroupInvite,
  UserInfo,
  UserRepository,
  VoterInfo,
  VotingMessageInfo,
  VotingMessageRepository,
  VoteRepository,
} from "../services/ports.js"

/**
 * mongoose-backed implementations of the service ports. all of them map to
 * plain serializable dtos — services never see mongoose documents.
 */

// lean() returns loosely typed objects, these shapes put them back together
type ApplicationLean = {
  _id: mongoose.Types.ObjectId
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

function toApplicationInfo(doc: ApplicationLean): ApplicationInfo {
  return {
    id: doc._id.toString(),
    minecraftUuid: doc.minecraftUuid,
    telegramId: doc.telegramId,
    answers: doc.answers.map((a) => ({ question: a.question, answer: a.answer })),
    status: doc.status,
    voting: {
      startedAt: doc.voting.startedAt,
      expiresAt: doc.voting.expiresAt,
      eligibleVoters: doc.voting.eligibleVoters,
      votesFor: doc.voting.votesFor,
      votesAgainst: doc.voting.votesAgainst,
      abstentions: doc.voting.abstentions,
      extensionCount: doc.voting.extensionCount,
      votesCast: doc.voting.votesCast ?? null,
      quorumReached: doc.voting.quorumReached,
      decidedAt: doc.voting.decidedAt,
    },
    createdAt: doc.createdAt,
  }
}

type UserLean = {
  minecraft: { uuid: string; username: string }
  telegram: { id: string; groupLinkedAt: Date | null }
  status: UserStatus
  language: Lang
  lastApplicationAt: Date | null
}

type VoterLean = { "minecraft.uuid": string; "minecraft.username": string; "telegram.id": string }

function toUserInfo(doc: UserLean): UserInfo {
  return {
    uuid: doc.minecraft.uuid,
    username: doc.minecraft.username,
    telegramId: doc.telegram.id,
    status: doc.status,
    lastApplicationAt: doc.lastApplicationAt,
    groupLinkedAt: doc.telegram.groupLinkedAt,
    language: doc.language,
  }
}

type InviteLean = {
  _id: mongoose.Types.ObjectId
  name: string
  applicationId: mongoose.Types.ObjectId
  minecraftUuid: string
  telegramId: string
  chatId: number
  url: string
  status: GroupInviteInfo["status"]
  issuedAt: Date
  usedAt: Date | null
  expiresAt: Date
}

function toInviteInfo(doc: InviteLean): GroupInviteInfo {
  return {
    name: doc.name,
    applicationId: doc.applicationId.toString(),
    minecraftUuid: doc.minecraftUuid,
    telegramId: doc.telegramId,
    chatId: doc.chatId,
    url: doc.url,
    status: doc.status,
    issuedAt: doc.issuedAt,
    usedAt: doc.usedAt,
    expiresAt: doc.expiresAt,
  }
}

function isDuplicateKeyError(err: unknown): boolean {
  return (
    typeof err === "object" &&
    err !== null &&
    "code" in err &&
    (err as { code?: unknown }).code === 11000
  )
}

const LIVE_APPLICATION_STATUSES: ApplicationStatus[] = ["PENDING", "VOTING"]

export class MongooseUserRepository implements UserRepository {
  async findByUuid(uuid: string): Promise<UserInfo | null> {
    const doc = await UserModel.findOne({ "minecraft.uuid": uuid }).lean<UserLean>()
    return doc ? toUserInfo(doc) : null
  }

  async findByTelegramId(telegramId: string): Promise<UserInfo | null> {
    const doc = await UserModel.findOne({ "telegram.id": telegramId }).lean<UserLean>()
    return doc ? toUserInfo(doc) : null
  }

  async listEligibleVoters(): Promise<VoterInfo[]> {
    const docs = await UserModel.find({
      status: "ACTIVE",
      "telegram.id": { $exists: true, $ne: "" },
    })
      .lean()
      .select({ "minecraft.uuid": 1, "minecraft.username": 1, "telegram.id": 1 })
    // the select projection shapes the lean docs; lean types don't narrow on it
    const projected = docs as unknown as VoterLean[]
    return projected.map((doc) => ({
      uuid: doc["minecraft.uuid"],
      username: doc["minecraft.username"],
      telegramId: doc["telegram.id"],
    }))
  }

  async setStatus(uuid: string, from: UserStatus[], to: UserStatus): Promise<boolean> {
    const result = await UserModel.updateOne(
      { "minecraft.uuid": uuid, status: { $in: from } },
      { $set: { status: to } },
    )
    return result.modifiedCount === 1
  }

  async setLastApplicationAt(uuid: string, at: Date): Promise<void> {
    await UserModel.updateOne({ "minecraft.uuid": uuid }, { $set: { lastApplicationAt: at } })
  }

  async markGroupLinked(uuid: string, at: Date): Promise<boolean> {
    const result = await UserModel.updateOne(
      { "minecraft.uuid": uuid },
      { $set: { "telegram.groupLinkedAt": at } },
    )
    return result.matchedCount === 1
  }

  async setLanguage(uuid: string, language: Lang): Promise<void> {
    await UserModel.updateOne({ "minecraft.uuid": uuid }, { $set: { language } })
  }
}

export class MongooseApplicationRepository implements ApplicationRepository {
  async insert(app: NewApplication): Promise<{ id: string } | "duplicate-live"> {
    try {
      const doc = await ApplicationModel.create({
        minecraftUuid: app.minecraftUuid,
        telegramId: app.telegramId,
        answers: app.answers,
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
      })
      return { id: doc._id.toString() }
    } catch (err) {
      if (isDuplicateKeyError(err)) return "duplicate-live"
      throw err
    }
  }

  async findById(id: string): Promise<ApplicationInfo | null> {
    if (!mongoose.isValidObjectId(id)) return null
    const doc = (await ApplicationModel.findById(id).lean()) as unknown as ApplicationLean | null
    return doc ? toApplicationInfo(doc) : null
  }

  async findLiveByUuid(uuid: string): Promise<ApplicationInfo | null> {
    const doc = (await ApplicationModel.findOne({
      minecraftUuid: uuid,
      status: { $in: LIVE_APPLICATION_STATUSES },
    })
      .lean()
      .sort({ createdAt: -1 })) as unknown as ApplicationLean | null
    return doc ? toApplicationInfo(doc) : null
  }

  async findLatestByUuid(uuid: string): Promise<ApplicationInfo | null> {
    const doc = (await ApplicationModel.findOne({ minecraftUuid: uuid })
      .lean()
      .sort({ createdAt: -1 })) as unknown as ApplicationLean | null
    return doc ? toApplicationInfo(doc) : null
  }

  async setStatus(id: string, from: ApplicationStatus[], to: ApplicationStatus): Promise<boolean> {
    const result = await ApplicationModel.updateOne(
      { _id: id, status: { $in: from } },
      { $set: { status: to } },
    )
    return result.modifiedCount === 1
  }

  async incrementVoteCounters(
    id: string,
    decision: VoteDecision,
  ): Promise<{ votesFor: number; votesAgainst: number } | null> {
    const field = decision === "ACCEPT" ? "voting.votesFor" : "voting.votesAgainst"
    const doc = await ApplicationModel.findOneAndUpdate(
      { _id: id, status: "VOTING" },
      { $inc: { [field]: 1 } },
      { new: true },
    ).lean()
    if (!doc) return null
    const shaped = doc as unknown as ApplicationLean
    return { votesFor: shaped.voting.votesFor, votesAgainst: shaped.voting.votesAgainst }
  }

  async saveDecision(
    id: string,
    decision: { status: ApplicationStatus; votesCast: number; quorumReached: boolean; decidedAt: Date },
  ): Promise<boolean> {
    // defense in depth on top of the scheduler reentrancy guard: only a voting
    // still in VOTING may be decided, a lost race returns false
    const result = await ApplicationModel.updateOne(
      { _id: id, status: "VOTING" },
      {
        $set: {
          status: decision.status,
          "voting.votesCast": decision.votesCast,
          "voting.quorumReached": decision.quorumReached,
          "voting.decidedAt": decision.decidedAt,
        },
      },
    )
    return result.modifiedCount === 1
  }

  async extendVoting(id: string, newExpiresAt: Date): Promise<boolean> {
    // single-shot guard: only the first extension wins, repeats are inert
    const result = await ApplicationModel.updateOne(
      { _id: id, status: "VOTING", "voting.extensionCount": 0 },
      {
        $set: { "voting.expiresAt": newExpiresAt },
        $inc: { "voting.extensionCount": 1 },
      },
    )
    return result.modifiedCount === 1
  }

  async findExpiredVotings(now: Date): Promise<ApplicationInfo[]> {
    const docs = (await ApplicationModel.find({
      status: "VOTING",
      "voting.expiresAt": { $lte: now },
    })
      .lean()
      .sort({ "voting.expiresAt": 1 })) as unknown as ApplicationLean[]
    return docs.map(toApplicationInfo)
  }
}

export class MongooseVoteRepository implements VoteRepository {
  async insert(vote: {
    applicationId: string
    voterUuid: string
    decision: VoteDecision
    createdAt: Date
  }): Promise<"inserted" | "duplicate"> {
    // cheap pre-check catches the realistic double-callback race; the
    // compound unique index is the hard guarantee underneath
    const existing = await VoteModel.findOne({
      applicationId: vote.applicationId,
      voterUuid: vote.voterUuid,
    }).lean()
    if (existing) return "duplicate"
    try {
      await VoteModel.create({
        applicationId: new mongoose.Types.ObjectId(vote.applicationId),
        voterUuid: vote.voterUuid,
        decision: vote.decision,
      })
      return "inserted"
    } catch (err) {
      if (isDuplicateKeyError(err)) return "duplicate"
      throw err
    }
  }

  async countByDecision(applicationId: string): Promise<{ votesFor: number; votesAgainst: number }> {
    const rows = await VoteModel.aggregate<{ _id: VoteDecision; count: number }>([
      { $match: { applicationId: new mongoose.Types.ObjectId(applicationId) } },
      { $group: { _id: "$decision", count: { $sum: 1 } } },
    ])
    const votesFor = rows.find((r) => r._id === "ACCEPT")?.count ?? 0
    const votesAgainst = rows.find((r) => r._id === "REJECT")?.count ?? 0
    return { votesFor, votesAgainst }
  }
}

export class MongooseVotingMessageRepository implements VotingMessageRepository {
  async saveMany(messages: VotingMessageInfo[]): Promise<void> {
    if (messages.length === 0) return
    await VotingMessageModel.insertMany(
      messages.map((m) => ({
        applicationId: new mongoose.Types.ObjectId(m.applicationId),
        telegramId: m.telegramId,
        chatId: m.chatId,
        messageId: m.messageId,
        kind: m.kind,
      })),
    )
  }

  async listByApplication(applicationId: string): Promise<VotingMessageInfo[]> {
    if (!mongoose.isValidObjectId(applicationId)) return []
    const docs = await VotingMessageModel.find({
      applicationId: new mongoose.Types.ObjectId(applicationId),
    }).lean()
    return docs.map((doc) => ({
      applicationId,
      telegramId: doc.telegramId,
      chatId: doc.chatId,
      messageId: doc.messageId,
      kind: doc.kind === "ephemeral" ? "ephemeral" : "group",
    }))
  }

  async deleteByApplication(applicationId: string): Promise<void> {
    if (!mongoose.isValidObjectId(applicationId)) return
    await VotingMessageModel.deleteMany({
      applicationId: new mongoose.Types.ObjectId(applicationId),
    })
  }
}

export class MongooseGroupInviteRepository implements GroupInviteRepository {
  async insert(invite: NewGroupInvite): Promise<void> {
    await GroupInviteModel.create({
      name: invite.name,
      applicationId: new mongoose.Types.ObjectId(invite.applicationId),
      minecraftUuid: invite.minecraftUuid,
      telegramId: invite.telegramId,
      chatId: invite.chatId,
      url: invite.url,
      status: "ISSUED",
      usedAt: null,
      issuedAt: invite.issuedAt,
      expiresAt: invite.expiresAt,
    })
  }

  async findByName(name: string): Promise<GroupInviteInfo | null> {
    const doc = (await GroupInviteModel.findOne({ name }).lean()) as unknown as InviteLean | null
    return doc ? toInviteInfo(doc) : null
  }

  async markUsed(name: string, usedAt: Date): Promise<"used" | "already-used" | "not-found"> {
    const result = await GroupInviteModel.updateOne(
      { name, status: "ISSUED" },
      { $set: { status: "USED", usedAt } },
    )
    if (result.modifiedCount === 1) return "used"
    const doc = await GroupInviteModel.findOne({ name }).lean()
    return doc ? "already-used" : "not-found"
  }
}

export class MongooseApplicationDraftRepository implements ApplicationDraftRepository {
  async save(draft: DraftInfo): Promise<void> {
    await ApplicationDraftModel.updateOne(
      { telegramId: draft.telegramId },
      {
        $set: {
          minecraftUuid: draft.minecraftUuid,
          username: draft.username,
          step: draft.step,
          answers: draft.answers,
          language: draft.language,
        },
      },
      { upsert: true },
    )
  }

  async findByTelegramId(telegramId: string): Promise<DraftInfo | null> {
    const doc = await ApplicationDraftModel.findOne({ telegramId }).lean()
    return doc ? toDraftInfo(doc) : null
  }

  async deleteByTelegramId(telegramId: string): Promise<DraftInfo | null> {
    const doc = await ApplicationDraftModel.findOneAndDelete({ telegramId }).lean()
    return doc ? toDraftInfo(doc) : null
  }

  async listAll(): Promise<DraftInfo[]> {
    const docs = await ApplicationDraftModel.find().lean()
    return docs.map(toDraftInfo)
  }
}

type DraftLean = {
  telegramId: string
  minecraftUuid: string
  username: string
  step: number
  answers: string[]
  language?: Lang
}

function toDraftInfo(doc: DraftLean): DraftInfo {
  return {
    telegramId: doc.telegramId,
    minecraftUuid: doc.minecraftUuid,
    username: doc.username,
    step: doc.step,
    answers: [...doc.answers],
    language: doc.language ?? "ru",
  }
}

export class MongooseAuditSink implements AuditSink {
  async record(event: AuditEvent): Promise<void> {
    await AuditLogModel.create({
      actorType: event.actorType,
      actorId: event.actorId,
      action: event.action,
      targetType: event.targetType ?? null,
      targetId: event.targetId ?? null,
      details: event.details ?? {},
      serverId: null,
      relatedSessionId: null,
    })
  }
}

export type AdmissionRepositories = {
  users: UserRepository
  applications: ApplicationRepository
  votes: VoteRepository
  votingMessages: VotingMessageRepository
  invites: GroupInviteRepository
  drafts: ApplicationDraftRepository
  audit: AuditSink
}

export function createAdmissionRepositories(): AdmissionRepositories {
  return {
    users: new MongooseUserRepository(),
    applications: new MongooseApplicationRepository(),
    votes: new MongooseVoteRepository(),
    votingMessages: new MongooseVotingMessageRepository(),
    invites: new MongooseGroupInviteRepository(),
    drafts: new MongooseApplicationDraftRepository(),
    audit: new MongooseAuditSink(),
  }
}

/** creates the indexes the admission flow depends on, best effort at boot */
export async function syncAdmissionIndexes(): Promise<void> {
  await Promise.all([
    ApplicationModel.syncIndexes(),
    VoteModel.syncIndexes(),
    ApplicationDraftModel.syncIndexes(),
    VotingMessageModel.syncIndexes(),
    GroupInviteModel.syncIndexes(),
    ConversationStateModel.syncIndexes(),
  ])
}
