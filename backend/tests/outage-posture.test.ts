import { describe, expect, test } from "bun:test"
import pino from "pino"
import type { Logger } from "../src/logging/logger.js"
import { AuthenticationService, type BackendAuthType, type PromptRequest } from "../src/modules/authentication/auth-service.js"
import { InMemoryAuthenticationStore } from "../src/modules/authentication/memory-store.js"

function silentLogger(): Logger {
  return pino({ level: "silent" })
}

type PushRecord = { serverId: string; type: BackendAuthType; payload: Record<string, unknown> }

/** same harness shape as auth-service.test.ts: the link outage is a mutable set */
function harness(overrides: Partial<typeof BASE_CONFIG> = {}): Harness {
  const store = new InMemoryAuthenticationStore()
  const pushes: PushRecord[] = []
  const prompts: PromptRequest[] = []
  const deadServers = new Set<string>()
  const service = new AuthenticationService({
    store,
    config: { ...BASE_CONFIG, ...overrides },
    logger: silentLogger(),
    push: async (serverId, type, payload) => {
      if (deadServers.has(serverId)) return false
      pushes.push({ serverId, type, payload: payload as Record<string, unknown> })
      return true
    },
    sendPrompt: async (request) => {
      prompts.push(request)
      return true
    },
  })
  return { service, store, pushes, prompts, deadServers }
}

const BASE_CONFIG = {
  requestTtlSeconds: 75,
  serverName: "TestCore",
  rateLimitWindowMs: 300_000,
  maxRequestsPerPlayer: 5,
  maxRequestsPerTelegram: 10,
  maxApprovalsPerTelegram: 30,
}

type Harness = {
  service: AuthenticationService
  store: InMemoryAuthenticationStore
  pushes: PushRecord[]
  prompts: PromptRequest[]
  deadServers: Set<string>
}

function activeUser(store: InMemoryAuthenticationStore, uuid: string, telegramId = "100"): void {
  store.putUser({ minecraftUuid: uuid, username: `user_${uuid.slice(0, 6)}`, telegramId, status: "ACTIVE" })
}

const UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
const SERVER = "game-1"

