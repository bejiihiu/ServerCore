import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  AuthRequestModel,
  GuestSnapshotModel,
  PlayerSessionModel,
} from "../src/database/models/index.js"
import { MongoAuthenticationStore } from "../src/modules/authentication/mongo-store.js"
import type { AuthRequestRecord, GuestSnapshotRecord } from "../src/modules/authentication/persistence.js"
import { expectDuplicateKey, mongoUnavailableReason, startTestMongo, type TestMongo } from "./helpers/mongo.js"

/**
 * authentication-side invariants that only a real server can prove: the
 * one-time claim is a single atomic update, sessions keep exactly one open row
 * per player, and a redelivered guest snapshot can never overwrite the capture.
 */

const mongo: TestMongo | null = await startTestMongo("auth-sessions")
if (!mongo) console.warn(`[mongo auth/sessions skipped] ${mongoUnavailableReason()}`)

const describeDb = describe.skipIf(mongo === null)

afterAll(async () => {
  await mongo?.stop()
})

const store = new MongoAuthenticationStore()

const NOW = new Date("2026-11-25T09:00:00Z").getTime()

function authRequest(overrides: Partial<AuthRequestRecord> = {}): AuthRequestRecord {
  return {
    id: "",
    minecraftUuid: "uuid-auth-1",
    telegramId: "800000001",
    sessionId: "session-auth-1",
    tokenHash: `hash-${Math.random().toString(36).slice(2)}`,
    status: "PENDING",
    deliveredAt: null,
    expiresAt: NOW + 75_000,
    createdAt: NOW,
    ...overrides,
  }
}

function snapshot(overrides: Partial<GuestSnapshotRecord> = {}): GuestSnapshotRecord {
  return {
    sessionId: "guest-session-1",
    minecraftUuid: "uuid-guest-1",
    world: "world",
    location: { x: 1.5, y: 64, z: -3.25, yaw: 90, pitch: 0 },
    gameMode: "SURVIVAL",
    flying: false,
    flySpeed: 0.1,
    walkSpeed: 0.2,
    ...overrides,
  }
}

describeDb("auth requests", () => {
  beforeAll(async () => {
    await AuthRequestModel.syncIndexes()
  })

  test("the ttl index drops expired requests on its own", async () => {
    const indexes = (await AuthRequestModel.collection.indexes()) as unknown as {
      key: Record<string, unknown>
      expireAfterSeconds?: number
    }[]
    const ttl = indexes.find((index) => JSON.stringify(index.key) === JSON.stringify({ expiresAt: 1 }))

    expect(ttl).toBeDefined()
    expect(ttl?.expireAfterSeconds).toBe(0)
  })

  test("two requests cannot share a token hash", async () => {
    const request = authRequest({ tokenHash: "hash-collision" })
    await store.insertAuthRequest(request)

    const code = await expectDuplicateKey(store.insertAuthRequest(authRequest({ tokenHash: "hash-collision" })))

    expect(code).toBe(11000)
    expect(await AuthRequestModel.countDocuments({ tokenHash: "hash-collision" })).toBe(1)
  })

  test("concurrent claims: exactly one racer flips the request out of PENDING", async () => {
    for (let attempt = 0; attempt < 3; attempt++) {
      const request = authRequest({ tokenHash: `hash-claim-${attempt}` })
      await store.insertAuthRequest(request)
      const stored = await store.findAuthRequestByTokenHash(request.tokenHash)
      if (!stored) throw new Error("seed failed")

      const [approve, reject] = await Promise.all([
        store.claimPendingAuthRequest(stored.id, "APPROVED", NOW),
        store.claimPendingAuthRequest(stored.id, "REJECTED", NOW),
      ])

      const winners = [approve, reject].filter((record) => record !== null)
      expect(winners).toHaveLength(1)
      const winner = winners[0]
      const persisted = await AuthRequestModel.findById(stored.id).lean()
      expect(persisted?.status).toBe(winner?.status)
    }
  })

  test("an already cancelled request can no longer be claimed", async () => {
    const request = authRequest({ tokenHash: "hash-cancelled", minecraftUuid: "uuid-cancel-only" })
    await store.insertAuthRequest(request)
    const stored = await store.findAuthRequestByTokenHash(request.tokenHash)
    if (!stored) throw new Error("seed failed")

    expect(await store.cancelPendingForPlayer(request.minecraftUuid, NOW)).toBe(1)
    const claim = await store.claimPendingAuthRequest(stored.id, "APPROVED", NOW)

    expect(claim).toBeNull()
    expect((await AuthRequestModel.findById(stored.id).lean())?.status).toBe("CANCELLED")
  })

  test("the expiry sweep sees only pending requests past their ttl", async () => {
    const expired = authRequest({
      tokenHash: "hash-expired",
      minecraftUuid: "uuid-expired",
      expiresAt: NOW - 1_000,
    })
    const fresh = authRequest({
      tokenHash: "hash-fresh",
      minecraftUuid: "uuid-fresh",
      expiresAt: NOW + 60_000,
    })
    await store.insertAuthRequest(expired)
    await store.insertAuthRequest(fresh)

    const found = await store.findPendingExpired(NOW)
    const hashes = found.map((record) => record.tokenHash)

    expect(hashes).toContain("hash-expired")
    expect(hashes).not.toContain("hash-fresh")
  })
})

