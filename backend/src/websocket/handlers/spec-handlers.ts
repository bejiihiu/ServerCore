import type { Envelope } from "../protocol/message-types.js"
import type { HandlerRegistry } from "./registry.js"
import type { Logger } from "../../logging/logger.js"
import type { SpecAuditService } from "../../modules/audit/spec-audit-service.js"

/**
 * administrative observation (spec) handlers:
 * - SPEC_STARTED opens an audit_logs row for the observation session
 * - SPEC_ENDED closes the trail with the wall duration and end reason
 *
 * every session the plugin runs must show up here — hidden observation from
 * the player's point of view still leaves a full administrative audit trail.
 *
 * both handlers are idempotent per (session, event): the plugin resends a
 * frame the backend failed on under a fresh envelope id, and the second pass
 * must land on the row the first one wrote, not next to it.
 */
export function registerSpecHandlers(
  registry: HandlerRegistry,
  deps: {
    audit: SpecAuditService
    logger: Logger
  },
): void {
  registry.register("SPEC_STARTED", async (envelope: Envelope<"SPEC_STARTED">, connection) => {
    const payload = envelope.payload
    await deps.audit.record({
      action: "spec.started",
      adminUuid: payload.adminUuid,
      adminUsername: payload.adminUsername,
      targetUuid: payload.targetUuid,
      targetUsername: payload.targetUsername,
      sessionId: payload.sessionId,
      serverId: connection.serverId,
      occurredAt: new Date(envelope.timestamp),
    })
  })

  registry.register("SPEC_ENDED", async (envelope: Envelope<"SPEC_ENDED">, connection) => {
    const payload = envelope.payload
    await deps.audit.record({
      action: "spec.ended",
      adminUuid: payload.adminUuid,
      adminUsername: payload.adminUsername,
      targetUuid: payload.targetUuid,
      targetUsername: payload.targetUsername,
      sessionId: payload.sessionId,
      durationSec: payload.durationSec,
      reason: payload.reason,
      serverId: connection.serverId,
      occurredAt: new Date(envelope.timestamp),
    })
  })
}
