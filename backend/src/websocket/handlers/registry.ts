import pino from "pino"
import type { Envelope, MessageType } from "../protocol/message-types.js"
import type { ServerConnection } from "../connection.js"
import type { Logger } from "../../logging/logger.js"

export type MessageHandler = (envelope: Envelope, connection: ServerConnection) => Promise<void> | void

/**
 * routes validated envelopes to module handlers. modules register real
 * handlers here as their features land; unknown-but-valid types are logged
 * and dropped instead of exploding the connection.
 */
export class HandlerRegistry {
  private readonly handlers = new Map<MessageType, MessageHandler>()
  private readonly logger: Logger

  constructor(logger?: Logger) {
    this.logger = logger ?? silentLogger()
  }

  register(type: MessageType, handler: MessageHandler): void {
    if (this.handlers.has(type)) {
      throw new Error(`handler for ${type} already registered`)
    }
    this.handlers.set(type, handler)
  }

  async dispatch(envelope: Envelope, connection: ServerConnection): Promise<void> {
    const handler = this.handlers.get(envelope.type)
    if (handler === undefined) {
      this.logger.debug({ type: envelope.type, serverId: connection.serverId }, "no handler for message type")
      return
    }
    await handler(envelope, connection)
  }

  get registeredTypes(): MessageType[] {
    return [...this.handlers.keys()]
  }
}

function silentLogger(): Logger {
  // test-only logger, bypasses config on purpose: pino's real silent level
  return pino({ level: "silent" })
}
