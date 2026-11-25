import { describe, expect, test } from "bun:test"
import type { Logger } from "../src/logging/logger.js"
import { loadConfig } from "../src/config/env.js"
import { buildHttpServer } from "../src/http/server.js"
import { connectWithBackoff, databaseReady } from "../src/database/connection.js"
import { createGateway } from "../src/websocket/gateway.js"
import { HandlerRegistry, silentLogger } from "../src/websocket/handlers/registry.js"
import { ServerConnection } from "../src/websocket/connection.js"
import { registerSpecHandlers } from "../src/websocket/handlers/spec-handlers.js"
import { SpecAuditService } from "../src/modules/audit/spec-audit-service.js"
import { verifyWireMessage } from "../src/websocket/protocol/signing.js"
import {
  AuthenticationService,
  type AuthModuleConfig,
  type PromptRequest,
} from "../src/modules/authentication/auth-service.js"
import { InMemoryAuthenticationStore } from "../src/modules/authentication/memory-store.js"
import type {
  AuthenticationStore,
  AuthRequestRecord,
  GuestSnapshotRecord,
  SessionRecord,
  SessionStatus,
  TerminalAuthStatus,
  UserRecord,
} from "../src/modules/authentication/persistence.js"
import { RetentionService } from "../src/modules/retention/retention-service.js"
import {
  WS_NOW,
  WS_SECRET,
  connectionOptions,
  fakeSocket,
  sentFrames,
  signedFrame,
  silentPino,
} from "./helpers/ws-harness.js"

const UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
const OTHER_UUID = "1f4c9a3e-0b2d-4c5a-9e8f-7d6c5b4a3928"
const SERVER = "game-1"

/** what the driver throws while atlas is unreachable */
const mongoDown = (): Error => new Error("MongoServerSelectionError: connection refused")

/** logger that keeps the warn lines; the backoff schedule is what matters */
function recordingLogger(): { logger: Logger; warnings: string[] } {
  const warnings: string[] = []
  const logger = {
    info: () => undefined,
    warn: (_obj: unknown, msg: string) => {
      warnings.push(msg)
    },
    error: () => undefined,
    debug: () => undefined,
  } as unknown as Logger
  return { logger, warnings }
}

// ---------------------------------------------------------------------------
// storage seam: the in-memory twin, but mongo can "go away" or stall mid-call
// ---------------------------------------------------------------------------

const AUTH_CONFIG: AuthModuleConfig = {
  requestTtlSeconds: 75,
  serverName: "TestCore",
  rateLimitWindowMs: 300_000,
  maxRequestsPerPlayer: 5,
  maxRequestsPerTelegram: 10,
  maxApprovalsPerTelegram: 30,
}

/**
 * every call goes through run(): optional stall (write-timeout simulation),
 * then a hard failure while `down`, then the real in-memory work. call counts
 * make "did the second sweep even touch the db?" observable.
 */
class ChaosAuthStore implements AuthenticationStore {
  readonly inner = new InMemoryAuthenticationStore()
  down = false
  stalled: { method: string; promise: Promise<void> } | null = null
  readonly calls = new Map<string, number>()

  private async run<T>(method: string, op: () => Promise<T>): Promise<T> {
    this.calls.set(method, (this.calls.get(method) ?? 0) + 1)
    if (this.stalled?.method === method) await this.stalled.promise
    if (this.down) throw mongoDown()
    return op()
  }

