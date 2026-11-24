import { model, Schema } from "mongoose"

export const AUDIT_ACTOR_TYPES = ["PLAYER", "ADMIN", "TELEGRAM", "SYSTEM"] as const

export type AuditActorType = (typeof AUDIT_ACTOR_TYPES)[number]

/**
 * append-only audit trail. entries are never updated or deleted — that's the
 * point of an audit log.
 */
export interface AuditLog {
  actorType: AuditActorType
  actorId: string
  action: string
  targetType: string | null
  targetId: string | null
  details: Record<string, unknown>
  serverId: string | null
  relatedSessionId: string | null
  createdAt: Date
}

const auditLogSchema = new Schema<AuditLog>(
  {
    actorType: { type: String, enum: AUDIT_ACTOR_TYPES, required: true },
    actorId: { type: String, required: true },
    action: { type: String, required: true },
    targetType: { type: String, default: null },
    targetId: { type: String, default: null },
    // shape depends on the action; validated at the service layer
    details: { type: Schema.Types.Mixed, default: {} },
    serverId: { type: String, default: null },
    relatedSessionId: { type: String, default: null },
  },
  { timestamps: { createdAt: true, updatedAt: false } },
)

auditLogSchema.index({ actorType: 1, actorId: 1, createdAt: -1 })
auditLogSchema.index({ action: 1, createdAt: -1 })
auditLogSchema.index({ targetId: 1 })

export const AuditLogModel = model<AuditLog>("AuditLog", auditLogSchema, "audit_logs")
