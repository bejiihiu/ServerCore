import { describe, expect, test } from "bun:test"
import { payloadSchemas } from "../src/websocket/protocol/message-types.js"
import { HandlerRegistry, silentLogger } from "../src/websocket/handlers/registry.js"
import { registerLifecycleHandlers, ServerDirectory } from "../src/websocket/handlers/server-lifecycle.js"
import { registerSpecHandlers } from "../src/websocket/handlers/spec-handlers.js"
import { SpecAuditService } from "../src/modules/audit/spec-audit-service.js"
import { StatsService } from "../src/services/runtime-stats.js"
import type { ServerConnection } from "../src/websocket/connection.js"
import type { AuditRecord } from "../src/modules/account/repository.js"

const ADMIN = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
const TARGET = "1b4b2f2e-1c2d-3e4f-5a6b-7c8d9e0f1a2b"

function fakeConnection(serverId: string): ServerConnection {
  return { serverId } as unknown as ServerConnection
}

/** records every AuditRecord the service pushes; the in-memory persistence seam */
class RecordingSink {
  readonly records: AuditRecord[] = []
  failures = 0

  async append(record: AuditRecord): Promise<void> {
    if (this.failures > 0) {
      this.failures -= 1
      throw new Error("sink down")
    }
    this.records.push(structuredClone(record))
  }
}

function envelope<T extends "SPEC_STARTED" | "SPEC_ENDED" | "SERVER_HEARTBEAT" | "SERVER_STATUS">(
  type: T,
  payload: Record<string, unknown>,
) {
  return { id: "msg_12345678", type, timestamp: Date.now(), payload } as never
}

describe("spec payload schemas", () => {
  test("SPEC_STARTED accepts uuids, session and usernames", () => {
    const result = payloadSchemas.SPEC_STARTED.safeParse({
      adminUuid: ADMIN,
      targetUuid: TARGET,
      sessionId: "spec-abc123",
      adminUsername: "Moderator",
      targetUsername: "griefer_1",
    })
    expect(result.success).toBe(true)
  })

  test("SPEC_STARTED stays valid without optional fields (old builds)", () => {
    const result = payloadSchemas.SPEC_STARTED.safeParse({ adminUuid: ADMIN, targetUuid: TARGET })
    expect(result.success).toBe(true)
  })

  test("SPEC_STARTED rejects a malformed uuid and a bad username", () => {
    expect(
      payloadSchemas.SPEC_STARTED.safeParse({ adminUuid: "not-a-uuid", targetUuid: TARGET }).success,
    ).toBe(false)
    expect(
      payloadSchemas.SPEC_STARTED.safeParse({ adminUuid: ADMIN, targetUuid: TARGET, adminUsername: "has space" })
        .success,
    ).toBe(false)
  })

  test("SPEC_ENDED defaults the reason to NORMAL and carries the duration", () => {
    const result = payloadSchemas.SPEC_ENDED.parse({
      adminUuid: ADMIN,
      targetUuid: TARGET,
      sessionId: "spec-abc123",
      durationSec: 95,
    })
    expect(result.reason).toBe("NORMAL")
    expect(result.durationSec).toBe(95)
  })

  test("SPEC_ENDED rejects an unknown reason and a negative duration", () => {
    expect(
      payloadSchemas.SPEC_ENDED.safeParse({ adminUuid: ADMIN, targetUuid: TARGET, reason: "BECAUSE" }).success,
    ).toBe(false)
    expect(
      payloadSchemas.SPEC_ENDED.safeParse({ adminUuid: ADMIN, targetUuid: TARGET, durationSec: -1 }).success,
    ).toBe(false)
  })
})

