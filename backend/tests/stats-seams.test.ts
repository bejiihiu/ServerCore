import { describe, expect, test } from "bun:test"
import pino from "pino"
import type { Logger } from "../src/logging/logger.js"
import { HandlerRegistry } from "../src/websocket/handlers/registry.js"
import { ServerDirectory } from "../src/websocket/handlers/server-lifecycle.js"
import { registerAllHandlers } from "../src/websocket/handlers/wiring.js"
import type { ServerConnection } from "../src/websocket/connection.js"
import type { Envelope, MessageType } from "../src/websocket/protocol/message-types.js"
import type { GatewayOwnershipVerifier } from "../src/modules/account/session-probe.js"
import type { SpecAuditService } from "../src/modules/audit/spec-audit-service.js"
import { AuthenticationService, type BackendAuthType, type PromptRequest } from "../src/modules/authentication/auth-service.js"
import { InMemoryAuthenticationStore } from "../src/modules/authentication/memory-store.js"
import { OpsNotifier, eventForState, type OpsEvent } from "../src/services/ops-notifier.js"
import { NotificationService, type MessageSender } from "../src/services/notification-service.js"
import { SessionCounters, StatsService } from "../src/services/runtime-stats.js"
import type { StatsCountsSource } from "../src/services/stats-counts.js"
import { LocaleService } from "../src/services/locale-service.js"

function logger(): Logger {
  return pino({ level: "silent" })
}

const UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
const SERVER = "game-1"

const BASE_CONFIG = {
  requestTtlSeconds: 75,
  serverName: "TestCore",
  rateLimitWindowMs: 300_000,
  maxRequestsPerPlayer: 5,
  maxRequestsPerTelegram: 10,
  maxApprovalsPerTelegram: 30,
}

// ---- fakes ----

function conn(serverId: string): ServerConnection {
  return { serverId } as unknown as ServerConnection
}

let msgSeq = 0
function env(type: MessageType, payload: Record<string, unknown>): Envelope {
  msgSeq += 1
  return { id: `msg_${String(msgSeq).padStart(8, "0")}`, type, timestamp: 1_000, payload } as Envelope
}

function stubVerifier(): GatewayOwnershipVerifier {
  return { onProbeAnswer() {} } as unknown as GatewayOwnershipVerifier
}

function stubSpecAudit(): SpecAuditService {
  return { async record() {} } as unknown as SpecAuditService
}

function recordingNotifications(): { notifications: NotificationService; sent: { chatId: string; text: string }[] } {
  const sent: { chatId: string; text: string }[] = []
  const sender: MessageSender = {
    async send(chatId: string, text: string) {
      sent.push({ chatId, text })
    },
  }
  const notifications = new NotificationService({
    sender,
    recipients: { async listRecipients() { return [] } },
    logger: logger(),
  })
  return { notifications, sent }
}

function fakeCounts(initial: { openVotings: number; activeUsers: number } = { openVotings: 1, activeUsers: 1 }) {
  const state = { ...initial, fail: false }
  const source = {
    async openVotings() {
      if (state.fail) throw new Error("mongo down")
      return state.openVotings
    },
    async activeUsers() {
      if (state.fail) throw new Error("mongo down")
      return state.activeUsers
    },
    state,
  }
  return source as StatsCountsSource & { state: typeof state }
}

// ---- the seam under test: production wiring shape, in-memory everything ----

