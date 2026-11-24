import type { Gateway } from "../../websocket/gateway.js"
import type { Logger } from "../../logging/logger.js"

export type OwnershipProbeResult =
  | { ok: true; sessionId: string | null }
  | { ok: false; reason: "SERVER_OFFLINE" | "NOT_ONLINE" | "TIMEOUT" }

export type OwnershipVerifier = {
  /**
   * asks every connected game server whether this uuid has a live session
   * right now. one positive answer is enough — the plugin only counts players
   * it has seen join, so a yes is proof the person at the keyboard controls
   * that minecraft account.
   */
  verify(minecraftUuid: string, options?: { timeoutMs?: number }): Promise<OwnershipProbeResult>
}

const DEFAULT_PROBE_TIMEOUT_MS = 8_000

type PendingProbe = {
  readonly correlationId: string
  /** servers we still expect an answer from */
  outstanding: number
  readonly timer: ReturnType<typeof setTimeout>
  readonly resolve: (result: OwnershipProbeResult) => void
}

/**
 * round-trip probe over the gateway. sends SESSION_CHECK to each connected
 * server with a shared correlation id; a yes resolves immediately, when every
 * server has answered no (or the deadline passes) the probe fails.
 */
export class GatewayOwnershipVerifier implements OwnershipVerifier {
  private readonly gateway: Gateway
  private readonly logger: Logger
  private readonly defaultTimeoutMs: number
  private readonly pending = new Map<string, PendingProbe>()

  constructor(gateway: Gateway, logger: Logger, defaultTimeoutMs = DEFAULT_PROBE_TIMEOUT_MS) {
    this.gateway = gateway
    this.logger = logger
    this.defaultTimeoutMs = defaultTimeoutMs
  }

  async verify(minecraftUuid: string, options?: { timeoutMs?: number }): Promise<OwnershipProbeResult> {
    const servers = this.gateway.connectedServers
    if (servers.length === 0) return { ok: false, reason: "SERVER_OFFLINE" }

    const timeoutMs = options?.timeoutMs ?? this.defaultTimeoutMs
    const correlationId = `probe-${crypto.randomUUID()}`

    const probe = await new Promise<OwnershipProbeResult>((resolve) => {
      const pendingProbe: PendingProbe = {
        correlationId,
        outstanding: servers.length,
        timer: setTimeout(() => {
          this.pending.delete(correlationId)
          resolve({ ok: false, reason: "TIMEOUT" })
        }, timeoutMs),
        resolve,
      }
      this.pending.set(correlationId, pendingProbe)

      // fan out to every connected server; single-server is the norm,
      // multi-server just needs one yes
      for (const serverId of servers) {
        void this.gateway
          .sendTo(serverId, "SESSION_CHECK", { minecraftUuid }, { correlationId, awaitAck: true })
          .catch((err: unknown) => {
            // a dead connection counts as answered-no rather than hanging
            this.logger.warn({ err, serverId }, "session probe send failed")
            this.onProbeAnswer(correlationId, false, null)
          })
      }
    })

    return probe
  }

  /** wired to the SESSION_CHECK_RESULT handler; unknown correlation ids are dropped */
  onProbeAnswer(correlationId: string | undefined, online: boolean, sessionId: string | null): void {
    if (correlationId === undefined) return
    const probe = this.pending.get(correlationId)
    if (!probe) return

    if (online) {
      clearTimeout(probe.timer)
      this.pending.delete(correlationId)
      probe.resolve({ ok: true, sessionId })
      return
    }

    probe.outstanding -= 1
    if (probe.outstanding <= 0) {
      clearTimeout(probe.timer)
      this.pending.delete(correlationId)
      probe.resolve({ ok: false, reason: "NOT_ONLINE" })
    }
  }

  get pendingCount(): number {
    return this.pending.size
  }
}
