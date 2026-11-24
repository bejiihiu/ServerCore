import { MongoError } from "mongodb"
import { AuthRequestModel } from "../../database/models/auth-request.js"
import { GuestSnapshotModel } from "../../database/models/guest-snapshot.js"
import { PlayerSessionModel } from "../../database/models/session.js"
import { UserModel } from "../../database/models/user.js"
import {
  ACTIVE_USER_STATUS,
  type AuthRequestRecord,
  type AuthenticationStore,
  type GuestSnapshotRecord,
  type SessionRecord,
  type SessionStatus,
  type TerminalAuthStatus,
  type UserRecord,
} from "./persistence.js"

const DUPLICATE_KEY = 11000

function isDuplicateKey(error: unknown): boolean {
  return error instanceof MongoError && error.code === DUPLICATE_KEY
}

/**
 * mongoose-backed authentication store. all timestamps cross the seam as
 * epoch millis so the service layer never deals with Date objects.
 */
export class MongoAuthenticationStore implements AuthenticationStore {
  async findOpenSession(minecraftUuid: string): Promise<SessionRecord | null> {
    const doc = await PlayerSessionModel.findOne({ minecraftUuid, endedAt: null }).lean()
    return doc ? toSessionRecord(doc) : null
  }

  async createSession(session: SessionRecord): Promise<SessionRecord> {
    try {
      const created = await PlayerSessionModel.create({
        minecraftUuid: session.minecraftUuid,
        sessionId: session.sessionId,
        serverId: session.serverId,
        status: session.status,
        startedAt: new Date(session.startedAt),
        endedAt: null,
      })
      return toSessionRecord(created.toObject())
    } catch (error) {
      if (!isDuplicateKey(error)) throw error
      // another connection won the race for the open session, reuse it
      const existing = await this.findOpenSession(session.minecraftUuid)
      if (!existing) throw error
      return existing
    }
  }

  async updateOpenSessionStatus(minecraftUuid: string, status: SessionStatus): Promise<SessionRecord | null> {
    const doc = await PlayerSessionModel.findOneAndUpdate(
      { minecraftUuid, endedAt: null },
      { $set: { status } },
      { new: true },
    ).lean()
    return doc ? toSessionRecord(doc) : null
  }

  async closeOpenSession(minecraftUuid: string): Promise<SessionRecord | null> {
    const doc = await PlayerSessionModel.findOneAndUpdate(
      { minecraftUuid, endedAt: null },
      { $set: { status: "DISCONNECTED", endedAt: new Date() } },
      { new: true },
    ).lean()
    return doc ? toSessionRecord(doc) : null
  }

  async saveGuestSnapshot(snapshot: GuestSnapshotRecord): Promise<void> {
    // upsert with $setOnInsert only: a redelivered durable frame (same session
    // id) confirms the existing row instead of overwriting the original spot
    await GuestSnapshotModel.updateOne(
      { sessionId: snapshot.sessionId },
      {
        $setOnInsert: {
          sessionId: snapshot.sessionId,
          minecraftUuid: snapshot.minecraftUuid,
          world: snapshot.world,
          location: snapshot.location,
          gameMode: snapshot.gameMode,
          flying: snapshot.flying,
          flySpeed: snapshot.flySpeed,
          walkSpeed: snapshot.walkSpeed,
        },
      },
      { upsert: true },
    )
  }

  async deleteGuestSnapshot(sessionId: string): Promise<number> {
    const result = await GuestSnapshotModel.deleteMany({ sessionId })
    return result.deletedCount ?? 0
  }

  async insertAuthRequest(request: AuthRequestRecord): Promise<void> {
    // tokenHash has a unique index: collisions from the random generator get
    // retried by the caller, everything else bubbles up
    await AuthRequestModel.create({
      minecraftUuid: request.minecraftUuid,
      telegramId: request.telegramId,
      sessionId: request.sessionId,
      tokenHash: request.tokenHash,
      status: request.status,
      deliveredAt: request.deliveredAt === null ? null : new Date(request.deliveredAt),
      expiresAt: new Date(request.expiresAt),
      createdAt: new Date(request.createdAt),
    })
  }

