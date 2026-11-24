import type { Envelope, PayloadOf, ServerState } from "../protocol/message-types.js"
import type { HandlerRegistry } from "./registry.js"
import type { Logger } from "../../logging/logger.js"

export type ServerRuntimeStatus = {
  serverId: string
  onlinePlayers: number
  maxPlayers: number
  tps?: number
  version?: string
  state?: ServerState
  detail?: string
  /** last frame said flectonepulse owns telegram announcements for this server */
  telegramSuppressed?: boolean
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

export type LifecycleHooks = {
  /** fires only when the explicit ops state actually changes, not on every status frame */
  onStateChange?: (
    serverId: string,
    state: ServerState,
    previous: ServerState | undefined,
    options?: { telegramSuppressed?: boolean },
  ) => void
  /** player lifecycle events, consumed by the authentication module */
  onPlayerJoined?: (event: { minecraftUuid: string; username: string; serverId: string }) => void
  onPlayerLeft?: (event: { minecraftUuid: string; username?: string; reason: string }) => void
  onGuestStarted?: (event: { minecraftUuid: string; username: string; sessionId: string }, serverId: string) => void
  onGuestEnded?: (event: { minecraftUuid: string; sessionId: string; reason: string }) => void
}

/** wires the lifecycle handlers that exist at skeleton stage: presence tracking */
export function registerLifecycleHandlers(
  registry: HandlerRegistry,
  directory: ServerDirectory,
  logger: Logger,
  hooks: LifecycleHooks = {},
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
      state: extra.state ?? existing?.state,
      detail: extra.detail ?? existing?.detail,
      telegramSuppressed: extra.telegramSuppressed ?? existing?.telegramSuppressed,
      lastSeenAt: Date.now(),
    })
  }

  registry.register("SERVER_STATUS", (envelope: Envelope<"SERVER_STATUS">, connection) => {
    const { state, detail, telegramSuppressed } = envelope.payload
    const previousState = directory.get(connection.serverId)?.state
    mergeCounts(connection.serverId, envelope.payload, {
      version: envelope.payload.version,
      state,
      detail,
      telegramSuppressed,
    })
    if (state !== undefined && previousState !== state) {
      // status frames fire on every heartbeat-style push; only a real state
      // flip is interesting for notifications
      logger.info({ serverId: connection.serverId, state, previous: previousState }, "server state changed")
      hooks.onStateChange?.(connection.serverId, state, previousState, { telegramSuppressed })
    }
  })

  registry.register("SERVER_HEARTBEAT", (envelope: Envelope<"SERVER_HEARTBEAT">, connection) => {
    mergeCounts(connection.serverId, envelope.payload)
  })

  registry.register("PLAYER_JOINED", (envelope: Envelope<"PLAYER_JOINED">, connection) => {
    logger.info({ uuid: envelope.payload.minecraftUuid, username: envelope.payload.username }, "player joined")
    hooks.onPlayerJoined?.({
      minecraftUuid: envelope.payload.minecraftUuid,
      username: envelope.payload.username,
      serverId: connection.serverId,
    })
  })

  registry.register("PLAYER_LEFT", (envelope: Envelope<"PLAYER_LEFT">) => {
    logger.info({ uuid: envelope.payload.minecraftUuid, reason: envelope.payload.reason }, "player left")
    hooks.onPlayerLeft?.({
      minecraftUuid: envelope.payload.minecraftUuid,
      username: envelope.payload.username,
      reason: envelope.payload.reason,
    })
  })

  registry.register("GUEST_STARTED", (envelope: Envelope<"GUEST_STARTED">, connection) => {
    logger.info(
      { uuid: envelope.payload.minecraftUuid, sessionId: envelope.payload.sessionId },
      "guest session started",
    )
    hooks.onGuestStarted?.(envelope.payload, connection.serverId)
  })

  registry.register("GUEST_ENDED", (envelope: Envelope<"GUEST_ENDED">) => {
    logger.info(
      { uuid: envelope.payload.minecraftUuid, sessionId: envelope.payload.sessionId, reason: envelope.payload.reason },
      "guest session ended",
    )
    hooks.onGuestEnded?.(envelope.payload)
  })
}

export type { PayloadOf }
