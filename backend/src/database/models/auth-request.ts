import { model, Schema } from "mongoose"

export const AUTH_REQUEST_STATUSES = ["PENDING", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED"] as const

export type AuthRequestStatus = (typeof AUTH_REQUEST_STATUSES)[number]

export interface AuthRequest {
  minecraftUuid: string
  telegramId: string
  sessionId: string
  tokenHash: string
  status: AuthRequestStatus
  expiresAt: Date
  createdAt: Date
}

const authRequestSchema = new Schema<AuthRequest>(
  {
    minecraftUuid: { type: String, required: true },
    telegramId: { type: String, required: true },
    sessionId: { type: String, required: true },
    tokenHash: { type: String, required: true },
    status: { type: String, enum: AUTH_REQUEST_STATUSES, required: true },
    expiresAt: { type: Date, required: true },
  },
  { timestamps: true },
)

// only the hash is stored, never the token itself
authRequestSchema.index({ tokenHash: 1 }, { unique: true })
// pending requests per session for the cancel-previous-request flow
authRequestSchema.index({ sessionId: 1, status: 1 })
// ttl: expired requests are dead weight, mongo drops them automatically
authRequestSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 })

export const AuthRequestModel = model<AuthRequest>("AuthRequest", authRequestSchema, "auth_requests")
