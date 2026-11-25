import { broadcastToAll, type Gateway } from "../websocket/gateway.js"
import type { ServerDirectory } from "../websocket/handlers/server-lifecycle.js"
import type { Logger } from "../logging/logger.js"
import type { PayloadOf } from "../websocket/protocol/message-types.js"

export type NetworkSnapshot = PayloadOf<"NETWORK_SNAPSHOT">

/**
 * the network view the gateway can answer right now: one row per server it
 * has heard from, the age of that server's last frame, and the total load.
 *
 * the directory is the only source. entries survive a disconnect (the offline
 * watcher needs them), so a server that went away keeps showing up with a
 * growing age — that is exactly the signal an in-game /netbar needs to say
 * "1 down" instead of quietly dropping a server from the count.
 */
export function buildNetworkSnapshot(directory: ServerDirectory, now: number): NetworkSnapshot {
  const servers = directory
    .list()
    .map((server) => ({
      serverId: server.serverId,
      onlinePlayers: server.onlinePlayers,
      maxPlayers: server.maxPlayers,
      lastSeenAgeMs: Math.max(0, now - server.lastSeenAt),
    }))
    // stable order: the plugin renders this list and a reshuffled frame would
    // read as a change even when nothing moved
    .sort((left, right) => left.serverId.localeCompare(right.serverId))

  return {
    generatedAt: now,
    playersOnline: servers.reduce((sum, server) => sum + server.onlinePlayers, 0),
    servers,
  }
}

/**
 * pushes the aggregate on the heartbeat cycle, with no timer of its own.
 *
 * every SERVER_HEARTBEAT already refreshes the directory, so the freshest
 * moment to publish is right after one lands. a fleet that heartbeats at the
 * same second would otherwise send N nearly identical frames; [minIntervalMs]
 * collapses them into one push per window, and a skipped heartbeat is
 * harmless because the next one carries the same or newer data.
 */
export class NetworkSnapshotBroadcaster {
  private lastPushAt = 0

  constructor(
    private readonly gateway: Gateway,
    private readonly directory: ServerDirectory,
    private readonly logger: Logger,
    private readonly minIntervalMs = 1_000,
    private readonly now: () => number = Date.now,
  ) {}

  /** call after a heartbeat has been folded into the directory */
  onHeartbeat(serverId: string): void {
    const at = this.now()
    if (at - this.lastPushAt < this.minIntervalMs) return
    this.lastPushAt = at
    try {
      broadcastToAll(this.gateway, this.logger, "NETWORK_SNAPSHOT", buildNetworkSnapshot(this.directory, at))
      this.logger.debug({ serverId, servers: this.directory.list().length }, "network snapshot pushed")
    } catch (error) {
      // the bar is a convenience surface: a broken push must never abort the
      // heartbeat handler that produced it
      this.logger.warn({ err: error, serverId }, "network snapshot push failed")
    }
  }
}
