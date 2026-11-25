import { signEnvelope, verifyWireMessage, type VerifyFailure } from "./protocol/signing.js"
import { newMessageId, ReplayGuard } from "./security/replay-guard.js"
import { isNoAckType, type Envelope, type MessageType, type PayloadOf } from "./protocol/message-types.js"
import type { JsonValue } from "../utils/crypto.js"
import type { Logger } from "../logging/logger.js"

/** extra data bun attaches to each server websocket */
export type GatewaySocketData = {
  serverId: string
  connectedAt: number
  connection?: ServerConnection
}

export type ConnectionOptions = {
  secret: string
  heartbeatIntervalMs: number
  heartbeatTimeoutMs: number
  maxPacketBytes: number
  replayWindowMs: number
  replayGuardTtlMs: number
}

type PendingAck = {
  resolve: (value: void) => void
  reject: (error: Error) => void
  timer: ReturnType<typeof setTimeout>
}

const MAX_BUFFERED_BYTES = 5 * 1024 * 1024
const ACK_TIMEOUT_MS = 30_000
const CLOSE_GRACE_MS = 5_000

/**
 * one authenticated game-server connection. owns signing, replay protection,
 * heartbeat and ack correlation for its socket. the handshake itself lives in
 * the gateway (pre-upgrade, see auth.ts) — by the time this object exists the
 * peer already proved it knows the per-server secret.
 */
export class ServerConnection {
  readonly serverId: string
  private readonly socket: Bun.ServerWebSocket<GatewaySocketData>
  private readonly logger: Logger
  private readonly options: ConnectionOptions
  private readonly replayGuard: ReplayGuard
  private readonly pendingAcks = new Map<string, PendingAck>()
  /** unknown wire types already logged, so a chatty new peer logs once each */
  private readonly unknownTypes = new Set<string>()

  private alive = true
  private awaitingPong = false
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private closeTimer: ReturnType<typeof setTimeout> | null = null
  private lastSeenAt: number
  private activeRoutes = 0

  constructor(serverId: string, socket: Bun.ServerWebSocket<GatewaySocketData>, options: ConnectionOptions, logger: Logger) {
    this.serverId = serverId
    this.socket = socket
    this.options = options
    this.logger = logger
    this.replayGuard = new ReplayGuard(options.replayGuardTtlMs)
    this.lastSeenAt = Date.now()
  }

  /** starts the heartbeat loop. call once right after construction. */
  start(): void {
    this.heartbeatTimer = setInterval(() => {
      if (!this.alive) return
      if (this.awaitingPong) {
        this.logger.warn({ serverId: this.serverId }, "heartbeat timeout, terminating connection")
        this.close(1001, "heartbeat timeout")
        return
      }
      this.awaitingPong = true
      this.socket.ping()
    }, this.options.heartbeatIntervalMs)
  }

  /** wired by the gateway to the socket's pong handler */
  onPong(): void {
    this.awaitingPong = false
    this.lastSeenAt = Date.now()
  }

  /** handlers that started but have not finished yet, watched by the drain */
  get inFlightHandlers(): number {
    return this.activeRoutes
  }

  /** resolves once every in-flight handler finished, bounded by the timeout */
  async drain(timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs
    while (this.activeRoutes > 0 && Date.now() < deadline) {
      await Bun.sleep(25)
    }
  }

