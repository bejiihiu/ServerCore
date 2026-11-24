import { describe, expect, test } from "bun:test"
import type { Logger } from "../src/logging/logger.js"
import pino from "pino"
import { AuthenticationService, type BackendAuthType, type PromptRequest } from "../src/modules/authentication/auth-service.js"
import { InMemoryAuthenticationStore } from "../src/modules/authentication/memory-store.js"
import { sha256Hex } from "../src/utils/crypto.js"

function silentLogger(): Logger {
  return pino({ level: "silent" })
}

type PushRecord = { serverId: string; type: BackendAuthType; payload: Record<string, unknown> }

type Harness = {
  service: AuthenticationService
  store: InMemoryAuthenticationStore
  pushes: PushRecord[]
  prompts: PromptRequest[]
  botOnline: { value: boolean }
  /** server ids whose gateway link is currently down (mutable per test) */
  deadServers: Set<string>
  flushPrompts(): Promise<void>
}

const BASE_CONFIG = {
  requestTtlSeconds: 75,
  serverName: "TestCore",
  rateLimitWindowMs: 300_000,
  maxRequestsPerPlayer: 5,
  maxRequestsPerTelegram: 10,
  maxApprovalsPerTelegram: 30,
}

function harness(overrides: Partial<typeof BASE_CONFIG> = {}, botOnline = true): Harness {
  const store = new InMemoryAuthenticationStore()
  const pushes: PushRecord[] = []
  const prompts: PromptRequest[] = []
  const botState = { value: botOnline }
  const deadServers = new Set<string>()
  const service = new AuthenticationService({
    store,
    config: { ...BASE_CONFIG, ...overrides },
    logger: silentLogger(),
    push: async (serverId, type, payload) => {
      // the link being down means nothing can be delivered right now
      if (deadServers.has(serverId)) return false
      pushes.push({ serverId, type, payload: payload as Record<string, unknown> })
      return true
    },
    sendPrompt: async (request) => {
      if (!botState.value) return false
      prompts.push(request)
      return true
    },
  })
  return {
    service,
    store,
    pushes,
    prompts,
    botOnline: botState,
    deadServers,
    async flushPrompts() {
      await service.sweep(Date.now())
    },
  }
}

/** seeds an ACTIVE account with a linked telegram id */
function activeUser(store: InMemoryAuthenticationStore, uuid: string, telegramId = "100"): void {
  store.putUser({ minecraftUuid: uuid, username: `user_${uuid.slice(0, 6)}`, telegramId, status: "ACTIVE" })
}

const UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
const SERVER = "game-1"

describe("session lifecycle", () => {
  test("join of an active account creates WAITING_AUTH session and pushes AUTH_REQUEST", async () => {
    const h = harness()
    activeUser(h.store, UUID)
    const decision = await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "bejiihiu", serverId: SERVER }, 1_000)
    expect(decision.kind).toBe("AUTH_FLOW_STARTED")

    const session = await h.store.findOpenSession(UUID)
    expect(session?.status).toBe("WAITING_AUTH")
    expect(session?.sessionId.startsWith("auth-")).toBe(true)

    const request = h.pushes.find((p) => p.type === "AUTH_REQUEST")
    expect(request?.serverId).toBe(SERVER)
    expect(request?.payload.sessionId).toBe(session?.sessionId)
  })

  test("join of an unknown account stays guest and never touches the auth flow", async () => {
    const h = harness()
    const decision = await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "stranger", serverId: SERVER }, 1_000)
    expect(decision).toEqual({ kind: "GUEST_ONLY", reason: "NOT_ACTIVE" })
    expect(h.pushes.find((p) => p.type === "AUTH_REQUEST")).toBeUndefined()
  })

  test("a non-ACTIVE account (voting, banned...) is guest only", async () => {
    const h = harness()
    h.store.putUser({ minecraftUuid: UUID, username: "voter", telegramId: "100", status: "VOTING" })
    const decision = await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "voter", serverId: SERVER }, 1_000)
    expect(decision).toEqual({ kind: "GUEST_ONLY", reason: "NOT_ACTIVE" })
  })

  test("PLAYER_LEFT closes the session and cancels pending requests", async () => {
    const h = harness()
    activeUser(h.store, UUID)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "bejiihiu", serverId: SERVER }, 1_000)
    await h.service.handlePlayerLeft(UUID, 2_000)

    expect(await h.store.findOpenSession(UUID)).toBeNull()
    const pending = await h.store.findPendingUndelivered()
    expect(pending).toHaveLength(0)
  })

  test("join after a lost PLAYER_LEFT starts a fresh session (idempotent reconnect)", async () => {
    const h = harness()
    activeUser(h.store, UUID)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "a", serverId: SERVER }, 1_000)
    const first = await h.store.findOpenSession(UUID)
    // reconnect without a clean PLAYER_LEFT (ws blip): join wins
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "a", serverId: SERVER }, 5_000)
    const second = await h.store.findOpenSession(UUID)
    expect(second?.sessionId).not.toBe(first?.sessionId)
    expect(second?.status).toBe("WAITING_AUTH")
  })
})

