import { z } from "zod"

/**
 * domain message types from the wire protocol spec, plus a few transport-level
 * types (handshake, ack, error) that the protocol needs to work at all.
 * domain types are the contract with the plugin; transport types never leave
 * the gateway layer.
 */
export const MESSAGE_TYPES = [
  // player lifecycle
  "PLAYER_JOINED",
  "PLAYER_LEFT",

  // auth flow
  "AUTH_REQUEST",
  "AUTH_APPROVED",
  "AUTH_REJECTED",
  "AUTH_EXPIRED",

  // guest mode
  "GUEST_STARTED",
  "GUEST_ENDED",

  // admission
  "APPLICATION_SUBMITTED",
  "APPLICATION_APPROVED",
  "APPLICATION_REJECTED",

  // account
  "ACCOUNT_DELETED",

  // account linking: fresh one-time deep-link token pushed to the plugin so
  // the game chat can show the personal bot link
  "LINK_TOKEN",

  // account linking: backend asks the plugin whether a uuid is online right
  // now (ownership proof for telegram linking), plugin answers
  "SESSION_CHECK",
  "SESSION_CHECK_RESULT",

  // spectator administration
  "SPEC_STARTED",
  "SPEC_ENDED",

  // server lifecycle
  "SERVER_STATUS",
  "SERVER_HEARTBEAT",

  // transport-level
  "SERVER_HELLO",
  "SERVER_READY",
  "ACK",
  "ERROR",
] as const

export type MessageType = (typeof MESSAGE_TYPES)[number]

/** types the backend pushes to the plugin, everything else originates plugin-side */
export const BACKEND_TO_PLUGIN_TYPES: readonly MessageType[] = [
  "AUTH_APPROVED",
  "AUTH_REJECTED",
  "AUTH_EXPIRED",
  "APPLICATION_APPROVED",
  "APPLICATION_REJECTED",
  "ACCOUNT_DELETED",
  "LINK_TOKEN",
  "SESSION_CHECK",
  "SPEC_STARTED",
  "SPEC_ENDED",
  "SERVER_READY",
  "ACK",
  "ERROR",
]

/** these are fire-and-forget or answered by a dedicated reply, no ACK expected */
export const NO_ACK_TYPES: readonly MessageType[] = [
  "SERVER_HEARTBEAT",
  "SERVER_HELLO",
  "SERVER_READY",
  "ACK",
  "ERROR",
]

export const MINECRAFT_UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const minecraftUuid = z.string().regex(MINECRAFT_UUID_PATTERN, "not a valid minecraft uuid")
const serverId = z.string().min(1).max(64).regex(/^[a-z0-9._-]+$/)
const username = z.string().min(1).max(16).regex(/^[A-Za-z0-9_]+$/)
const objectId = z.string().regex(/^[0-9a-f]{24}$/i, "not a valid objectid")