describe("backend outage posture", () => {
  test("auth requests during outage are queued, never approved automatically", async () => {
    const h = harness()
    activeUser(h.store, UUID)
    h.deadServers.add(SERVER)

    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "bejiihiu", serverId: SERVER }, 1_000)

    // nothing reached the plugin: no prompt was even possible to approve
    expect(h.pushes.find((p) => p.type === "AUTH_REQUEST")).toBeUndefined()
    const session = await h.store.findOpenSession(UUID)
    expect(session?.status).toBe("WAITING_AUTH")

    // a full outage cycle of sweeps never auto-approves and never double-sends
    for (let t = 2_000; t <= 6_000; t += 2_000) {
      await h.service.sweep(t)
    }
    expect(h.pushes.filter((p) => p.type === "AUTH_APPROVED")).toHaveLength(0)
    expect(h.pushes).toHaveLength(0)
    expect((await h.store.findOpenSession(UUID))?.status).toBe("WAITING_AUTH")

    // the link returns: the parked request flushes exactly once
    h.deadServers.delete(SERVER)
    await h.service.sweep(7_000)
    expect(h.pushes.filter((p) => p.type === "AUTH_REQUEST")).toHaveLength(1)
    await h.service.sweep(8_000)
    expect(h.pushes.filter((p) => p.type === "AUTH_REQUEST")).toHaveLength(1)
  })

  test("existing authenticated sessions are untouched by an outage", async () => {
    const h = harness()
    activeUser(h.store, UUID)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "bejiihiu", serverId: SERVER }, 1_000)
    const prompt = h.prompts.at(-1)
    if (!prompt) throw new Error("no prompt delivered")
    const outcome = await h.service.approve(prompt.token, "100", 2_000)
    expect(outcome.kind).toBe("APPROVED")
    const before = await h.store.findOpenSession(UUID)
    expect(before?.status).toBe("AUTHENTICATED")

    // link dies: sweeps must not revoke, expire or downgrade the session
    h.deadServers.add(SERVER)
    for (let t = 3_000; t <= 20_000; t += 3_000) {
      await h.service.sweep(t)
    }
    const after = await h.store.findOpenSession(UUID)
    expect(after?.status).toBe("AUTHENTICATED")
    expect(after?.sessionId).toBe(before?.sessionId)
    expect(h.pushes.filter((p) => p.type === "AUTH_EXPIRED" || p.type === "AUTH_REJECTED")).toHaveLength(0)
  })

  test("guest sessions keep being tracked while the link is down", async () => {
    const h = harness()
    h.deadServers.add(SERVER)

    await h.service.handleGuestStarted({ minecraftUuid: UUID, sessionId: "guest-abc123" }, SERVER, 1_000)
    expect((await h.store.findOpenSession(UUID))?.status).toBe("GUEST")

    await h.service.handleGuestEnded({ minecraftUuid: UUID, sessionId: "guest-abc123", reason: "LOGOUT" })
    expect(await h.store.findOpenSession(UUID)).toBeNull()
  })

  test("GUEST_STARTED redelivery is exactly-once per session id", async () => {
    const h = harness()
    await h.service.handleGuestStarted({ minecraftUuid: UUID, sessionId: "guest-abc123" }, SERVER, 1_000)
    // the plugin reconnects and replays the same durable frame
    await h.service.handleGuestStarted({ minecraftUuid: UUID, sessionId: "guest-abc123" }, SERVER, 2_000)

    const session = await h.store.findOpenSession(UUID)
    expect(session?.sessionId).toBe("guest-abc123")
    expect(h.store.sessionCount).toBe(1)

    // even a fresh service (backend restart, openSessions mirror gone) with
    // the same store refuses to duplicate the session
    const rebooted = new AuthenticationService({
      store: h.store,
      config: BASE_CONFIG,
      logger: silentLogger(),
      push: async () => true,
      sendPrompt: null,
    })
    await rebooted.handleGuestStarted({ minecraftUuid: UUID, sessionId: "guest-abc123" }, SERVER, 3_000)
    expect(h.store.sessionCount).toBe(1)
  })

  test("a different session id never overrides an existing binding", async () => {
    const h = harness()
    activeUser(h.store, UUID)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "bejiihiu", serverId: SERVER }, 1_000)
    const waiting = await h.store.findOpenSession(UUID)

    await h.service.handleGuestStarted({ minecraftUuid: UUID, sessionId: "guest-abc123" }, SERVER, 2_000)

    const after = await h.store.findOpenSession(UUID)
    expect(after?.sessionId).toBe(waiting?.sessionId)
    expect(after?.status).toBe("WAITING_AUTH")
  })

  test("pushes parked past the give-up window are dropped, the next join re-syncs", async () => {
    const h = harness()
    activeUser(h.store, UUID)
    h.deadServers.add(SERVER)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "bejiihiu", serverId: SERVER }, 1_000)

    // eleven minutes of outage: the parked push is dead weight by then
    h.deadServers.delete(SERVER)
    await h.service.sweep(1_000 + 11 * 60_000)
    // the sweep expired the dead request and re-synced the plugin with
    // AUTH_EXPIRED; the parked AUTH_REQUEST itself never went out
    expect(h.pushes.filter((p) => p.type === "AUTH_REQUEST")).toHaveLength(0)
    expect(h.pushes.filter((p) => p.type === "AUTH_EXPIRED")).toHaveLength(1)

    // the compensating re-sync: the player's next join starts a fresh flow
    const decision = await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "bejiihiu", serverId: SERVER }, 1_000 + 11 * 60_000 + 1_000)
    expect(decision.kind).toBe("AUTH_FLOW_STARTED")
    expect(h.pushes.filter((p) => p.type === "AUTH_REQUEST")).toHaveLength(1)
  })
})
