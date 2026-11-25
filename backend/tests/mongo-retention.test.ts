import { afterAll, beforeEach, describe, expect, test } from "bun:test"
import mongoose from "mongoose"
import {
  MongoApplicationPurge,
  MongoAuditLogPurge,
  MongoGroupInvitePurge,
  MongoGuestSnapshotPurge,
  MongoServerEventPurge,
  MongoSessionPurge,
  MongoVotePurge,
  MongoVotingMessagePurge,
} from "../src/modules/retention/repository-mongo.js"
import { mongoUnavailableReason, startTestMongo, type TestMongo } from "./helpers/mongo.js"

/**
 * the retention sweep is the only thing keeping the m0 tier from filling up,
 * and its failure mode is silent data loss. every purge is seeded with live and
 * dead rows side by side and must remove exactly the dead ones.
 */

const mongo: TestMongo | null = await startTestMongo("retention")
if (!mongo) console.warn(`[mongo retention skipped] ${mongoUnavailableReason()}`)

const describeDb = describe.skipIf(mongo === null)

afterAll(async () => {
  await mongo?.stop()
})

const OLD = new Date("2025-01-01T00:00:00Z")
const CUTOFF = new Date("2026-01-01T00:00:00Z")
const RECENT = new Date("2026-06-01T00:00:00Z")

const db = (): mongoose.mongo.Db => mongoose.connection.db!

const sessions = new MongoSessionPurge()
const snapshots = new MongoGuestSnapshotPurge()
const messages = new MongoVotingMessagePurge()
const votes = new MongoVotePurge()
const invites = new MongoGroupInvitePurge()
const applications = new MongoApplicationPurge()
const audit = new MongoAuditLogPurge()
const serverEvents = new MongoServerEventPurge()

async function seedSession(doc: {
  minecraftUuid: string
  sessionId: string
  startedAt: Date
  endedAt: Date | null
  status?: string
}): Promise<void> {
  await mongoose.connection.collection("sessions").insertOne({
    minecraftUuid: doc.minecraftUuid,
    sessionId: doc.sessionId,
    serverId: "game-1",
    status: doc.status ?? (doc.endedAt === null ? "GUEST" : "DISCONNECTED"),
    startedAt: doc.startedAt,
    endedAt: doc.endedAt,
  })
}

async function seedApplication(doc: {
  minecraftUuid: string
  status: string
  decidedAt: Date | null
}): Promise<mongoose.Types.ObjectId> {
  const _id = new mongoose.Types.ObjectId()
  await mongoose.connection.collection("applications").insertOne({
    _id,
    minecraftUuid: doc.minecraftUuid,
    telegramId: `tg-${doc.minecraftUuid}`,
    answers: [],
    status: doc.status,
    voting: {
      startedAt: OLD,
      expiresAt: OLD,
      eligibleVoters: 5,
      votesFor: 0,
      votesAgainst: 0,
      abstentions: 0,
      extensionCount: 0,
      votesCast: null,
      quorumReached: null,
      decidedAt: doc.decidedAt,
    },
  })
  return _id
}