  findOpenSession(uuid: string): Promise<SessionRecord | null> {
    return this.run("findOpenSession", () => this.inner.findOpenSession(uuid))
  }
  createSession(session: SessionRecord): Promise<SessionRecord> {
    return this.run("createSession", () => this.inner.createSession(session))
  }
  updateOpenSessionStatus(uuid: string, status: SessionStatus): Promise<SessionRecord | null> {
    return this.run("updateOpenSessionStatus", () => this.inner.updateOpenSessionStatus(uuid, status))
  }
  closeOpenSession(uuid: string): Promise<SessionRecord | null> {
    return this.run("closeOpenSession", () => this.inner.closeOpenSession(uuid))
  }
  saveGuestSnapshot(snapshot: GuestSnapshotRecord): Promise<void> {
    return this.run("saveGuestSnapshot", () => this.inner.saveGuestSnapshot(snapshot))
  }
  deleteGuestSnapshot(sessionId: string): Promise<number> {
    return this.run("deleteGuestSnapshot", () => this.inner.deleteGuestSnapshot(sessionId))
  }
  insertAuthRequest(request: AuthRequestRecord): Promise<void> {
    return this.run("insertAuthRequest", () => this.inner.insertAuthRequest(request))
  }
  findAuthRequestByTokenHash(hash: string): Promise<AuthRequestRecord | null> {
    return this.run("findAuthRequestByTokenHash", () => this.inner.findAuthRequestByTokenHash(hash))
  }
  claimPendingAuthRequest(
    id: string,
    status: TerminalAuthStatus,
    now: number,
  ): Promise<AuthRequestRecord | null> {
    return this.run("claimPendingAuthRequest", () => this.inner.claimPendingAuthRequest(id, status, now))
  }
  markAuthRequestDelivered(id: string, deliveredAt: number): Promise<void> {
    return this.run("markAuthRequestDelivered", () => this.inner.markAuthRequestDelivered(id, deliveredAt))
  }
  cancelPendingForPlayer(uuid: string, now: number): Promise<number> {
    return this.run("cancelPendingForPlayer", () => this.inner.cancelPendingForPlayer(uuid, now))
  }
  findPendingExpired(now: number): Promise<AuthRequestRecord[]> {
    return this.run("findPendingExpired", () => this.inner.findPendingExpired(now))
  }
  findPendingUndelivered(): Promise<AuthRequestRecord[]> {
    return this.run("findPendingUndelivered", () => this.inner.findPendingUndelivered())
  }
  findUserByUuid(uuid: string): Promise<UserRecord | null> {
    return this.run("findUserByUuid", () => this.inner.findUserByUuid(uuid))
  }
}

type PushRecord = { serverId: string; type: string }

function authHarness(): {
  store: ChaosAuthStore
  service: AuthenticationService
  pushes: PushRecord[]
  prompts: PromptRequest[]
} {
  const store = new ChaosAuthStore()
  const pushes: PushRecord[] = []
  const prompts: PromptRequest[] = []
  const service = new AuthenticationService({
    store,
    config: AUTH_CONFIG,
    logger: silentPino(),
    push: async (serverId, type) => {
      pushes.push({ serverId, type })
      return true
    },
    sendPrompt: async (request) => {
      prompts.push(request)
      return true
    },
  })
  return { store, service, pushes, prompts }
}

function activeUser(store: InMemoryAuthenticationStore): void {
  store.putUser({ minecraftUuid: UUID, username: "bejiihiu", telegramId: "100", status: "ACTIVE" })
}

const JOIN = { minecraftUuid: UUID, username: "bejiihiu", serverId: SERVER }

// ---------------------------------------------------------------------------

