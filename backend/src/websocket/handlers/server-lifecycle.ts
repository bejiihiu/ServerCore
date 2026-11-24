import type { Envelope, PayloadOf } from "../protocol/message-types.js"
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

type PlayerCountPatch = Pick<ServerRuntimeStatus, "onlinePlayers" | "maxPlayers" | "tps">

/** wires the lifecycle handlers that exist at skeleton stage: presence tracking */
export function registerLifecycleHandlers(
  registry: HandlerRegistry,
  directory: ServerDirectory,
  logger: Logger,
): void {
  const mergeCounts = (
    serverId: string,
    patch: PlayerCountPatch,
    extra: Partial<Omit<ServerRuntimeStatus, "serverId" | "lastSeenAt">> = {},
  ): void => {
    const existing = directory.get(serverId)
    directory.update({
      serverId,
      onlinePlayers: patch.onlinePlayers ?? existing?.onlinePlayers ?? 0,
      maxPlayers: patch.maxPlayers ?? existing?.maxPlayers ?? 0,
      tps: patch.tps ?? existing?.tps,
      version: extra.version ?? existing?.version,
      lastSeenAt: Date.now(),
    })
  }

  registry.register("SERVER_STATUS", (envelope: Envelope<"SERVER_STATUS">, connection) => {
    mergeCounts(connection.serverId, envelope.payload, { version: envelope.payload.version })
    logger.debug({ serverId: connection.serverId }, "server status updated")
  })

  registry.register("SERVER_HEARTBEAT", (envelope: Envelope<"SERVER_HEARTBEAT">, connection) => {
    mergeCounts(connection.serverId, envelope.payload)
  })

  registry.register("PLAYER_JOINED", (envelope: Envelope<"PLAYER_JOINED">) => {
    logger.info({ uuid: envelope.payload.minecraftUuid, username: envelope.payload.username }, "player joined")
  })

  registry.register("PLAYER_LEFT", (envelope: Envelope<"PLAYER_LEFT">) => {
    logger.info({ uuid: envelope.payload.minecraftUuid, reason: envelope.payload.reason }, "player left")
  })

  registry.register("GUEST_STARTED", (envelope: Envelope<"GUEST_STARTED">) => {
    logger.info(
      { uuid: envelope.payload.minecraftUuid, sessionId: envelope.payload.sessionId },
      "guest session started",
    )
  })

  registry.register("GUEST_ENDED", (envelope: Envelope<"GUEST_ENDED">) => {
    logger.info(
      { uuid: envelope.payload.minecraftUuid, sessionId: envelope.payload.sessionId, reason: envelope.payload.reason },
      "guest session ended",
    )
  })
}

export type { PayloadOf }