function buildScenario() {
  const registry = new HandlerRegistry(logger())
  const directory = new ServerDirectory()

  const store = new InMemoryAuthenticationStore()
  store.putUser({ minecraftUuid: UUID, username: "bejiihiu", telegramId: "100", status: "ACTIVE" })

  const authPushes: { serverId: string; type: BackendAuthType; payload: Record<string, unknown> }[] = []
  const prompts: PromptRequest[] = []
  // the game-server link starts down, tests flip it to simulate a reconnect
  let linkUp = false
  let now = 10_000

  const auth = new AuthenticationService({
    store,
    config: BASE_CONFIG,
    logger: logger(),
    push: async (serverId, type, payload) => {
      if (!linkUp) return false
      authPushes.push({ serverId, type, payload: payload as Record<string, unknown> })
      return true
    },
    sendPrompt: async (request) => {
      prompts.push(request)
      return true
    },
  })

  const { notifications, sent } = recordingNotifications()
  const ops = new OpsNotifier({ notifications, adminChatIds: ["777"], locale: "en", logger: logger() })
  const announcements: { event: OpsEvent; telegram: boolean }[] = []

  // lifecycle hooks are fire-and-forget in production; the test tracks the
  // promises so assertions run after the async flows settled
  const flights: Promise<unknown>[] = []
  const track = <T>(promise: Promise<T>): Promise<T> => {
    flights.push(promise)
    return promise
  }
  const settle = async (): Promise<void> => {
    await Promise.allSettled(flights)
    flights.length = 0
  }

  // hooks mirror app.ts: same fan-out, same suppression rule
  registerAllHandlers(registry, {
    directory,
    logger: logger(),
    verifier: stubVerifier(),
    specAudit: stubSpecAudit(),
    locale: new LocaleService({ logger: logger() }),
    hooks: {
      onStateChange: (serverId, state, _previous, options) => {
        const telegram = options?.telegramSuppressed === true ? false : true
        announcements.push({ event: eventForState(state), telegram })
        return track(ops.announce(serverId, eventForState(state), { telegram }))
      },
      onPlayerJoined: (event) => track(auth.handlePlayerJoined(event, now)),
      onPlayerLeft: (event) => track(auth.handlePlayerLeft(event.minecraftUuid, now)),
      onGuestStarted: (event, serverId) => track(auth.handleGuestStarted(event, serverId, now)),
      onGuestEnded: (event) => track(auth.handleGuestEnded(event)),
    },
  })

  const counters = new SessionCounters()
  const counts = fakeCounts()
  const stats = new StatsService({
    startedAt: now - 60_000,
    gateway: { stats: { running: true, connections: 1 }, connectedServers: [SERVER] },
    directory,
    counters,
    counts,
  })

  const dispatch = async (type: MessageType, payload: Record<string, unknown>): Promise<void> => {
    await registry.dispatch(env(type, payload), conn(SERVER))
    await settle()
  }
  // the sweeper interval in app.ts: refresh the /stats counters from the auth
  // module's live numbers on the same tick
  const sweep = async (): Promise<void> => {
    await auth.sweep(now)
    Object.assign(counters, auth.stats())
  }

  return { registry, directory, auth, store, authPushes, prompts, ops, sent, announcements, stats, counts, dispatch, sweep, link: () => (linkUp = true), now: () => (now += 1_000) }
}