  /**
   * handles a raw inbound frame. returns null when the frame was consumed
   * (valid + routed), or a failure when the connection must close.
   */
  handleFrame(
    raw: string,
    now: number,
    route: (envelope: Envelope, conn: ServerConnection) => Promise<void>,
  ): VerifyFailure | null {
    if (!this.alive) return { reason: "BAD_ENVELOPE", detail: "connection is closing" }

    const result = verifyWireMessage(raw, this.options.secret, now, {
      maxSkewMs: this.options.replayWindowMs,
      maxPacketBytes: this.options.maxPacketBytes,
    })
    if (!result.ok) return result.failure

    const envelope = result.envelope

    if (envelope.type === "SERVER_HELLO") {
      // hello is obsolete: auth happens pre-upgrade now, so a hello here is
      // either an old client or an attempted downgrade of the auth layer
      return { reason: "BAD_ENVELOPE", detail: "unexpected handshake after upgrade" }
    }

    const correlationId = envelope.correlationId
    if (result.known && envelope.type === "ACK" && correlationId) {
      const ack = this.pendingAcks.get(correlationId)
      if (ack) {
        clearTimeout(ack.timer)
        this.pendingAcks.delete(correlationId)
        ack.resolve()
      }
      return null
    }

    if (!this.replayGuard.checkAndRecord(envelope.id, now)) {
      // same envelope id twice = the peer replayed a frame we already
      // consumed. every frame is hmac-signed, so this is the legitimate peer
      // redelivering after a lost ack, not an attack: ack it again, process
      // nothing. killing the connection here would turn one lost ack into a
      // reconnect loop.
      this.logger.debug({ serverId: this.serverId, id: envelope.id }, "duplicate frame, acked without reprocessing")
      if (!isNoAckType(envelope.type)) {
        this.sendRaw("ACK", { ackedId: envelope.id }, envelope.id)
      }
      return null
    }

    this.lastSeenAt = now

    if (!isNoAckType(envelope.type)) {
      this.sendRaw("ACK", { ackedId: envelope.id }, envelope.id)
    }

    if (!result.known) {
      // hmac checked out, the type is just newer than this build: ack it so
      // the peer can retire its durable frame, change no state, keep the
      // socket. a newer plugin must never be able to kill the gateway.
      this.noteUnknownType(envelope.type)
      return null
    }

    // handler errors must not kill the connection — log and keep going.
    // the route is tracked so a graceful shutdown can wait for it to finish
    this.activeRoutes += 1
    void route(result.envelope, this)
      .catch((error: unknown) => {
        this.logger.error({ err: error, serverId: this.serverId, type: envelope.type }, "message handler failed")
        // the frame was already acked above, so a dead handler would otherwise
        // look like a successful write to the peer (mongo blip mid-request:
        // audit row, session write, admission step). answer the ack with a
        // correlated failure instead of silence — no close, the socket stays
        // usable and the next frame is processed normally
        void this.send("ERROR", {
          code: "HANDLER_FAILED",
          message: "backend could not process this frame",
          inReplyTo: envelope.id,
        })
      })
      .finally(() => {
        this.activeRoutes -= 1
      })

    return null
  }

  /** one warn per unknown wire type per connection, not one per frame */
  private noteUnknownType(type: string): void {
    if (this.unknownTypes.has(type)) return
    this.unknownTypes.add(type)
    this.logger.warn({ serverId: this.serverId, type }, "unknown message type, acked and ignored")
  }

  /** signs and sends an envelope, optionally awaiting the peer's ack */
  send<T extends MessageType>(
    type: T,
    payload: PayloadOf<T>,
    options: { correlationId?: string; awaitAck?: boolean } = {},
  ): Promise<void> {
    const awaitAck = options.awaitAck ?? false
    const correlationId = options.correlationId ?? (awaitAck ? newMessageId() : undefined)

    if (!awaitAck || !correlationId) {
      this.sendRaw(type, payload as JsonValue, correlationId)
      return Promise.resolve()
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

    if (this.socket.getBufferedAmount() > MAX_BUFFERED_BYTES) {
      // peer is not draining, kill it instead of ballooning memory
      this.logger.warn({ serverId: this.serverId }, "send buffer overflow, terminating")
      this.close(1013, "slow consumer")
      return
    }

    // signing can throw on a payload that has no canonical form (non-finite
    // numbers); a bad outbound payload must not escape into the frame handler
    try {
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
      this.socket.sendText(JSON.stringify(wire))
    } catch (error: unknown) {
      this.logger.warn({ err: error, serverId: this.serverId, type }, "outbound frame dropped")
    }
  }

  close(code: number, reason: string): void {
    if (!this.alive) return
    this.alive = false
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    if (this.closeTimer) clearTimeout(this.closeTimer)
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
    this.closeTimer = setTimeout(() => {
      try {
        this.socket.terminate()
      } catch {
        // already gone
      }
    }, CLOSE_GRACE_MS)
  }

  get isAlive(): boolean {
    return this.alive
  }

  get lastSeen(): number {
    return this.lastSeenAt
  }
}