describe("section 9.1 protection matrix", () => {
  async function startedRequest(h: Harness, uuid = UUID): Promise<{ token: string; sessionId: string }> {
    activeUser(h.store, uuid)
    await h.service.handlePlayerJoined({ minecraftUuid: uuid, username: "player", serverId: SERVER }, 1_000)
    // prompt delivery happens in deliver(); pull the raw token from the prompt call
    const prompt = h.prompts.at(-1)
    if (!prompt) throw new Error("no prompt delivered")
    const session = await h.store.findOpenSession(uuid)
    return { token: prompt.token, sessionId: session?.sessionId ?? "" }
  }

  test("approve with the correct token flips the session to AUTHENTICATED and pushes AUTH_APPROVED", async () => {
    const h = harness()
    const { token, sessionId } = await startedRequest(h)
    const outcome = await h.service.approve(token, "100", 2_000)
    expect(outcome.kind).toBe("APPROVED")
    const session = await h.store.findOpenSession(UUID)
    expect(session?.status).toBe("AUTHENTICATED")
    const push = h.pushes.find((p) => p.type === "AUTH_APPROVED")
    expect(push?.payload).toMatchObject({ minecraftUuid: UUID, sessionId })
  })

  test("one-time tokens: a second approve of the same token is rejected", async () => {
    const h = harness()
    const { token } = await startedRequest(h)
    await h.service.approve(token, "100", 2_000)
    const replay = await h.service.approve(token, "100", 3_000)
    expect(replay.kind).toBe("ALREADY_PROCESSED")
  })

  test("replay protection: reject after approve does not resurrect or double-consume", async () => {
    const h = harness()
    const { token } = await startedRequest(h)
    await h.service.approve(token, "100", 2_000)
    const reject = await h.service.reject(token, "100", 3_000)
    expect(reject.kind).toBe("ALREADY_PROCESSED")
    expect(h.pushes.filter((p) => p.type === "AUTH_REJECTED")).toHaveLength(0)
  })

  test("token expiry: approving past expiresAt fails and marks EXPIRED", async () => {
    const h = harness()
    const { token } = await startedRequest(h)
    const late = 1_000 + BASE_CONFIG.requestTtlSeconds * 1000 + 1
    const outcome = await h.service.approve(token, "100", late)
    expect(outcome.kind).toBe("EXPIRED")
    const session = await h.store.findOpenSession(UUID)
    expect(session?.status).toBe("WAITING_AUTH") // approval never landed
  })

  test("hash storage: only sha256 hashes are persisted, never raw tokens", async () => {
    const h = harness()
    const { token } = await startedRequest(h)
    // walk every stored request through json: the raw token must not exist anywhere
    const request = await h.store.findAuthRequestByTokenHash(sha256Hex(token))
    expect(request).not.toBeNull()
    const serialized = JSON.stringify(request)
    expect(serialized).not.toContain(token)
  })

  test("cancel-on-new: a rejoin cancels the previous pending request", async () => {
    const h = harness()
    const { token: firstToken } = await startedRequest(h)
    // second join: new request, the first one must die
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "player", serverId: SERVER }, 10_000)
    const outcome = await h.service.approve(firstToken, "100", 11_000)
    // the request still exists but sits in CANCELLED: unusable either way
    expect(outcome.kind).toBe("ALREADY_PROCESSED")
    const session = await h.store.findOpenSession(UUID)
    expect(session?.status).toBe("WAITING_AUTH")
  })

  test("session binding: a token from an ended session cannot approve a new one", async () => {
    const h = harness()
    const { token, sessionId: firstSession } = await startedRequest(h)
    // player leaves (request cancelled) then rejoins: new session, new request
    await h.service.handlePlayerLeft(UUID, 2_000)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "player", serverId: SERVER }, 3_000)
    const outcome = await h.service.approve(token, "100", 4_000)
    expect(outcome.kind).toBe("ALREADY_PROCESSED")
    expect(firstSession).not.toBe((await h.store.findOpenSession(UUID))?.sessionId)
  })

  test("no old-session approval: approval against a replaced session fails", async () => {
    const h = harness()
    const { token } = await startedRequest(h)
    // simulate the plugin reconnecting: a fresh session replaces the old one
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "player", serverId: SERVER }, 10_000)
    const outcome = await h.service.approve(token, "100", 11_000)
    expect(outcome.kind).toBe("ALREADY_PROCESSED")
  })

  test("foreign telegram id cannot approve someone else's request", async () => {
    const h = harness()
    const { token } = await startedRequest(h)
    const outcome = await h.service.approve(token, "999", 2_000)
    expect(outcome.kind).toBe("NOT_YOURS")
    const session = await h.store.findOpenSession(UUID)
    expect(session?.status).toBe("WAITING_AUTH")
  })

  test("per-player request rate limiting (sliding window)", async () => {
    const h = harness({ maxRequestsPerPlayer: 2 })
    activeUser(h.store, UUID)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 1_000)
    await h.service.handlePlayerLeft(UUID, 1_100)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 2_000)
    await h.service.handlePlayerLeft(UUID, 2_100)
    const decision = await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 3_000)
    expect(decision).toEqual({ kind: "GUEST_ONLY", reason: "RATE_LIMITED" })
    // once the window slides past, the budget is back
    const later = await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 301_000)
    expect(later.kind).toBe("AUTH_FLOW_STARTED")
  })

  test("per-telegram-id request budget is enforced separately", async () => {
    const h = harness({ maxRequestsPerTelegram: 1, maxRequestsPerPlayer: 10 })
    activeUser(h.store, UUID, "777")
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 1_000)
    await h.service.handlePlayerLeft(UUID, 1_100)
    const decision = await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 2_000)
    expect(decision).toEqual({ kind: "GUEST_ONLY", reason: "RATE_LIMITED" })
  })

  test("approval rate limiting caps telegram-side presses", async () => {
    const h = harness({ maxApprovalsPerTelegram: 1 })
    activeUser(h.store, UUID, "777")
    // two fresh requests by re-joining, each delivers a fresh prompt token
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 1_000)
    await h.service.handlePlayerLeft(UUID, 1_100)
    // bypass the per-telegram join limiter by rewinding the window config
    const second = await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 1_200)
    expect(second.kind).toBe("AUTH_FLOW_STARTED")
    const liveToken = h.prompts.at(-1)?.token ?? ""
    const first = await h.service.approve(liveToken, "777", 2_000)
    expect(first.kind).toBe("APPROVED")
    // the very next press is over budget, whatever token it carries
    const blocked = await h.service.reject(liveToken, "777", 2_100)
    expect(blocked.kind).toBe("RATE_LIMITED")
  })

  test("sweeper expires dead requests and flips stale sessions to GUEST", async () => {
    const h = harness()
    activeUser(h.store, UUID)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 1_000)
    const expiry = 1_000 + BASE_CONFIG.requestTtlSeconds * 1000 + 1
    await h.service.sweep(expiry)

    const push = h.pushes.find((p) => p.type === "AUTH_EXPIRED")
    expect(push).toBeDefined()
    const session = await h.store.findOpenSession(UUID)
    expect(session?.status).toBe("GUEST")
  })

  test("reject keeps the player in guest mode and pushes AUTH_REJECTED", async () => {
    const h = harness()
    const { token, sessionId } = await startedRequest(h)
    const outcome = await h.service.reject(token, "100", 2_000)
    expect(outcome.kind).toBe("REJECTED")
    const session = await h.store.findOpenSession(UUID)
    expect(session?.status).toBe("GUEST")
    const push = h.pushes.find((p) => p.type === "AUTH_REJECTED")
    expect(push?.payload.sessionId).toBe(sessionId)
    expect(push?.payload.reason).toBe("DECLINED")
  })
})