describeDb("player sessions", () => {
  beforeAll(async () => {
    await PlayerSessionModel.syncIndexes()
  })

  test("two simultaneous joins: one open session, both callers get the same row", async () => {
    const record = (sessionId: string) => ({
      minecraftUuid: "uuid-session-race",
      sessionId,
      serverId: "game-1",
      status: "WAITING_AUTH" as const,
      startedAt: NOW,
      endedAt: null,
    })

    const [first, second] = await Promise.all([
      store.createSession(record("session-race-a")),
      store.createSession(record("session-race-b")),
    ])

    expect(first.sessionId).toBe(second.sessionId)
    expect(await PlayerSessionModel.countDocuments({ minecraftUuid: "uuid-session-race" })).toBe(1)
  })

  test("closing the open session is a one-time transition", async () => {
    const opened = await store.createSession({
      minecraftUuid: "uuid-session-close",
      sessionId: "session-close",
      serverId: "game-1",
      status: "WAITING_AUTH",
      startedAt: NOW,
      endedAt: null,
    })
    expect((await store.findOpenSession("uuid-session-close"))?.sessionId).toBe(opened.sessionId)

    const closed = await store.closeOpenSession("uuid-session-close")
    expect(closed?.status).toBe("DISCONNECTED")
    expect(closed?.endedAt).not.toBeNull()

    expect(await store.findOpenSession("uuid-session-close")).toBeNull()
    expect(await store.closeOpenSession("uuid-session-close")).toBeNull()
    expect(await store.updateOpenSessionStatus("uuid-session-close", "AUTHENTICATED")).toBeNull()

    const rows = await PlayerSessionModel.find({ minecraftUuid: "uuid-session-close" }).lean()
    expect(rows).toHaveLength(1)
    expect(rows[0]?.status).toBe("DISCONNECTED")
  })

  test("a player can open a new session after the previous one closed", async () => {
    const base = { minecraftUuid: "uuid-session-rejoin", serverId: "game-1", status: "WAITING_AUTH" as const, startedAt: NOW }
    await store.createSession({ ...base, sessionId: "session-rejoin-1", endedAt: null })
    await store.closeOpenSession("uuid-session-rejoin")

    const reopened = await store.createSession({
      ...base,
      sessionId: "session-rejoin-2",
      startedAt: NOW + 1_000,
      endedAt: null,
    })

    expect(reopened.sessionId).toBe("session-rejoin-2")
    expect((await store.findOpenSession("uuid-session-rejoin"))?.sessionId).toBe("session-rejoin-2")
    expect(await PlayerSessionModel.countDocuments({ minecraftUuid: "uuid-session-rejoin" })).toBe(2)
  })

  test("status patches hit only the open session", async () => {
    await store.createSession({
      minecraftUuid: "uuid-session-status",
      sessionId: "session-status",
      serverId: "game-1",
      status: "WAITING_AUTH",
      startedAt: NOW,
      endedAt: null,
    })

    const updated = await store.updateOpenSessionStatus("uuid-session-status", "AUTHENTICATED")

    expect(updated?.status).toBe("AUTHENTICATED")
  })
})

describeDb("guest snapshots", () => {
  beforeAll(async () => {
    await GuestSnapshotModel.syncIndexes()
  })

  test("a redelivered snapshot confirms the original capture instead of overwriting it", async () => {
    await store.saveGuestSnapshot(snapshot())
    await store.saveGuestSnapshot(
      snapshot({ world: "nether", location: { x: 999, y: 70, z: 999, yaw: 0, pitch: 0 } }),
    )

    expect(await GuestSnapshotModel.countDocuments({ sessionId: "guest-session-1" })).toBe(1)
    const stored = await GuestSnapshotModel.findOne({ sessionId: "guest-session-1" }).lean()
    expect(stored?.world).toBe("world")
    expect(stored?.location.x).toBe(1.5)
    expect(stored?.location.z).toBe(-3.25)
  })

  test("the session id is unique at the index level", async () => {
    await store.saveGuestSnapshot(snapshot({ sessionId: "guest-session-unique" }))

    const code = await expectDuplicateKey(
      GuestSnapshotModel.collection.insertOne({
        sessionId: "guest-session-unique",
        minecraftUuid: "uuid-guest-2",
        world: "world",
        location: { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 },
        gameMode: "SURVIVAL",
        flying: false,
        flySpeed: 0.1,
        walkSpeed: 0.2,
      }),
    )

    expect(code).toBe(11000)
  })

  test("deleting a snapshot of a finished session removes exactly that row", async () => {
    await store.saveGuestSnapshot(snapshot({ sessionId: "guest-session-doomed" }))

    expect(await store.deleteGuestSnapshot("guest-session-doomed")).toBe(1)
    expect(await store.deleteGuestSnapshot("guest-session-doomed")).toBe(0)
    expect(await GuestSnapshotModel.findOne({ sessionId: "guest-session-1" }).lean()).not.toBeNull()
  })
})
