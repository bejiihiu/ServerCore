import { model, Schema, Types } from "mongoose"

export const PUNISHMENT_TYPES = ["BAN", "MUTE", "WARN", "KICK"] as const

export type PunishmentType = (typeof PUNISHMENT_TYPES)[number]

/**
 * read-only mirror of punishments issued by the external moderation system.
 * servercore never writes here and never builds its own punishment system.
 */
export interface PunishmentExternal {
  externalId: string
  sourcePlugin: string
  playerUuid: string
  playerName: string | null
  type: PunishmentType
  reason: string | null
  actor: string | null
  issuedAt: Date
  expiresAt: Date | null
  active: boolean
  syncedAt: Date
}

const punishmentExternalSchema = new Schema<PunishmentExternal>(
  {
    externalId: { type: String, required: true },
    sourcePlugin: { type: String, required: true },
    playerUuid: { type: String, required: true },
    playerName: { type: String, default: null },
    type: { type: String, enum: PUNISHMENT_TYPES, required: true },
    reason: { type: String, default: null },
    actor: { type: String, default: null },
    issuedAt: { type: Date, required: true },
    expiresAt: { type: Date, default: null },
    active: { type: Boolean, required: true, default: true },
    syncedAt: { type: Date, required: true },
  },
  { timestamps: true },
)

punishmentExternalSchema.index({ sourcePlugin: 1, externalId: 1 }, { unique: true })
punishmentExternalSchema.index({ playerUuid: 1, active: 1 })

export const PunishmentExternalModel = model<PunishmentExternal>(
  "PunishmentExternal",
  punishmentExternalSchema,
  "punishments_external",
)
