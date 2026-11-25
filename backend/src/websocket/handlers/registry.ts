import pino from "pino"
import type { Envelope, MessageType } from "../protocol/message-types.js"
import type { ServerConnection } from "../connection.js"

/** handler receives an envelope typed for the message type it registered for */
export type MessageHandler<T extends MessageType = MessageType> = (
  envelope: Envelope<T>,
  connection: ServerConnection,
) => Promise<void> | void

/**
 * routes validated envelopes to module handlers. modules register real
 * handlers here as they get built; valid types without a handler are logged
 * at debug and dropped — they never break the connection.
 *
 * one registry per process, owned by the composition root: every type is
 * registered exactly once from `registerAllHandlers` before the gateway starts
 * accepting frames, and `dispatch` is the only read path afterwards.
 */
export class HandlerRegistry {
  private readonly handlers = new Map<MessageType, MessageHandler>()
  private readonly logger: pino.Logger

  constructor(logger: pino.Logger) {
    this.logger = logger
  }

  /**
   * one handler per type. a second registration throws on purpose: two
   * modules claiming the same type is a wiring bug, and failing the boot beats
   * silently dropping one of them. registering after the gateway is live is
   * a race the caller owns.
   */
  register<T extends MessageType>(type: T, handler: MessageHandler<T>): void {
    if (this.handlers.has(type)) {
      throw new Error(`handler for ${type} already registered`)
    }
    this.handlers.set(type, handler as MessageHandler)
  }

  /**
   * runs the handler for the envelope's type; an unregistered type is a no-op
   * (debug log). handler rejections are not swallowed here — the connection
   * catches them, logs, and answers the frame with a correlated ERROR while
   * keeping the socket. handlers can therefore let a failed write propagate
   * instead of faking success.
   */
  async dispatch(envelope: Envelope, connection: ServerConnection): Promise<void> {
    const handler = this.handlers.get(envelope.type)
    if (!handler) {
      this.logger.debug({ type: envelope.type, serverId: connection.serverId }, "no handler for message type")
      return
    }
    await handler(envelope, connection)
  }

  get registeredTypes(): string[] {
    return [...this.handlers.keys()]
  }
}

/** silent logger for tests */
export function silentLogger(): pino.Logger {
  return pino({ level: "silent" })
}
