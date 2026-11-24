import { model, Schema, Types } from "mongoose"

export const GROUP_INVITE_STATUSES = ["ISSUED", "USED", "REVOKED"] as const

export type GroupInviteStatus = (typeof GROUP_INVITE_STATUSES)[number]

/**
 * one-time group invites (member_limit: 1). the link name doubles as the
 * cross-check key: the chat_member update carries invite_link.name and we
 * match it against these records.
 */
export interface GroupInvite {
  name: string
  applicationId: Types.ObjectId
  minecraftUuid: string
  telegramId: string
  chatId: number
  url: string
  status: GroupInviteStatus
  issuedAt: Date
  usedAt: Date | null
  expiresAt: Date
}

const groupInviteSchema = new Schema<GroupInvite>(
  {
    name: { type: String, required: true },
    applicationId: { type: Schema.Types.ObjectId, required: true, ref: "Application" },
    minecraftUuid: { type: String, required: true },
    telegramId: { type: String, required: true },
    chatId: { type: Number, required: true },
    url: { type: String, required: true },
    status: { type: String, enum: GROUP_INVITE_STATUSES, required: true },
    usedAt: { type: Date, default: null },
    expiresAt: { type: Date, required: true },
  },
  { timestamps: { createdAt: true, updatedAt: false } },
)

groupInviteSchema.index({ name: 1 }, { unique: true })
groupInviteSchema.index({ minecraftUuid: 1, status: 1 })

export const GroupInviteModel = model<GroupInvite>("GroupInvite", groupInviteSchema, "group_invites")