describe("auth ↔ ops ↔ spec ↔ stats integration seams", () => {
  test("join → auth request → approve, the snapshot follows the real state", async () => {
    const s = buildScenario()

    // server boots: state lands in the directory, ops announces to telegram
    await s.dispatch("SERVER_STATUS", { onlinePlayers: 0, maxPlayers: 20, state: "STARTING" })
    await s.dispatch("SERVER_STATUS", { onlinePlayers: 0, maxPlayers: 20, state: "RUNNING", version: "1.21" })
    expect(s.directory.get(SERVER)?.state).toBe("RUNNING")
    expect(s.announcements.map((a) => a.event)).toEqual(["STARTING", "RUNNING"])
    expect(s.sent.length).toBe(2)

    // join with the game-server link down: the telegram prompt still goes
    // out, the AUTH_REQUEST parks in the backend outbox
    s.now()
    await s.dispatch("PLAYER_JOINED", { minecraftUuid: UUID, username: "bejiihiu" })
    expect(s.prompts).toHaveLength(1)
    expect(s.authPushes.filter((p) => p.type === "AUTH_REQUEST")).toHaveLength(0)

    // link comes back: the sweeper flushes the outbox — exactly one
    // AUTH_REQUEST, and a second sweep never re-sends it
    s.link()
    await s.sweep()
    expect(s.authPushes.filter((p) => p.type === "AUTH_REQUEST")).toHaveLength(1)
    await s.sweep()
    expect(s.authPushes.filter((p) => p.type === "AUTH_REQUEST")).toHaveLength(1)

    await s.sweep()
    const snap = await s.stats.snapshot()
    expect(snap.counters.pendingAuths).toBe(1)
    expect(snap.servers[0]?.state).toBe("RUNNING")
    expect(snap.admission).toEqual({ openVotings: 1 })
    expect(snap.accounts).toEqual({ activeUsers: 1 })

    // the admin approves with the exact token the prompt carried
    const outcome = await s.auth.approve(s.prompts[0]!.token, "100", s.now())
    expect(outcome.kind).toBe("APPROVED")
    expect(s.authPushes.filter((p) => p.type === "AUTH_APPROVED")).toHaveLength(1)

    await s.sweep()
    const after = await s.stats.snapshot()
    expect(after.counters.activeSessions).toBe(1)
    expect(after.counters.pendingAuths).toBe(0)
  })

  test("guest interplay + heartbeat spec counter reach the snapshot", async () => {
    const s = buildScenario()

    await s.dispatch("SERVER_STATUS", { onlinePlayers: 0, maxPlayers: 20, state: "RUNNING" })
    // an unknown player goes guest; the heartbeat carries the live spec
    // counter from the plugin's own registry
    s.now()
    await s.dispatch("PLAYER_JOINED", { minecraftUuid: "11111111-2222-4333-8444-555566667777", username: "stranger" })
    await s.dispatch("GUEST_STARTED", { minecraftUuid: "11111111-2222-4333-8444-555566667777", username: "stranger", sessionId: "guest-abc" })
    await s.dispatch("SERVER_HEARTBEAT", { onlinePlayers: 1, maxPlayers: 20, activeSpecSessions: 2 })

    await s.sweep()
    const snap = await s.stats.snapshot()
    expect(snap.counters.guestSessions).toBe(1)
    expect(snap.servers[0]?.activeSpecSessions).toBe(2)

    // guest session ends for real: the counter drops back
    await s.dispatch("GUEST_ENDED", { minecraftUuid: "11111111-2222-4333-8444-555566667777", sessionId: "guest-abc", reason: "DISCONNECTED" })
    await s.sweep()
    expect((await s.stats.snapshot()).counters.guestSessions).toBe(0)
  })

  test("telegramSuppressed on the status frame keeps the bot quiet for that event", async () => {
    const s = buildScenario()

    // flectonepulse owns announcements for this restart: no telegram send
    await s.dispatch("SERVER_STATUS", { onlinePlayers: 0, maxPlayers: 20, state: "RESTARTING", telegramSuppressed: true })
    expect(s.announcements).toEqual([{ event: "RESTARTING", telegram: false }])
    expect(s.sent).toHaveLength(0)

    // the next unsuppressed flip announces normally
    await s.dispatch("SERVER_STATUS", { onlinePlayers: 0, maxPlayers: 20, state: "RUNNING" })
    expect(s.sent).toHaveLength(1)
  })

  test("a failed mongo count drops its section instead of faking a number", async () => {
    const s = buildScenario()
    s.counts.state.fail = true

    const snap = await s.stats.snapshot()
    expect(snap.admission).toBeNull()
    expect(snap.accounts).toBeNull()
    expect(snap.counters.pendingAuths).toBe(0)
  })

  test("reconnect after a crash alert announces the all-clear to admins and the recovery line to users", async () => {
    const { notifications, sent } = recordingNotifications()
    const ops = new OpsNotifier({ notifications, adminChatIds: ["777"], locale: "en", logger: logger() })

    await ops.serverBack("game-1")
    expect(sent).toEqual([
      { chatId: "777", text: "Server game-1 is back online" },
      { chatId: "777", text: "The server is up" },
    ])
  })
})
