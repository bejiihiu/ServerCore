import { model, Schema } from "mongoose"

export const SERVER_EVENT_STATUSES = ["RECEIVED", "PROCESSED", "FAILED"] as const

export type ServerEventStatus = (typeof SERVER_EVENT_STATUSES)[number]

/**
 * durable record of every event that arrived from a game server.
 * doubles as the replay source when a connection drops mid-stream: the plugin
 * can re-send and the backend dedupes on the wire message id.
 */
export interface ServerEvent {
  serverId: string
  messageId: string
  type: string
  payload: Record<string, unknown>
  status: ServerEventStatus
  receivedAt: Date
  processedAt: Date | null
  error: string | null
}

const serverEventSchema = new Schema<ServerEvent>(
  {
    serverId: { type: String, required: true },
    // wire message id — unique so a redelivered event can't be double-processed
    messageId: { type: String, required: true },
    type: { type: String, required: true },
    // shape depends on the event type; validated before insert
    payload: { type: Schema.Types.Mixed, required: true },
    status: { type: String, enum: SERVER_EVENT_STATUSES, required: true },
    receivedAt: { type: Date, required: true },
    processedAt: { type: Date, default: null },
    error: { type: String, default: null },
  },
  { timestamps: true },
)

serverEventSchema.index({ messageId: 1 }, { unique: true })
serverEventSchema.index({ serverId: 1, type: 1, receivedAt: -1 })
serverEventSchema.index({ status: 1 })

export const ServerEventModel = model<ServerEvent>("ServerEvent", serverEventSchema, "server_events")