describe("boot with mongodb unreachable", () => {
  test("databaseReady() is false until a connection actually lands", () => {
    // the health seam reads this directly; mongoose is never connected here
    expect(databaseReady()).toBe(false)
  })

  test("/health answers 503 degraded while the database is down, 200 once it recovers", async () => {
    let dbConnected = false
    const app = await buildHttpServer({
      config: loadConfig({}),
      logger: silentPino(),
      health: () => ({
        uptimeSeconds: 1,
        mongoConnected: dbConnected,
        gateway: { running: true, connections: 0 },
        connectedServers: [],
      }),
    })

    const degraded = await app.inject({ method: "GET", url: "/health" })
    expect(degraded.statusCode).toBe(503)
    expect(degraded.json()).toMatchObject({ status: "degraded", mongoConnected: false })

    // the connect loop landed in the background: same process, same listeners
    dbConnected = true
    const healthy = await app.inject({ method: "GET", url: "/health" })
    expect(healthy.statusCode).toBe(200)
    expect(healthy.json()).toMatchObject({ status: "ok", mongoConnected: true })

    await app.close()
  })

  test("the retry loop backs off exponentially, caps the delay and never rejects", async () => {
    const delays: number[] = []
    const { logger, warnings } = recordingLogger()
    let attempts = 0

    const outcome = await connectWithBackoff({
      attempt: async () => {
        attempts += 1
        if (attempts < 3) throw mongoDown()
      },
      shouldRetry: () => true,
      logger,
      sleep: async (ms) => {
        delays.push(ms)
      },
    })

    expect(outcome).toEqual({ attempts: 3, connected: true })
    expect(delays).toEqual([1_000, 2_000])
    expect(warnings).toHaveLength(2)
  })

  test("the delay tops out at 30s instead of doubling forever", async () => {
    const delays: number[] = []
    const { logger } = recordingLogger()
    let attempts = 0

    const outcome = await connectWithBackoff({
      attempt: async () => {
        attempts += 1
        throw mongoDown()
      },
      shouldRetry: () => attempts < 8,
      logger,
      sleep: async (ms) => {
        delays.push(ms)
      },
    })

    expect(outcome.connected).toBe(false)
    expect(outcome.attempts).toBe(8)
    expect(delays.slice(0, 5)).toEqual([1_000, 2_000, 4_000, 8_000, 16_000])
    expect(Math.max(...delays)).toBe(30_000)
  })

  test("shutdown stops the loop instead of holding the process open", async () => {
    const { logger } = recordingLogger()

    const immediate = await connectWithBackoff({
      attempt: async () => {
        throw new Error("must not run")
      },
      shouldRetry: () => false,
      logger,
      sleep: async () => undefined,
    })
    expect(immediate).toEqual({ attempts: 0, connected: false })

    // SIGTERM lands mid-retry: no further attempt, no waiting on the sleep
    let retrying = true
    let attempts = 0
    const outcome = await connectWithBackoff({
      attempt: async () => {
        attempts += 1
        if (attempts === 2) retrying = false
        throw mongoDown()
      },
      shouldRetry: () => retrying,
      logger,
      sleep: async () => undefined,
    })
    expect(outcome).toEqual({ attempts: 2, connected: false })
  })

  test("the websocket listener comes up and still rejects unsigned upgrades", async () => {
    // no database anywhere in this path: the gateway must be up and enforcing
    // the handshake even while mongo is unreachable
    const config = { ...loadConfig({ HOST: "127.0.0.1" }), wsPort: 0 }
    const gateway = createGateway(config, silentPino(), new HandlerRegistry(silentLogger()))
    try {
      expect(gateway.stats.running).toBe(true)
      const response = await fetch(`http://127.0.0.1:${gateway.port}/ws`)
      expect(response.status).toBe(401)
    } finally {
      await gateway.stop()
    }
  })

  test("a join during the outage fails closed, then the flow works after recovery", async () => {
    const h = authHarness()
    activeUser(h.store.inner)
    h.store.down = true

    // the same posture as the lifecycle hook in the wiring: log, never crash
    const failures: unknown[] = []
    const supervise = async (run: () => Promise<unknown>): Promise<void> => {
      try {
        await run()
      } catch (error) {
        failures.push(error)
      }
    }

    await supervise(() => h.service.handlePlayerJoined(JOIN, 1_000))
    expect(failures).toHaveLength(1)
    expect(h.pushes).toHaveLength(0)
    expect(h.prompts).toHaveLength(0)
    expect(await h.store.inner.findOpenSession(UUID)).toBeNull()

    // sweeps through the outage never approve anything on their own
    await supervise(() => h.service.sweep(2_000))
    expect(h.pushes.filter((push) => push.type === "AUTH_APPROVED")).toHaveLength(0)
    expect(await h.store.inner.findOpenSession(UUID)).toBeNull()

    // mongo is back: the next join is a normal fail-open-to-guest flow
    h.store.down = false
    await h.service.sweep(2_500)
    const decision = await h.service.handlePlayerJoined(JOIN, 3_000)
    expect(decision.kind).toBe("AUTH_FLOW_STARTED")
    expect(h.pushes.filter((push) => push.type === "AUTH_REQUEST")).toHaveLength(1)
    expect(h.prompts).toHaveLength(1)

    const prompt = h.prompts[0]
    if (!prompt) throw new Error("no prompt delivered")
    const approved = await h.service.approve(prompt.token, "100", 4_000)
    expect(approved.kind).toBe("APPROVED")
    expect(h.pushes.filter((push) => push.type === "AUTH_APPROVED")).toHaveLength(1)
  })
})

// ---------------------------------------------------------------------------

