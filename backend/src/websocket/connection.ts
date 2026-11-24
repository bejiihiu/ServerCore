import type { ServerWebSocket } from "bun"
import { signEnvelope, verifyWireMessage, type VerifyFailure } from "./protocol/signing.js"
import { newMessageId, ReplayGuard } from "./security/replay-guard.js"
import { NO_ACK_TYPES, payloadSchemas, type Envelope, type MessageType, type PayloadOf } from "./protocol/message-types.js"
import { randomHex, type JsonValue } from "../utils/crypto.js"
import type { Logger } from "../logging/logger.js"

/** per-connection data attached to the bun socket at upgrade time */
export type GatewaySocketData = {
  serverId: string
  connectedAt: number
  connection?: ServerConnection
}

export type ConnectionOptions = {
  /** per-server pre-shared secret, resolved by the gateway at open() */
  secret: string
  heartbeatIntervalMs: number
  heartbeatTimeoutMs: number
  maxPacketBytes: number
  replayWindowMs: number
  /** replay guard ttl: timestamp window plus enough slack to outlive it */
  replayGuardTtlMs: number
}

type PendingAck = {
  resolve: () => void
  reject: (error: Error) => void
  timer: ReturnType<typeof setTimeout>
}

const ACK_TIMEOUT_MS = 30_000
const MAX_BUFFERED_BYTES = 5 * 1024 * 1024
const HANDSHAKE_TIMEOUT_MS = 10_000

/**
 * one authenticated game-server connection over a native bun socket.
 * owns envelope signing/verification, replay protection, protocol-level
 * heartbeat watchdog and ack correlation. created authenticated (the gateway
 * proved the secret before upgrade); the app-level SERVER_HELLO/SERVER_READY
 * exchange then syncs heartbeat parameters both ways.
 */
export class ServerConnection {
  readonly serverId: string
  authenticated = false

  private readonly socket: ServerWebSocket<GatewaySocketData>
  private readonly options: ConnectionOptions
  private readonly logger: Logger
  private readonly replayGuard: ReplayGuard
  private readonly pendingAcks = new Map<string, PendingAck>()

  private alive = true
  private awaitingPong = false
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private handshakeTimer: ReturnType<typeof setTimeout> | null = null
  private lastSeenAt: number

  constructor(serverId: string, socket: ServerWebSocket<GatewaySocketData>, options: ConnectionOptions, logger: Logger) {
    this.serverId = serverId
    this.socket = socket
    this.options = options
    this.logger = logger
    this.replayGuard = new ReplayGuard(options.replayGuardTtlMs)
    this.lastSeenAt = Date.now()
  }

  /** starts the heartbeat watchdog + hello deadline. call once from open() */
  start(): void {
    this.heartbeatTimer = setInterval(() => {
      if (!this.alive) return
      if (this.awaitingPong) {
        this.logger.warn({ serverId: this.serverId }, "heartbeat timeout, closing connection")
        this.close(1001, "heartbeat timeout")
        return
      }
      this.awaitingPong = true
      this.socket.ping()
    }, this.options.heartbeatIntervalMs)

    // the pre-upgrade hmac already proved the secret; this deadline just drops
    // sockets that connect and then go silent without ever saying hello
    this.handshakeTimer = setTimeout(() => {
      if (!this.authenticated) {
        this.logger.warn({ serverId: this.serverId }, "hello deadline passed")
        this.close(1008, "handshake timeout")
      }
    }, HANDSHAKE_TIMEOUT_MS)
  }

  /** protocol-level pong came back from the plugin */
  onPong(): void {
    this.awaitingPong = false
    this.lastSeenAt = Date.now()
  }