describe("spec audit handlers", () => {
  function makeDeps() {
    const sink = new RecordingSink()
    const audit = new SpecAuditService({ sink, logger: silentLogger() as never })
    const registry = new HandlerRegistry(silentLogger())
    registerSpecHandlers(registry, { audit, logger: silentLogger() as never })
    return { sink, registry }
  }

  test("SPEC_STARTED lands in audit_logs as a kind=spec admin entry", async () => {
    const { sink, registry } = makeDeps()
    await registry.dispatch(
      envelope("SPEC_STARTED", {
        adminUuid: ADMIN,
        targetUuid: TARGET,
        sessionId: "spec-abc123",
        adminUsername: "Moderator",
      }),
      fakeConnection("game-1"),
    )

    expect(sink.records).toHaveLength(1)
    const record = sink.records[0]!
    expect(record.actorType).toBe("ADMIN")
    expect(record.actorId).toBe(ADMIN)
    expect(record.action).toBe("spec.started")
    expect(record.targetType).toBe("PLAYER")
    expect(record.targetId).toBe(TARGET)
    expect(record.relatedSessionId).toBe("spec-abc123")
    expect(record.serverId).toBe("game-1")
    expect(record.details).toMatchObject({ kind: "spec", adminUsername: "Moderator" })
  })

  test("SPEC_ENDED closes the trail with duration and reason", async () => {
    const { sink, registry } = makeDeps()
    await registry.dispatch(
      envelope("SPEC_ENDED", {
        adminUuid: ADMIN,
        targetUuid: TARGET,
        sessionId: "spec-abc123",
        durationSec: 120,
        reason: "TARGET_DISCONNECTED",
      }),
      fakeConnection("game-1"),
    )

    const record = sink.records[0]!
    expect(record.action).toBe("spec.ended")
    expect(record.relatedSessionId).toBe("spec-abc123")
    expect(record.details).toMatchObject({ kind: "spec", durationSec: 120, reason: "TARGET_DISCONNECTED" })
  })

  test("a sink failure is swallowed: the connection never dies over bookkeeping", async () => {
    const { sink, registry } = makeDeps()
    sink.failures = 1
    // must not throw
    await registry.dispatch(
      envelope("SPEC_STARTED", { adminUuid: ADMIN, targetUuid: TARGET }),
      fakeConnection("game-1"),
    )
    expect(sink.records).toHaveLength(0)
  })
})

describe("active spec sessions counter wiring", () => {
  function makeStats() {
    const registry = new HandlerRegistry(silentLogger())
    const directory = new ServerDirectory()
    registerLifecycleHandlers(registry, directory, silentLogger() as never)
    const stats = new StatsService({
      startedAt: Date.now(),
      gateway: { stats: { running: true, connections: 1 }, connectedServers: ["game-1"] },
      directory,
      counters: { activeSessions: 0, guestSessions: 0, pendingAuths: 0 },
    })
    return { registry, directory, stats }
  }

  test("heartbeat count lands in the directory and the /stats snapshot", async () => {
    const { registry, stats } = makeStats()
    await registry.dispatch(
      envelope("SERVER_HEARTBEAT", { onlinePlayers: 5, maxPlayers: 20, activeSpecSessions: 2 }),
      fakeConnection("game-1"),
    )
    const snap = stats.snapshot()
    expect(snap.servers[0]?.activeSpecSessions).toBe(2)
  })

  test("status frames carry the counter too and old builds keep it untouched", async () => {
    const { registry, directory, stats } = makeStats()
    await registry.dispatch(
      envelope("SERVER_STATUS", { onlinePlayers: 1, maxPlayers: 10, state: "RUNNING", activeSpecSessions: 1 }),
      fakeConnection("game-1"),
    )
    expect(directory.get("game-1")?.activeSpecSessions).toBe(1)

    // a frame without the field keeps the last known count
    await registry.dispatch(
      envelope("SERVER_HEARTBEAT", { onlinePlayers: 2, maxPlayers: 10 }),
      fakeConnection("game-1"),
    )
    expect(directory.get("game-1")?.activeSpecSessions).toBe(1)

    // aggregates to the total the /stats line renders
    const snap = stats.snapshot()
    expect(snap.servers.reduce((sum, s) => sum + (s.activeSpecSessions ?? 0), 0)).toBe(1)
  })
})