describe("mongo drops mid-request", () => {
  const specFrame = (id: string): string =>
    signedFrame(
      "SPEC_STARTED",
      {
        adminUuid: UUID,
        targetUuid: OTHER_UUID,
        adminUsername: "admin",
        targetUsername: "target",
      },
      id,
    )

  test("a failing handler answers its ack with a correlated ERROR, the socket survives", async () => {
    const registry = new HandlerRegistry(silentLogger())
    registry.register("SPEC_STARTED", async () => {
      throw mongoDown()
    })
    const beats: string[] = []
    registry.register("SERVER_HEARTBEAT", () => {
      beats.push("beat")
    })

    const fake = fakeSocket()
    const connection = new ServerConnection(SERVER, fake.socket, connectionOptions(), silentPino())
    const route = (envelope: Parameters<typeof registry.dispatch>[0], conn: ServerConnection) =>
      registry.dispatch(envelope, conn)

    const failure = connection.handleFrame(specFrame("frame-0001"), WS_NOW, route)
    expect(failure).toBeNull()
    // the handler runs detached from the frame handler; give it a tick
    await Bun.sleep(10)

    const frames = sentFrames(fake.sent)
    expect(frames.filter((frame) => frame.type === "ACK")).toHaveLength(1)
    const error = frames.find((frame) => frame.type === "ERROR")
    expect(error).toBeDefined()
    expect(error?.payload.code).toBe("HANDLER_FAILED")
    expect(error?.payload.inReplyTo).toBe("frame-0001")

    // a wire-valid frame the plugin can actually decode (signed at send time)
    const errorText = fake.sent.find((text) => text.includes('"ERROR"'))
    if (errorText === undefined) throw new Error("no error frame on the wire")
    const verified = verifyWireMessage(errorText, WS_SECRET, Date.now(), {
      maxSkewMs: 30_000,
      maxPacketBytes: 262_144,
    })
    expect(verified.ok).toBe(true)

    // no crash, no close, no hang: later frames are still routed
    expect(fake.isClosed()).toBe(false)
    connection.handleFrame(
      signedFrame("SERVER_HEARTBEAT", { onlinePlayers: 1, maxPlayers: 20 }, "hb-0000001"),
      WS_NOW,
      route,
    )
    expect(beats).toEqual(["beat"])
  })

  test("an audit write that dies with the database never costs the socket", async () => {
    // the spec trail is bookkeeping: a failed insert is logged and swallowed,
    // so the frame completes instead of hanging and the connection stays
    const registry = new HandlerRegistry(silentLogger())
    registerSpecHandlers(registry, {
      audit: new SpecAuditService({
        sink: {
          append: async () => {
            throw mongoDown()
          },
        },
        logger: { info: () => undefined, warn: () => undefined },
      }),
      logger: silentLogger(),
    })

    const fake = fakeSocket()
    const connection = new ServerConnection(SERVER, fake.socket, connectionOptions(), silentPino())
    connection.handleFrame(specFrame("frame-0002"), WS_NOW, (envelope, conn) => registry.dispatch(envelope, conn))
    await Bun.sleep(10)

    const frames = sentFrames(fake.sent)
    expect(frames.filter((frame) => frame.type === "ACK")).toHaveLength(1)
    expect(frames.filter((frame) => frame.type === "ERROR")).toHaveLength(0)
    expect(fake.isClosed()).toBe(false)
  })
})

// ---------------------------------------------------------------------------

