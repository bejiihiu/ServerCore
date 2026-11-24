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
 */
export class HandlerRegistry {
  private readonly handlers = new Map<MessageType, MessageHandler>()
  private readonly logger: pino.Logger

  constructor(logger: pino.Logger) {
    this.logger = logger
  }

  register<T extends MessageType>(type: T, handler: MessageHandler<T>): void {
    if (this.handlers.has(type)) {
      throw new Error(`handler for ${type} already registered`)
    }
    this.handlers.set(type, handler as MessageHandler)
  }

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
