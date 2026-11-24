import type { Envelope } from "../protocol/message-types.js"
import type { ServerConnection } from "../connection.js"
import type { HandlerRegistry } from "./registry.js"
import type { Logger } from "../../logging/logger.js"

export type ServerRuntimeStatus = {
  serverId: string
  onlinePlayers: number
  maxPlayers: number
  tps?: number
  version?: string
  lastSeenAt: number
}

/**
 * tracks what the gateway knows about connected game servers. handlers here
 * keep the directory fresh; modules read from it instead of guessing which
 * servers are alive.
 */
export class ServerDirectory {
  private readonly servers = new Map<string, ServerRuntimeStatus>()

  update(status: ServerRuntimeStatus): void {
    this.servers.set(status.serverId, status)
  }

  get(serverId: string): ServerRuntimeStatus | undefined {
    return this.servers.get(serverId)
  }

  remove(serverId: string): void {
    this.servers.delete(serverId)
  }

  list(): ServerRuntimeStatus[] {
    return [...this.servers.values()]
  }
}

/** wires the lifecycle handlers that exist at skeleton stage: presence tracking */
export function registerLifecycleHandlers(
  registry: HandlerRegistry,
  directory: ServerDirectory,
  logger: Logger,
): void {
  const upsert = (envelope: Envelope, connection: ServerConnection, extra: Partial<ServerRuntimeStatus>): void => {
    const payload = envelope.payload as Partial<ServerRuntimeStatus>
    const existing = directory.get(connection.serverId)
    directory.update({
      serverId: connection.serverId,
      onlinePlayers: payload.onlinePlayers ?? existing?.onlinePlayers ?? 0,
      maxPlayers: payload.maxPlayers ?? existing?.maxPlayers ?? 0,
      tps: payload.tps ?? existing?.tps,
      version: payload.version ?? existing?.version,
      lastSeenAt: Date.now(),
      ...extra,
    })
  }

  registry.register("SERVER_STATUS", (envelope, connection) => {
    upsert(envelope, connection, {})
    logger.debug({ serverId: connection.serverId }, "server status updated")
  })

  registry.register("SERVER_HEARTBEAT", (envelope, connection) => {
    upsert(envelope, connection, {})
  })

  registry.register("SERVER_HELLO", (_envelope, connection) => {
    const existing = directory.get(connection.serverId)
    directory.update({
      serverId: connection.serverId,
      onlinePlayers: existing?.onlinePlayers ?? 0,
      maxPlayers: existing?.maxPlayers ?? 0,
      lastSeenAt: Date.now(),
    })
    logger.info({ serverId: connection.serverId }, "server directory entry created")
  })

  registry.register("PLAYER_JOINED", (envelope) => {
    logger.info({ uuid: envelope.payload.minecraftUuid, username: envelope.payload.username }, "player joined")
  })

  registry.register("PLAYER_LEFT", (envelope) => {
    logger.info({ uuid: envelope.payload.minecraftUuid, reason: envelope.payload.reason }, "player left")
  })

  registry.register("GUEST_STARTED", (envelope) => {
    logger.info(
      { uuid: envelope.payload.minecraftUuid, sessionId: envelope.payload.sessionId },
      "guest session started",
    )
  })

  registry.register("GUEST_ENDED", (envelope) => {
    logger.info(
      { uuid: envelope.payload.minecraftUuid, sessionId: envelope.payload.sessionId, reason: envelope.payload.reason },
      "guest session ended",
    )
  })
}
