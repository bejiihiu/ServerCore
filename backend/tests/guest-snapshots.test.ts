import { describe, expect, test } from "bun:test"
import pino from "pino"
import type { Logger } from "../src/logging/logger.js"
import { AuthenticationService } from "../src/modules/authentication/auth-service.js"
import { InMemoryAuthenticationStore } from "../src/modules/authentication/memory-store.js"
import type { GuestSnapshotRecord } from "../src/modules/authentication/persistence.js"

const UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
const SERVER = "game-1"
const SESSION = "guest-abc123"

const CONFIG = {
  requestTtlSeconds: 75,
  serverName: "TestCore",
  rateLimitWindowMs: 300_000,
  maxRequestsPerPlayer: 5,
  maxRequestsPerTelegram: 10,
  maxApprovalsPerTelegram: 30,
}

/** the wire snapshot block, field for field what the plugin sends */
const SNAPSHOT = {
  world: "world",
  x: 1.5,
  y: 64,
  z: -3.25,
  yaw: 90,
  pitch: 0,
  gameMode: "SURVIVAL",
  flying: false,
  flySpeed: 0.1,
  walkSpeed: 0.2,
}

function silentLogger(): Logger {
  return pino({ level: "silent" })
}

function harness(store = new InMemoryAuthenticationStore()) {
  const service = new AuthenticationService({
    store,
    config: CONFIG,
    logger: silentLogger(),
    push: async () => true,
    sendPrompt: async () => true,
  })
  return { service, store }
}

/** store whose snapshot write fails like a mongo blip */
class FlakySnapshotStore extends InMemoryAuthenticationStore {
  override async saveGuestSnapshot(_snapshot: GuestSnapshotRecord): Promise<void> {
    throw new Error("mongo down")
  }
}

describe("guest snapshot persistence", () => {
  test("GUEST_STARTED stores the captured snapshot for its session", async () => {
    const { service, store } = harness()

    await service.handleGuestStarted({ minecraftUuid: UUID, sessionId: SESSION, snapshot: SNAPSHOT }, SERVER, 1_000)

    const stored = store.guestSnapshot(SESSION)
    expect(stored).not.toBeNull()
    expect(stored?.sessionId).toBe(SESSION)
    expect(stored?.minecraftUuid).toBe(UUID)
    expect(stored?.world).toBe("world")
    expect(stored?.location).toEqual({ x: 1.5, y: 64, z: -3.25, yaw: 90, pitch: 0 })
    expect(stored?.gameMode).toBe("SURVIVAL")
    expect(stored?.flying).toBe(false)
    expect(stored?.flySpeed).toBe(0.1)
    expect(stored?.walkSpeed).toBe(0.2)
  })

  test("a redelivered GUEST_STARTED is idempotent and keeps the original capture", async () => {
    const { service, store } = harness()

    await service.handleGuestStarted({ minecraftUuid: UUID, sessionId: SESSION, snapshot: SNAPSHOT }, SERVER, 1_000)
    // same durable frame replayed after a lost ack
    await service.handleGuestStarted({ minecraftUuid: UUID, sessionId: SESSION, snapshot: SNAPSHOT }, SERVER, 2_000)
    // a repeat carrying a different position must not move the saved spot
    await service.handleGuestStarted(
      { minecraftUuid: UUID, sessionId: SESSION, snapshot: { ...SNAPSHOT, x: 999, world: "nether" } },
      SERVER,
      3_000,
    )

    expect(store.guestSnapshotCount).toBe(1)
    const stored = store.guestSnapshot(SESSION)
    expect(stored?.location.x).toBe(1.5)
    expect(stored?.world).toBe("world")
  })

  test("a redelivered GUEST_ENDED stays a no-op on the session and the snapshot", async () => {
    const { service, store } = harness()

    await service.handleGuestStarted({ minecraftUuid: UUID, sessionId: SESSION, snapshot: SNAPSHOT }, SERVER, 1_000)
    await service.handleGuestEnded({ minecraftUuid: UUID, sessionId: SESSION, reason: "LOGOUT" })
    // the plugin resends the durable frame under a fresh envelope id: the
    // session is already closed and the snapshot already gone, so the replay
    // must not create anything back
    await service.handleGuestEnded({ minecraftUuid: UUID, sessionId: SESSION, reason: "LOGOUT" })

    expect(await store.findOpenSession(UUID)).toBeNull()
    expect(store.guestSnapshot(SESSION)).toBeNull()
    expect(store.sessionCount).toBe(0)
  })

  test("a redelivered GUEST_ENDED AUTHENTICATED keeps the snapshot and the session", async () => {
    const { service, store } = harness()

    await service.handleGuestStarted({ minecraftUuid: UUID, sessionId: SESSION, snapshot: SNAPSHOT }, SERVER, 1_000)
    await service.handleGuestEnded({ minecraftUuid: UUID, sessionId: SESSION, reason: "AUTHENTICATED" })
    await service.handleGuestEnded({ minecraftUuid: UUID, sessionId: SESSION, reason: "AUTHENTICATED" })

    expect(store.guestSnapshot(SESSION)).not.toBeNull()
    expect((await store.findOpenSession(UUID))?.status).toBe("AUTHENTICATED")
  })

  test("GUEST_ENDED without AUTHENTICATED purges the snapshot", async () => {
    const { service, store } = harness()

    await service.handleGuestStarted({ minecraftUuid: UUID, sessionId: SESSION, snapshot: SNAPSHOT }, SERVER, 1_000)
    expect(store.guestSnapshot(SESSION)).not.toBeNull()

    await service.handleGuestEnded({ minecraftUuid: UUID, sessionId: SESSION, reason: "LOGOUT" })

    expect(store.guestSnapshot(SESSION)).toBeNull()
    expect(store.guestSnapshotCount).toBe(0)
  })

  test("GUEST_ENDED AUTHENTICATED keeps the snapshot, the session only changed mode", async () => {
    const { service, store } = harness()

    await service.handleGuestStarted({ minecraftUuid: UUID, sessionId: SESSION, snapshot: SNAPSHOT }, SERVER, 1_000)
    await service.handleGuestEnded({ minecraftUuid: UUID, sessionId: SESSION, reason: "AUTHENTICATED" })

    expect(store.guestSnapshot(SESSION)).not.toBeNull()
  })

  test("a frame without the snapshot field still records the session", async () => {
    const { service, store } = harness()

    // defensive: a build that predates the snapshot field must not break the
    // session bookkeeping
    await service.handleGuestStarted({ minecraftUuid: UUID, sessionId: "guest-legacy" }, SERVER, 1_000)

    expect(store.guestSnapshotCount).toBe(0)
    expect((await store.findOpenSession(UUID))?.sessionId).toBe("guest-legacy")
  })

  test("a failed snapshot write never breaks the guest session itself", async () => {
    const store = new FlakySnapshotStore()
    const { service } = harness(store)

    await service.handleGuestStarted({ minecraftUuid: UUID, sessionId: SESSION, snapshot: SNAPSHOT }, SERVER, 1_000)

    // the snapshot is the backup, the session row is the live state
    expect((await store.findOpenSession(UUID))?.sessionId).toBe(SESSION)
    expect(store.guestSnapshot(SESSION)).toBeNull()
  })
})