describe("fail-closed posture", () => {
  test("telegram down: no approval path exists, request stays pending then expires", async () => {
    const h = harness({}, false)
    activeUser(h.store, UUID)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 1_000)
    // telegram never received anything: prompts list is empty
    expect(h.prompts).toHaveLength(0)
    // nothing was approved automatically
    const session = await h.store.findOpenSession(UUID)
    expect(session?.status).toBe("WAITING_AUTH")
    // after the window the sweeper expires it and the player stays guest
    await h.service.sweep(1_000 + BASE_CONFIG.requestTtlSeconds * 1000 + 1)
    expect((await h.store.findOpenSession(UUID))?.status).toBe("GUEST")
  })

  test("no bot configured: same posture, expiry instead of approval", async () => {
    const store = new InMemoryAuthenticationStore()
    const service = new AuthenticationService({
      store,
      config: BASE_CONFIG,
      logger: silentLogger(),
      push: async () => true,
      sendPrompt: null,
    })
    store.putUser({ minecraftUuid: UUID, username: "p", telegramId: "1", status: "ACTIVE" })
    await service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 1_000)
    await service.sweep(1_000 + BASE_CONFIG.requestTtlSeconds * 1000 + 1)
    expect((await store.findOpenSession(UUID))?.status).toBe("GUEST")
  })

  test("game server link down: pushes park in the outbox and flush on reconnect", async () => {
    const h = harness()
    activeUser(h.store, UUID)
    // first join against a dead server link: push parked
    h.deadServers.add(SERVER)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 1_000)
    expect(h.pushes.find((p) => p.type === "AUTH_REQUEST")).toBeUndefined()
    // link returns: sweeper flushes the queued push
    h.deadServers.delete(SERVER)
    await h.service.sweep(2_000)
    const push = h.pushes.find((p) => p.type === "AUTH_REQUEST")
    expect(push?.serverId).toBe(SERVER)
  })

  test("undelivered prompts retry on the next sweep when telegram recovers", async () => {
    const h = harness({}, false)
    activeUser(h.store, UUID)
    await h.service.handlePlayerJoined({ minecraftUuid: UUID, username: "p", serverId: SERVER }, 1_000)
    expect(h.prompts).toHaveLength(0)
    h.botOnline.value = true
    await h.service.sweep(1_500)
    expect(h.prompts).toHaveLength(1)
    // and a second sweep does not double-send
    await h.service.sweep(1_600)
    expect(h.prompts).toHaveLength(1)
  })
})