  async findAuthRequestByTokenHash(tokenHash: string): Promise<AuthRequestRecord | null> {
    const doc = await AuthRequestModel.findOne({ tokenHash }).lean()
    return doc ? toAuthRequestRecord(doc) : null
  }

  async claimPendingAuthRequest(
    id: string,
    status: TerminalAuthStatus,
    _now: number,
  ): Promise<AuthRequestRecord | null> {
    // the status filter is the whole one-time guarantee: exactly one caller
    // ever flips PENDING to a terminal state
    const doc = await AuthRequestModel.findOneAndUpdate(
      { _id: id, status: "PENDING" },
      { $set: { status } },
      { new: true },
    ).lean()
    return doc ? toAuthRequestRecord(doc) : null
  }

  async markAuthRequestDelivered(id: string, deliveredAt: number): Promise<void> {
    await AuthRequestModel.updateOne(
      { _id: id, deliveredAt: null },
      { $set: { deliveredAt: new Date(deliveredAt) } },
    )
  }

  async cancelPendingForPlayer(minecraftUuid: string, _now: number): Promise<number> {
    const result = await AuthRequestModel.updateMany(
      { minecraftUuid, status: "PENDING" },
      { $set: { status: "CANCELLED" } },
    )
    return result.modifiedCount
  }

  async findPendingExpired(now: number): Promise<AuthRequestRecord[]> {
    const docs = await AuthRequestModel.find({ status: "PENDING", expiresAt: { $lte: new Date(now) } })
      .limit(100)
      .lean()
    return docs.map(toAuthRequestRecord)
  }

  async findPendingUndelivered(): Promise<AuthRequestRecord[]> {
    const docs = await AuthRequestModel.find({ status: "PENDING", deliveredAt: null }).limit(100).lean()
    return docs.map(toAuthRequestRecord)
  }

  async findUserByUuid(minecraftUuid: string): Promise<UserRecord | null> {
    const doc = await UserModel.findOne({ "minecraft.uuid": minecraftUuid }).lean()
    if (!doc) return null
    const user: UserRecord = {
      minecraftUuid: doc.minecraft.uuid,
      username: doc.minecraft.username,
      telegramId: doc.telegram.id,
      status: doc.status,
    }
    // only accounts that cleared the whole admission path may authenticate
    return user.status === ACTIVE_USER_STATUS && user.telegramId !== "" ? user : null
  }
}

type LeanSession = {
  _id: unknown
  minecraftUuid: string
  sessionId: string
  serverId: string
  status: SessionStatus
  startedAt: Date
  endedAt: Date | null
}

function toSessionRecord(doc: LeanSession): SessionRecord {
  return {
    minecraftUuid: doc.minecraftUuid,
    sessionId: doc.sessionId,
    serverId: doc.serverId,
    status: doc.status,
    startedAt: doc.startedAt.getTime(),
    endedAt: doc.endedAt === null ? null : doc.endedAt.getTime(),
  }
}

type LeanAuthRequest = {
  _id: unknown
  minecraftUuid: string
  telegramId: string
  sessionId: string
  tokenHash: string
  status: AuthRequestRecord["status"]
  deliveredAt: Date | null
  expiresAt: Date
  createdAt: Date
}

function toAuthRequestRecord(doc: LeanAuthRequest): AuthRequestRecord {
  return {
    id: String(doc._id),
    minecraftUuid: doc.minecraftUuid,
    telegramId: doc.telegramId,
    sessionId: doc.sessionId,
    tokenHash: doc.tokenHash,
    status: doc.status,
    deliveredAt: doc.deliveredAt === null ? null : doc.deliveredAt.getTime(),
    expiresAt: doc.expiresAt.getTime(),
    createdAt: doc.createdAt.getTime(),
  }
}
