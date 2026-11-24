import type { GatewayStats } from "../websocket/gateway.js"
import type { ServerDirectory } from "../websocket/handlers/server-lifecycle.js"

/**
 * in-memory session counters. the gateway owns server presence; session-level
 * numbers get fed here by their owning modules as those merge. the counters
 * are real state (not estimates) - zero means "no sessions registered yet",
 * which is exactly what a fresh backend knows.
 */
export class SessionCounters {
  activeSessions = 0
  guestSessions = 0
  pendingAuths = 0
}

export type ServerStatsEntry = {
  serverId: string
  state?: string
  version?: string
  onlinePlayers: number
  maxPlayers: number
  tps?: number
  activeSpecSessions?: number
  secondsSinceLastSeen: number
}

export type StatsSnapshot = {
  uptimeSeconds: number
  wsConnections: number
  wsRunning: boolean
  servers: ServerStatsEntry[]
  counters: {
    activeSessions: number
    guestSessions: number
    pendingAuths: number
  }
}

/** minimal gateway surface for stats */
export type StatsGateway = {
  readonly stats: GatewayStats
  readonly connectedServers: readonly string[]
}

/** aggregates everything the /stats admin command needs from live state */
export class StatsService {
  private readonly startedAt: number
  private readonly gateway: StatsGateway
  private readonly directory: ServerDirectory
  private readonly counters: SessionCounters

  constructor(deps: {
    startedAt: number
    gateway: StatsGateway
    directory: ServerDirectory
    counters: SessionCounters
  }) {
    this.startedAt = deps.startedAt
    this.gateway = deps.gateway
    this.directory = deps.directory
    this.counters = deps.counters
  }

  snapshot(now = Date.now()): StatsSnapshot {
    const servers: ServerStatsEntry[] = this.directory.list().map((server) => ({
      serverId: server.serverId,
      ...(server.state !== undefined ? { state: server.state } : {}),
      ...(server.version !== undefined ? { version: server.version } : {}),
      onlinePlayers: server.onlinePlayers,
      maxPlayers: server.maxPlayers,
      ...(server.tps !== undefined ? { tps: server.tps } : {}),
      ...(server.activeSpecSessions !== undefined ? { activeSpecSessions: server.activeSpecSessions } : {}),
      secondsSinceLastSeen: Math.max(0, Math.floor((now - server.lastSeenAt) / 1000)),
    }))

    return {
      uptimeSeconds: Math.max(0, Math.floor((now - this.startedAt) / 1000)),
      wsConnections: this.gateway.stats.connections,
      wsRunning: this.gateway.stats.running,
      servers,
      counters: {
        activeSessions: this.counters.activeSessions,
        guestSessions: this.counters.guestSessions,
        pendingAuths: this.counters.pendingAuths,
      },
    }
  }
}