  /**
   * handles a raw inbound frame. returns null when the frame was consumed
   * (valid + routed or rejected), or a failure when the connection must close.
   */
  handleFrame(raw: string, now: number, route: (envelope: Envelope, conn: ServerConnection) => Promise<void>): VerifyFailure | null {
    if (!this.alive) return { reason: "BAD_ENVELOPE", detail: "connection is closing" }

    const result = verifyWireMessage(raw, this.options.secret, now, {
      maxSkewMs: this.options.replayWindowMs,
      maxPacketBytes: this.options.maxPacketBytes,
    })
    if (!result.ok) return result.failure

    const envelope = result.envelope

    if (!this.authenticated && envelope.type !== "SERVER_HELLO") {
      return { reason: "BAD_ENVELOPE", detail: "first message must be SERVER_HELLO" }
    }

    if (envelope.type === "SERVER_HELLO") {
      if (this.authenticated) {
        return { reason: "BAD_ENVELOPE", detail: "duplicate handshake" }
      }
      this.authenticated = true
      if (this.handshakeTimer !== null) {
        clearTimeout(this.handshakeTimer)
        this.handshakeTimer = null
      }
      this.lastSeenAt = now
      this.send("SERVER_READY", {
        serverNonce: randomHex(16),
        heartbeatIntervalMs: this.options.heartbeatIntervalMs,
        heartbeatTimeoutMs: this.options.heartbeatTimeoutMs,
      })
      return null
    }

    if (!this.replayGuard.checkAndRecord(envelope.id, now)) {
      // duplicate id = replayed frame, protocol violation
      return { reason: "BAD_ENVELOPE", detail: `replayed message id ${envelope.id}` }
    }

    this.lastSeenAt = now

    // ack correlation: the ack token travels in correlationId, the plugin
    // echoes it back in the ACK's correlationId (payload.ackedId mirrors it)
    const ack = envelope.correlationId !== undefined ? this.pendingAcks.get(envelope.correlationId) : undefined
    if (envelope.type === "ACK" && ack && envelope.correlationId !== undefined) {
      clearTimeout(ack.timer)
      this.pendingAcks.delete(envelope.correlationId)
      ack.resolve()
      return null
    }

    if (!NO_ACK_TYPES.includes(envelope.type)) {
      this.sendRaw("ACK", { ackedId: envelope.id }, envelope.id)
    }

    // handler errors must not kill the connection — log and keep going
    void route(envelope, this).catch((error: unknown) => {
      this.logger.error({ err: error, serverId: this.serverId, type: envelope.type }, "message handler failed")
    })

    return null
  }

  /** signs and sends an envelope, optionally registering an ack expectation */
  send<T extends MessageType>(
    type: T,
    payload: PayloadOf<T>,
    options: { correlationId?: string; awaitAck?: boolean } = {},
  ): Promise<void> | void {
    const correlationId = options.correlationId ?? (options.awaitAck ? newMessageId() : undefined)

    if (!options.awaitAck || correlationId === undefined) {
      this.sendRaw(type, payload as JsonValue, correlationId)
      return
    }

    return new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingAcks.delete(correlationId)
        reject(new Error(`ack timeout for ${type}`))
      }, ACK_TIMEOUT_MS)
      this.pendingAcks.set(correlationId, { resolve, reject, timer })
      this.sendRaw(type, payload as JsonValue, correlationId)
    })
  }

  private sendRaw(type: MessageType, payload: JsonValue, correlationId?: string): void {
    if (!this.alive) return

    // peer is not draining, kill it instead of ballooning memory
    if (this.socket.getBufferedAmount() > MAX_BUFFERED_BYTES) {
      this.logger.warn({ serverId: this.serverId }, "send buffer overflow, terminating")
      this.close(1013, "slow consumer")
      return
    }

    const wire = signEnvelope(
      {
        id: newMessageId(),
        type,
        timestamp: Date.now(),
        ...(correlationId !== undefined ? { correlationId } : {}),
        payload,
      },
      this.options.secret,
    )

    try {
      this.socket.send(JSON.stringify(wire))
    } catch (error: unknown) {
      this.logger.warn({ err: error, serverId: this.serverId }, "socket send failed")
    }
  }

  close(code: number, reason: string): void {
    if (!this.alive) return
    this.alive = false
    if (this.heartbeatTimer !== null) clearInterval(this.heartbeatTimer)
    if (this.handshakeTimer !== null) clearTimeout(this.handshakeTimer)
    for (const [, ack] of this.pendingAcks) {
      clearTimeout(ack.timer)
      ack.reject(new Error("connection closed"))
    }
    this.pendingAcks.clear()
    try {
      this.socket.close(code, reason.slice(0, 100))
    } catch {
      // already gone
    }
    // hard terminate shortly after in case the peer never answers the close
    setTimeout(() => {
      try {
        this.socket.terminate()
      } catch {
        // already gone
      }
    }, 5_000)
  }

  get isAlive(): boolean {
    return this.alive
  }

  get lastSeen(): number {
    return this.lastSeenAt
  }
}

/** helper used by tests and the gateway to know a type's payload schema exists */
export function hasPayloadSchema(type: MessageType): boolean {
  return type in payloadSchemas
}