export const payloadSchemas = {
  PLAYER_JOINED: z.object({
    minecraftUuid,
    username,
    serverId: serverId.optional(),
  }),

  PLAYER_LEFT: z.object({
    minecraftUuid,
    username: username.optional(),
    reason: z.enum(["DISCONNECT", "KICK", "BAN", "TIMEOUT", "ERROR"]).default("DISCONNECT"),
  }),

  AUTH_REQUEST: z.object({
    minecraftUuid,
    username,
    sessionId: serverId,
  }),

  AUTH_APPROVED: z.object({
    minecraftUuid,
    sessionId: serverId,
  }),

  AUTH_REJECTED: z.object({
    minecraftUuid,
    sessionId: serverId,
    reason: z.string().max(256).optional(),
  }),

  AUTH_EXPIRED: z.object({
    minecraftUuid,
    sessionId: serverId,
  }),

  GUEST_STARTED: z.object({
    minecraftUuid,
    username,
    sessionId: serverId,
    snapshot: z.object({
      world: z.string().min(1).max(128),
      x: z.number(),
      y: z.number(),
      z: z.number(),
      yaw: z.number(),
      pitch: z.number(),
      gameMode: z.string().min(1).max(32),
      flying: z.boolean(),
      flySpeed: z.number().min(0).max(10),
      walkSpeed: z.number().min(0).max(10),
    }),
  }),

  GUEST_ENDED: z.object({
    minecraftUuid,
    sessionId: serverId,
    reason: z.enum(["AUTHENTICATED", "LOGOUT", "KICK", "BAN", "ERROR"]),
  }),

  APPLICATION_SUBMITTED: z.object({
    applicationId: objectId,
    minecraftUuid,
    telegramId: z.string().min(1).max(32),
  }),

  APPLICATION_APPROVED: z.object({
    applicationId: objectId,
    minecraftUuid,
  }),

  APPLICATION_REJECTED: z.object({
    applicationId: objectId,
    minecraftUuid,
    reason: z.string().max(512).optional(),
  }),

  ACCOUNT_DELETED: z.object({
    minecraftUuid,
    reason: z.enum(["SELF_REQUESTED", "ADMIN_ACTION", "INACTIVITY"]),
  }),

  LINK_TOKEN: z.object({
    minecraftUuid,
    username,
    // one-time linking token, 16 alphanumerics
    token: z.string().length(16).regex(/^[A-Za-z0-9]+$/),
    // epoch millis after which the token is dead
    expiresAt: z.number().int().min(0),
  }),

  SESSION_CHECK: z.object({
    minecraftUuid,
  }),

  SESSION_CHECK_RESULT: z.object({
    minecraftUuid,
    online: z.boolean(),
    // present when online: id of the live session the plugin holds
    sessionId: serverId.optional(),
    // present when online: what the session currently is
    sessionKind: z.enum(["GUEST", "WAITING_AUTH", "AUTHENTICATED"]).optional(),
  }),

  SPEC_STARTED: z.object({
    adminUuid: minecraftUuid,
    targetUuid: minecraftUuid,
    sessionId: serverId.optional(),
  }),

  SPEC_ENDED: z.object({
    adminUuid: minecraftUuid,
    targetUuid: minecraftUuid,
  }),

  SERVER_STATUS: z.object({
    onlinePlayers: z.number().int().min(0),
    maxPlayers: z.number().int().min(0),
    tps: z.number().min(0).max(1000).optional(),
    version: z.string().max(64).optional(),
  }),

  SERVER_HEARTBEAT: z.object({
    onlinePlayers: z.number().int().min(0),
    maxPlayers: z.number().int().min(0),
    tps: z.number().min(0).max(1000).optional(),
  }),

  SERVER_HELLO: z.object({
    serverId,
    clientNonce: z.string().min(8).max(128),
  }),

  SERVER_READY: z.object({
    serverNonce: z.string().min(8).max(128),
    heartbeatIntervalMs: z.number().int().min(1000),
    heartbeatTimeoutMs: z.number().int().min(1000),
  }),

  ACK: z.object({
    ackedId: z.string().min(8).max(64),
  }),

  ERROR: z.object({
    code: z.string().min(1).max(64),
    message: z.string().min(1).max(512),
    inReplyTo: z.string().min(8).max(64).optional(),
  }),
} as const satisfies Record<MessageType, z.ZodType>

export type PayloadOf<T extends MessageType> = z.output<(typeof payloadSchemas)[T]>
export type EnvelopePayloads = { [T in MessageType]: PayloadOf<T> }

/** envelope without the hmac — this is exactly what gets signed */
export const envelopeMetaSchema = z.object({
  id: z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/),
  type: z.enum(MESSAGE_TYPES),
  timestamp: z.number().int().min(0),
  correlationId: z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/).optional(),
})

export type EnvelopeMeta = z.infer<typeof envelopeMetaSchema>

/**
 * a fully validated message: meta fields + the payload for its concrete type.
 * the discriminated union is built by hand instead of z.discriminatedUnion so
 * the payload schema lookup stays a plain map access.
 */
export type Envelope<T extends MessageType = MessageType> = EnvelopeMeta & {
  type: T
  payload: PayloadOf<T>
}

export type WireMessage = EnvelopeMeta & {
  payload: unknown
  hmac: string
}