describeDb("retention purges leave live rows alone", () => {
  // every case seeds its own live/dead pair, so start each one from an empty db
  beforeEach(async () => {
    await mongoose.connection.dropDatabase()
  })

  test("closed sessions past the cutoff die, open and recent ones survive", async () => {
    await seedSession({ minecraftUuid: "ret-open", sessionId: "s-open", startedAt: OLD, endedAt: null })
    await seedSession({ minecraftUuid: "ret-closed-dead", sessionId: "s-dead", startedAt: OLD, endedAt: OLD })
    await seedSession({
      minecraftUuid: "ret-closed-recent",
      sessionId: "s-recent",
      startedAt: RECENT,
      endedAt: RECENT,
    })

    expect(await sessions.deleteClosedOlderThan(CUTOFF)).toBe(1)
    expect(await mongoose.connection.collection("sessions").countDocuments({ minecraftUuid: "ret-open" })).toBe(1)
    expect(await mongoose.connection.collection("sessions").countDocuments({ minecraftUuid: "ret-closed-recent" })).toBe(
      1,
    )
    expect(await mongoose.connection.collection("sessions").countDocuments({ minecraftUuid: "ret-closed-dead" })).toBe(0)
  })

  test("pruneDuplicateActive keeps the newest open session per player", async () => {
    // no unique index here on purpose: this sweep exists for leftovers from a
    // crash, which is the only way two open rows can appear
    await seedSession({ minecraftUuid: "ret-dup", sessionId: "dup-old", startedAt: OLD, endedAt: null })
    await seedSession({ minecraftUuid: "ret-dup", sessionId: "dup-new", startedAt: RECENT, endedAt: null })
    await seedSession({ minecraftUuid: "ret-solo", sessionId: "solo", startedAt: OLD, endedAt: null })

    expect(await sessions.pruneDuplicateActive()).toBe(1)

    const remaining = await mongoose.connection
      .collection("sessions")
      .find({ minecraftUuid: "ret-dup" })
      .toArray()
    expect(remaining.map((doc) => doc.sessionId)).toEqual(["dup-new"])
    expect(await mongoose.connection.collection("sessions").countDocuments({ minecraftUuid: "ret-solo" })).toBe(1)
  })

  test("snapshots go only when the session is over and the row is old", async () => {
    await seedSession({ minecraftUuid: "ret-snap-live", sessionId: "snap-live-session", startedAt: OLD, endedAt: null })

    const seedSnapshot = async (sessionId: string, minecraftUuid: string, createdAt: Date): Promise<void> => {
      await mongoose.connection.collection("guest_snapshots").insertOne({
        sessionId,
        minecraftUuid,
        world: "world",
        location: { x: 0, y: 64, z: 0, yaw: 0, pitch: 0 },
        gameMode: "SURVIVAL",
        flying: false,
        flySpeed: 0.1,
        walkSpeed: 0.2,
        createdAt,
        updatedAt: createdAt,
      })
    }

    await seedSnapshot("snap-orphan-old", "ret-snap-gone", OLD)
    await seedSnapshot("snap-live-old", "ret-snap-live", OLD)
    await seedSnapshot("snap-orphan-recent", "ret-snap-gone-2", RECENT)

    expect(await snapshots.deleteOrphanedOlderThan(CUTOFF)).toBe(1)
    expect(await mongoose.connection.collection("guest_snapshots").countDocuments({ sessionId: "snap-orphan-old" })).toBe(
      0,
    )
    expect(await mongoose.connection.collection("guest_snapshots").countDocuments({ sessionId: "snap-live-old" })).toBe(1)
    expect(
      await mongoose.connection.collection("guest_snapshots").countDocuments({ sessionId: "snap-orphan-recent" }),
    ).toBe(1)
  })

  test("voting messages survive while their voting is live and die with old decisions", async () => {
    const live = await seedApplication({ minecraftUuid: "ret-msg-live", status: "VOTING", decidedAt: null })
    const oldDecision = await seedApplication({ minecraftUuid: "ret-msg-old", status: "REJECTED", decidedAt: OLD })
    const recentDecision = await seedApplication({ minecraftUuid: "ret-msg-recent", status: "REJECTED", decidedAt: RECENT })
    const orphan = new mongoose.Types.ObjectId()

    const seedMessage = async (applicationId: mongoose.Types.ObjectId, messageId: number): Promise<void> => {
      await mongoose.connection.collection("voting_messages").insertOne({
        applicationId,
        telegramId: "tg",
        chatId: -100,
        messageId,
        kind: "group",
        createdAt: OLD,
      })
    }
    await seedMessage(live, 1)
    await seedMessage(oldDecision, 2)
    await seedMessage(recentDecision, 3)
    await seedMessage(orphan, 4)

    expect(await messages.deleteDeadOlderThan(CUTOFF)).toBe(2)
    expect(await mongoose.connection.collection("voting_messages").countDocuments({ applicationId: live })).toBe(1)
    expect(await mongoose.connection.collection("voting_messages").countDocuments({ applicationId: recentDecision })).toBe(
      1,
    )
    expect(await mongoose.connection.collection("voting_messages").countDocuments({ applicationId: oldDecision })).toBe(0)
    expect(await mongoose.connection.collection("voting_messages").countDocuments({ applicationId: orphan })).toBe(0)
  })

  test("votes follow their application: live and recent votings keep their tally", async () => {
    const live = await seedApplication({ minecraftUuid: "ret-vote-live", status: "VOTING", decidedAt: null })
    const oldDecision = await seedApplication({ minecraftUuid: "ret-vote-old", status: "REJECTED", decidedAt: OLD })
    const recentDecision = await seedApplication({
      minecraftUuid: "ret-vote-recent",
      status: "APPROVED",
      decidedAt: RECENT,
    })
    const orphan = new mongoose.Types.ObjectId()

    const seedVote = async (applicationId: mongoose.Types.ObjectId, voterUuid: string): Promise<void> => {
      await mongoose.connection.collection("votes").insertOne({
        applicationId,
        voterUuid,
        decision: "ACCEPT",
        createdAt: OLD,
      })
    }
    await seedVote(live, "v1")
    await seedVote(oldDecision, "v2")
    await seedVote(recentDecision, "v3")
    await seedVote(orphan, "v4")

    expect(await votes.deleteDecidedOlderThan(CUTOFF)).toBe(2)
    expect(await mongoose.connection.collection("votes").countDocuments({ applicationId: live })).toBe(1)
    expect(await mongoose.connection.collection("votes").countDocuments({ applicationId: recentDecision })).toBe(1)
    expect(await mongoose.connection.collection("votes").countDocuments({ applicationId: oldDecision })).toBe(0)
    expect(await mongoose.connection.collection("votes").countDocuments({ applicationId: orphan })).toBe(0)
  })

  test("invites die by their own three clocks", async () => {
    const usedCutoff = new Date("2026-01-01T00:00:00Z")
    const seedInvite = async (doc: {
      name: string
      status: string
      issuedAt: Date
      expiresAt: Date
    }): Promise<void> => {
      await mongoose.connection.collection("group_invites").insertOne({
        name: doc.name,
        applicationId: new mongoose.Types.ObjectId(),
        minecraftUuid: `uuid-${doc.name}`,
        telegramId: "tg",
        chatId: -100,
        url: `https://t.me/+${doc.name}`,
        status: doc.status,
        usedAt: doc.status === "USED" ? doc.issuedAt : null,
        issuedAt: doc.issuedAt,
        expiresAt: doc.expiresAt,
      })
    }

    await seedInvite({ name: "inv-revoked-old", status: "REVOKED", issuedAt: OLD, expiresAt: OLD })
    await seedInvite({ name: "inv-expired", status: "ISSUED", issuedAt: OLD, expiresAt: OLD })
    await seedInvite({ name: "inv-used-old", status: "USED", issuedAt: OLD, expiresAt: OLD })
    await seedInvite({ name: "inv-live", status: "ISSUED", issuedAt: OLD, expiresAt: RECENT })
    await seedInvite({ name: "inv-used-recent", status: "USED", issuedAt: RECENT, expiresAt: RECENT })

    expect(await invites.deleteDeadOlderThan(CUTOFF, usedCutoff)).toBe(3)
    const left = await mongoose.connection
      .collection("group_invites")
      .find({ name: { $in: ["inv-live", "inv-used-recent"] } })
      .toArray()
    expect(left).toHaveLength(2)
  })

  test("applications: only old rejected/expired dead ends go", async () => {
    await seedApplication({ minecraftUuid: "ret-app-old", status: "REJECTED", decidedAt: OLD })
    await seedApplication({ minecraftUuid: "ret-app-expired", status: "EXPIRED", decidedAt: OLD })
    await seedApplication({ minecraftUuid: "ret-app-recent", status: "REJECTED", decidedAt: RECENT })
    await seedApplication({ minecraftUuid: "ret-app-approved", status: "APPROVED", decidedAt: OLD })
    await seedApplication({ minecraftUuid: "ret-app-voting", status: "VOTING", decidedAt: null })
    // a rejected row that never got a decision timestamp is not old enough to judge
    await seedApplication({ minecraftUuid: "ret-app-undecided", status: "REJECTED", decidedAt: null })

    expect(await applications.deleteClosedOlderThan(CUTOFF)).toBe(2)
    const survivors = await mongoose.connection
      .collection("applications")
      .find({
        minecraftUuid: {
          $in: ["ret-app-recent", "ret-app-approved", "ret-app-voting", "ret-app-undecided"],
        },
      })
      .toArray()
    expect(survivors).toHaveLength(4)
  })

  test("audit logs and server events age out independently", async () => {
    await db().collection("audit_logs").insertOne({ actorType: "SYSTEM", actorId: "x", action: "old", createdAt: OLD })
    await db().collection("audit_logs").insertOne({ actorType: "SYSTEM", actorId: "x", action: "recent", createdAt: RECENT })
    await db().collection("server_events").insertOne({ messageId: "old", serverId: "s1", type: "T", receivedAt: OLD })
    await db().collection("server_events").insertOne({ messageId: "recent", serverId: "s1", type: "T", receivedAt: RECENT })

    expect(await audit.deleteOlderThan(CUTOFF)).toBe(1)
    expect(await serverEvents.deleteOlderThan(CUTOFF)).toBe(1)
    expect(await db().collection("audit_logs").countDocuments({ action: "recent" })).toBe(1)
    expect(await db().collection("server_events").countDocuments({ messageId: "recent" })).toBe(1)
  })
})
