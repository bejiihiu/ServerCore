import type { ServerDirectory } from "../websocket/handlers/server-lifecycle.js"
import type { Logger } from "../logging/logger.js"
import { MS_PER_MINUTE, MS_PER_SECOND } from "../utils/time.js"

/** minimal gateway surface the watcher needs */
export type WatchedGateway = {
  readonly connectedServers: readonly string[]
}

export type OfflineAlert = {
  serverId: string
  /** how long the server has been unreachable, minutes (floored) */
  minutesUnreachable: number
  lastSeenAt: number
}

/**
 * crash detection: a game server that dropped the websocket and did not come
 * back within the threshold is presumed offline (crash or host outage). each
 * server is alerted exactly once per outage; a reconnect re-arms the alert.
 */
export class OfflineWatcher {
  private readonly directory: ServerDirectory
  private readonly gateway: WatchedGateway
  private readonly logger: Logger
  private readonly thresholdMs: number
  private readonly now: () => number
  private readonly alerted = new Set<string>()
  private timer: ReturnType<typeof setInterval> | null = null

  /** last alert emitted by check(), consumed by the wiring in app.ts */
  onAlert: ((alert: OfflineAlert) => void) | null = null
  /** fires when a previously-alerted server shows up again */
  onReconnect: ((serverId: string) => void) | null = null

  constructor(deps: {
    directory: ServerDirectory
    gateway: WatchedGateway
    logger: Logger
    /** alert when unreachable for at least this long */
    thresholdMs: number
    now?: () => number
  }) {
    this.directory = deps.directory
    this.gateway = deps.gateway
    this.logger = deps.logger
    this.thresholdMs = deps.thresholdMs
    this.now = deps.now ?? (() => Date.now())
  }

  start(intervalMs = 30 * MS_PER_SECOND): void {
    if (this.timer !== null) return
    this.timer = setInterval(() => {
      try {
        this.check()
      } catch (error) {
        this.logger.error({ err: error }, "offline watcher check failed")
      }
    }, intervalMs)
  }

  stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer)
      this.timer = null
    }
  }

  /**
   * one sweep over the known servers. pure enough to call from tests.
   * returns the alerts emitted in this sweep.
   */
  check(): OfflineAlert[] {
    const connected = new Set(this.gateway.connectedServers)
    const now = this.now()
    const alerts: OfflineAlert[] = []

    for (const server of this.directory.list()) {
      if (connected.has(server.serverId)) {
        if (this.alerted.delete(server.serverId)) {
          // the all-clear itself is logged by OpsNotifier.serverBack
          this.logger.debug({ serverId: server.serverId }, "crash alert re-armed")
          this.onReconnect?.(server.serverId)
        }
        continue
      }

      const silenceMs = now - server.lastSeenAt
      if (silenceMs < this.thresholdMs) continue
      if (this.alerted.has(server.serverId)) continue

      const minutesUnreachable = Math.floor(silenceMs / MS_PER_MINUTE)
      this.alerted.add(server.serverId)
      const alert: OfflineAlert = { serverId: server.serverId, minutesUnreachable, lastSeenAt: server.lastSeenAt }
      alerts.push(alert)
      this.onAlert?.(alert)
    }

    return alerts
  }

  /** servers currently in the alerted (presumed offline) state */
  get alertedServers(): readonly string[] {
    return [...this.alerted]
  }
}
