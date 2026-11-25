import { afterAll, beforeEach, describe, expect, test } from "bun:test"
import mongoose from "mongoose"
import { MongoAuditRepository } from "../src/modules/account/repository-mongo.js"
import { SpecAuditService } from "../src/modules/audit/spec-audit-service.js"
import { silentLogger } from "../src/websocket/handlers/registry.js"
import { mongoUnavailableReason, startTestMongo, type TestMongo } from "./helpers/mongo.js"

/**
 * the spec audit trail through the real sink. the handler retry contract says
 * a failed frame comes back under a fresh envelope id, so the same observation
 * event reaches the service twice — the deterministic row id has to collapse
 * it into one audit_logs document, not two.
 */

const mongo: TestMongo | null = await startTestMongo("spec_audit")
if (!mongo) console.warn(`[mongo spec audit skipped] ${mongoUnavailableReason()}`)

const describeDb = describe.skipIf(mongo === null)

afterAll(async () => {
  await mongo?.stop()
})

const ADMIN = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
const TARGET = "1b4b2f2e-1c2d-3e4f-5a6b-7c8d9e0f1a2b"

const audit = new MongoAuditRepository()
const service = new SpecAuditService({ sink: audit, logger: silentLogger() as never })

function rows(): Promise<Array<Record<string, unknown>>> {
  return mongoose.connection.collection("audit_logs").find({}).sort({ _id: 1 }).toArray()
}

beforeEach(async () => {
  await mongoose.connection.collection("audit_logs").deleteMany({})
})

describeDb("spec audit idempotency on a real collection", () => {
  test("a replayed SPEC_STARTED leaves exactly one row", async () => {
    const occurredAt = new Date("2026-11-24T16:30:00Z")
    const started = {
      action: "spec.started" as const,
      adminUuid: ADMIN,
      targetUuid: TARGET,
      sessionId: "spec-abc123",
      serverId: "game-1",
      occurredAt,
    }

    await service.record(started)
    await service.record(started)

    const stored = await rows()
    expect(stored).toHaveLength(1)
    expect(stored[0]!._id).toBe("spec:spec-abc123:STARTED")
    expect(stored[0]!.relatedSessionId).toBe("spec-abc123")
    // the row keeps the event time, not the time of the retry
    expect((stored[0]!.createdAt as Date).getTime()).toBe(occurredAt.getTime())
  })

  test("a replayed SPEC_ENDED leaves one row and keeps the first reason", async () => {
    const base = {
      action: "spec.ended" as const,
      adminUuid: ADMIN,
      targetUuid: TARGET,
      sessionId: "spec-abc123",
      durationSec: 120,
      serverId: "game-1",
      occurredAt: new Date("2026-11-24T16:31:00Z"),
    }

    await service.record({ ...base, reason: "NORMAL" })
    await service.record({ ...base, reason: "FORCED" })

    const stored = await rows()
    expect(stored).toHaveLength(1)
    expect(stored[0]!._id).toBe("spec:spec-abc123:ENDED")
    expect((stored[0]!.details as Record<string, unknown>).reason).toBe("NORMAL")
  })

  test("distinct sessions keep one row each, replays do not touch them", async () => {
    const base = {
      action: "spec.started" as const,
      adminUuid: ADMIN,
      targetUuid: TARGET,
      serverId: "game-1",
      occurredAt: new Date("2026-11-24T16:30:00Z"),
    }

    await service.record({ ...base, sessionId: "spec-1" })
    await service.record({ ...base, sessionId: "spec-2" })
    await service.record({ ...base, sessionId: "spec-1" })

    const stored = await rows()
    expect(stored.map((row) => row._id)).toEqual(["spec:spec-1:STARTED", "spec:spec-2:STARTED"])
  })

  test("plain appends still land as their own docs next to the deterministic ones", async () => {
    await service.record({
      action: "spec.started",
      adminUuid: ADMIN,
      targetUuid: TARGET,
      sessionId: "spec-1",
      serverId: "game-1",
      occurredAt: new Date("2026-11-24T16:30:00Z"),
    })
    // an older plugin build sends no session id: nothing to key on, plain append
    await audit.append({
      actorType: "SYSTEM",
      actorId: ADMIN,
      action: "account.created",
      targetType: "user",
      targetId: TARGET,
      details: { username: "target" },
      serverId: null,
      relatedSessionId: null,
      createdAt: new Date("2026-11-24T16:29:00Z"),
    })

    const stored = await rows()
    expect(stored).toHaveLength(2)
    const actions = stored.map((row) => row.action).sort()
    expect(actions).toEqual(["account.created", "spec.started"])
  })
})
