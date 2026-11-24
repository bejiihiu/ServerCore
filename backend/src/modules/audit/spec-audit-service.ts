import type { AuditRecord } from "../account/repository.js"

/**
 * append-only audit trail for administrative observation sessions. every
 * start and end lands in the audit_logs collection with kind "spec" in the
 * details — the collection is purged by the retention service (90d by
 * default, no ttl index: the daily cleanup job is the only janitor).
 *
 * the entry carries uuids and usernames only, nothing else about the
 * players — an audit log must not become a second source of personal data.
 */
export type SpecAuditAction = "spec.started" | "spec.ended"

export type SpecAuditEntry = {
  action: SpecAuditAction
  adminUuid: string
  adminUsername?: string
  targetUuid: string
  targetUsername?: string
  sessionId?: string
  /** wall duration of the observation, present on ends */
  durationSec?: number
  /** end reason: NORMAL | FORCED | TARGET_DISCONNECTED | ADMIN_DISCONNECTED | ERROR */
  reason?: string
  /** serverId of the connection the event arrived through */
  serverId: string
  occurredAt: Date
}

/** port over the audit_logs collection; mongo implements it, tests fake it */
export interface SpecAuditSink {
  append(record: AuditRecord): Promise<void>
}

export class SpecAuditService {
  private readonly sink: SpecAuditSink
  private readonly logger: { info(obj: unknown, msg: string): void; warn(obj: unknown, msg: string): void }

  constructor(deps: {
    sink: SpecAuditSink
    logger: { info(obj: unknown, msg: string): void; warn(obj: unknown, msg: string): void }
  }) {
    this.sink = deps.sink
    this.logger = deps.logger
  }

  /** one audit row per event; a sink failure is logged, never propagated —
   * the websocket handler must not blow up the connection over bookkeeping */
  async record(entry: SpecAuditEntry): Promise<void> {
    const record: AuditRecord = {
      actorType: "ADMIN",
      actorId: entry.adminUuid,
      action: entry.action,
      targetType: "PLAYER",
      targetId: entry.targetUuid,
      details: {
        kind: "spec",
        ...(entry.adminUsername !== undefined ? { adminUsername: entry.adminUsername } : {}),
        ...(entry.targetUsername !== undefined ? { targetUsername: entry.targetUsername } : {}),
        ...(entry.durationSec !== undefined ? { durationSec: entry.durationSec } : {}),
        ...(entry.reason !== undefined ? { reason: entry.reason } : {}),
      },
      serverId: entry.serverId,
      relatedSessionId: entry.sessionId ?? null,
      createdAt: entry.occurredAt,
    }
    try {
      await this.sink.append(record)
      this.logger.info(
        {
          action: entry.action,
          admin: entry.adminUuid,
          target: entry.targetUuid,
          sessionId: entry.sessionId ?? null,
          serverId: entry.serverId,
        },
        "spec audit entry recorded",
      )
    } catch (error) {
      this.logger.warn({ err: error, action: entry.action, admin: entry.adminUuid }, "spec audit write failed")
    }
  }
}
