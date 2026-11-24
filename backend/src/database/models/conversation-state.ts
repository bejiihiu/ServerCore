import { model, Schema } from "mongoose"

/**
 * persistence for grammY conversations v2: serialized conversation state per
 * storage key. plain key/value with json blobs — the conversations plugin
 * versioning wraps whatever we store here.
 */
export interface ConversationState {
  key: string
  state: unknown
  createdAt: Date
  updatedAt: Date
}

const conversationStateSchema = new Schema<ConversationState>(
  {
    key: { type: String, required: true },
    // shape is owned by the conversations plugin, stored as-is
    state: { type: Schema.Types.Mixed, required: true },
  },
  { timestamps: true },
)

conversationStateSchema.index({ key: 1 }, { unique: true })
// dead conversation state (bot killed mid-flow, chat never returned) expires
// after 30 days of silence; active flows bump updatedAt on every step
conversationStateSchema.index({ updatedAt: 1 }, { expireAfterSeconds: 30 * 86_400 })

export const ConversationStateModel = model<ConversationState>(
  "ConversationState",
  conversationStateSchema,
  "conversation_states",
)
