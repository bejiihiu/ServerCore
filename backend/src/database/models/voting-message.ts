import { model, Schema, Types } from "mongoose"

/**
 * every private message with a voting keyboard, per voter. needed to push
 * live vote counts into all the keyboards while the voting runs.
 */
export interface VotingMessage {
  applicationId: Types.ObjectId
  telegramId: string
  chatId: number
  messageId: number
  /** group = public vote message, ephemeral = one voter's private confirmation */
  kind: "group" | "ephemeral"
  createdAt: Date
}

const votingMessageSchema = new Schema<VotingMessage>(
  {
    applicationId: { type: Schema.Types.ObjectId, required: true, ref: "Application" },
    telegramId: { type: String, required: true },
    chatId: { type: Number, required: true },
    messageId: { type: Number, required: true },
    kind: { type: String, enum: ["group", "ephemeral"], required: true },
  },
  { timestamps: { createdAt: true, updatedAt: false } },
)

votingMessageSchema.index({ applicationId: 1 })

export const VotingMessageModel = model<VotingMessage>(
  "VotingMessage",
  votingMessageSchema,
  "voting_messages",
)