describe("telegram ui", () => {
  test("approve callback data carries the token, parsing round-trips", async () => {
    const { parseAuthCallbackData, approveCallbackData, rejectCallbackData } = await import(
      "../src/modules/authentication/telegram-prompt.js"
    )
    const token = "AbCdEfGhIjKlMnOp"
    const approved = parseAuthCallbackData(approveCallbackData(token))
    expect(approved).toEqual({ kind: "APPROVE", token })
    const rejected = parseAuthCallbackData(rejectCallbackData(token))
    expect(rejected).toEqual({ kind: "REJECT", token })
    expect(parseAuthCallbackData("menu:back")).toBeNull()
    expect(parseAuthCallbackData(undefined)).toBeNull()
  })

  test("prompt screen carries styled buttons and the expandable blockquote", async () => {
    const { promptScreen } = await import("../src/modules/authentication/telegram-prompt.js")
    const { createTranslator } = await import("../src/bot/ui/text/index.js")
    const { i18n } = await import("../src/i18n/instance.js")
    const t = createTranslator(i18n)
    const screen = promptScreen(
      { id: "r1", minecraftUuid: UUID, telegramId: "1", username: "bejiihiu", token: "AbCdEfGhIjKlMnOp", expiresInSeconds: 75 },
      "Survival S2",
      t,
    )
    expect(screen.text).toContain("<b>")
    expect(screen.text).toContain("<blockquote expandable>")
    expect(screen.text).toContain("75")
    const row = screen.markup?.inline_keyboard[0] ?? []
    expect(row).toHaveLength(2)
    expect(row[0]).toMatchObject({ style: "success", callback_data: "auth:approve:AbCdEfGhIjKlMnOp" })
    expect(row[1]).toMatchObject({ style: "danger", callback_data: "auth:reject:AbCdEfGhIjKlMnOp" })
  })
})

describe("sweep reentrancy", () => {
  test("a slow sweep makes the next tick a no-op instead of overlapping", async () => {
    const h = harness()
    let calls = 0
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const realFind = h.store.findPendingExpired.bind(h.store)
    h.store.findPendingExpired = async (now: number) => {
      calls += 1
      await gate
      return realFind(now)
    }

    const first = h.service.sweep(1_000)
    // the timer fires mid-pass: this call must return without touching the store
    await h.service.sweep(1_000)
    expect(calls).toBe(1)

    release()
    await first
    expect(calls).toBe(1)

    // the guard is released: the next tick runs normally
    await h.service.sweep(1_100)
    expect(calls).toBe(2)
  })

  test("a throwing sweep releases the guard", async () => {
    const h = harness()
    let calls = 0
    h.store.findPendingExpired = async () => {
      calls += 1
      throw new Error("mongo gone")
    }
    await h.service.sweep(1_000).catch(() => undefined)
    await h.service.sweep(1_100).catch(() => undefined)
    expect(calls).toBe(2)
  })
})