describe("write timeouts and overlapping sweeps", () => {
  test("a stalled auth sweep makes the next tick a no-op, then releases the guard", async () => {
    const h = authHarness()
    let release!: () => void
    h.store.stalled = {
      method: "findPendingExpired",
      promise: new Promise<void>((resolve) => {
        release = resolve
      }),
    }

    const first = h.service.sweep(1_000)
    const second = await h.service.sweep(1_001)
    expect(second).toBeUndefined()
    // the skipped tick never reached the database at all
    expect(h.store.calls.get("findPendingExpired")).toBe(1)

    release()
    await first
    h.store.stalled = null

    await h.service.sweep(1_002)
    expect(h.store.calls.get("findPendingExpired")).toBe(2)
  })

  test("a sweep that rejects releases the guard instead of dying for good", async () => {
    const h = authHarness()
    h.store.down = true

    // app.ts wraps the interval callback in .catch, so a rejection is a log line
    const logged: unknown[] = []
    await h.service.sweep(1_000).catch((error: unknown) => logged.push(error))
    expect(logged).toHaveLength(1)

    h.store.down = false
    await h.service.sweep(1_001)
    expect(h.store.calls.get("findPendingExpired")).toBe(2)
  })

  test("a slow retention pass never overlaps the next tick and the cadence survives", async () => {
    const purges = new GatedPurges()
    const service = new RetentionService({
      audit: purges.auditPort,
      serverEvents: purges.eventsPort,
      sessions: purges.sessionsPort,
      snapshots: purges.snapshotsPort,
      votingMessages: purges.votingMessagesPort,
      invites: purges.invitesPort,
      votes: purges.votesPort,
      applications: purges.applicationsPort,
      options: { intervalMs: 10, initialDelayMs: 10 },
      logger: { info: () => undefined, warn: () => undefined },
    })

    let release!: () => void
    purges.gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const stop = service.start()
    try {
      await Bun.sleep(60)
      // every tick that landed mid-pass was skipped, not stacked
      expect(purges.maxConcurrent).toBe(1)

      release()
      purges.gate = null
      await Bun.sleep(60)
      expect(purges.maxConcurrent).toBe(1)

      const stepsBefore = purges.steps
      expect(stepsBefore).toBeGreaterThan(1)
      await Bun.sleep(40)
      expect(purges.steps).toBeGreaterThan(stepsBefore)

      // a pass that keeps failing does not stop the schedule either
      purges.fail = true
      await Bun.sleep(40)
      const stepsDuringFailure = purges.steps
      await Bun.sleep(40)
      expect(purges.steps).toBeGreaterThan(stepsDuringFailure)
    } finally {
      stop()
    }
  })
})

/** purge ports that stall on one gate and count how many run at once */
class GatedPurges {
  concurrent = 0
  maxConcurrent = 0
  steps = 0
  gate: Promise<void> | null = null
  fail = false

  private async step(): Promise<number> {
    this.concurrent += 1
    this.maxConcurrent = Math.max(this.maxConcurrent, this.concurrent)
    this.steps += 1
    try {
      if (this.gate !== null) await this.gate
      if (this.fail) throw mongoDown()
      return 0
    } finally {
      this.concurrent -= 1
    }
  }

  readonly auditPort = { deleteOlderThan: (): Promise<number> => this.step() }
  readonly eventsPort = { deleteOlderThan: (): Promise<number> => this.step() }
  readonly sessionsPort = {
    deleteClosedOlderThan: (): Promise<number> => this.step(),
    pruneDuplicateActive: (): Promise<number> => this.step(),
  }
  readonly snapshotsPort = { deleteOrphanedOlderThan: (): Promise<number> => this.step() }
  readonly votingMessagesPort = { deleteDeadOlderThan: (): Promise<number> => this.step() }
  readonly votesPort = { deleteDecidedOlderThan: (): Promise<number> => this.step() }
  readonly invitesPort = { deleteDeadOlderThan: (): Promise<number> => this.step() }
  readonly applicationsPort = { deleteClosedOlderThan: (): Promise<number> => this.step() }
}

// ---------------------------------------------------------------------------

describe("unhandled rejection paths", () => {
  test("a rejected handler promise never becomes an uncaughtException", async () => {
    // child process: the fixture installs its own uncaughtException /
    // unhandledRejection hooks, routes one throwing frame through the real
    // connection + registry, and only exits 0 if nothing escaped
    const fixture = `${import.meta.dir}/fixtures/rejecting-handler-child.ts`
    const proc = Bun.spawn([process.execPath, fixture], { stdout: "pipe", stderr: "pipe" })
    const stdout = await new Response(proc.stdout).text()
    const exitCode = await proc.exited

    expect(exitCode).toBe(0)
    expect(stdout).toContain("ALIVE")
    expect(stdout).toContain("acks=2")
    expect(stdout).toContain("errors=1")
    expect(stdout).toContain("heartbeats=1")
    expect(stdout).toContain("closed=false")
    expect(stdout).not.toContain("UNHANDLED_REJECTION")
    expect(stdout).not.toContain("UNCAUGHT_EXCEPTION")
  })
})
