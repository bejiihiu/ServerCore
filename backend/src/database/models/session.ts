import { model, Schema } from "mongoose"

export const SESSION_STATUSES = ["WAITING_AUTH", "AUTHENTICATED", "GUEST", "DISCONNECTED"] as const

export type SessionStatus = (typeof SESSION_STATUSES)[number]

export interface PlayerSession {
  minecraftUuid: string
  startedAt: Date
  endedAt: Date | null
  status: SessionStatus
  serverId: string
}

const sessionSchema = new Schema<PlayerSession>(
  {
    minecraftUuid: { type: String, required: true },
    startedAt: { type: Date, required: true },
    endedAt: { type: Date, default: null },
    status: { type: String, enum: SESSION_STATUSES, required: true },
    serverId: { type: String, required: true },
  },
  { timestamps: true },
)

sessionSchema.index({ minecraftUuid: 1, startedAt: -1 })
// active sessions lookup: uuid + no end yet
sessionSchema.index(
  { minecraftUuid: 1 },
  { unique: true, partialFilterExpression: { endedAt: null } },
)

export const PlayerSessionModel = model<PlayerSession>("PlayerSession", sessionSchema, "sessions")
