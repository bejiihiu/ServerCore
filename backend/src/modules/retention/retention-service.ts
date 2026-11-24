/**
 * data retention for the atlas free tier (M0, 512 mb): the backend is the
 * sole db owner, so it is also the sole janitor. every purge target is a
 * port implemented by mongo for real and by in-memory fakes in tests.
 */
export type RetentionReport = {
  auditLogsDeleted: number
  serverEventsDeleted: number
  sessionsDeleted: number
  guestSnapshotsDeleted: number
  ranAt: Date
}

/** purge ports; each returns the number of documents removed */
export interface AuditLogPurgePort {
  deleteOlderThan(cutoff: Date): Promise<number>
}

export interface ServerEventPurgePort {
  deleteOlderThan(cutoff: Date): Promise<number>
}

export interface SessionPurgePort {
  /** closed sessions (endedAt set) older than the cutoff; active ones survive */
  deleteClosedOlderThan(cutoff: Date): Promise<number>
  /**
   * keeps at most the newest active session per player, drops older stragglers
   * (e.g. leftovers from an unclean shutdown)
   */
  pruneDuplicateActive(): Promise<number>
}

export interface GuestSnapshotPurgePort {
  /**
   * snapshots whose session is over (ended/closed) and that nobody restored
   * from anymore. delete-on-session-completion is wired by the auth phase;
   * this catches everything that would otherwise accumulate forever
   */
  deleteOrphanedOlderThan(cutoff: Date): Promise<number>
  /** direct hook for the auth phase: drop the snapshot of a completing session */
  deleteBySessionId(sessionId: string): Promise<number>
}

export type RetentionOptions = {
  /** audit_logs + server_events lifetime, days */
  auditDays: number
  /** closed sessions lifetime, days */
  sessionDays: number
  /** how often the cleanup runs, ms (default: once a day) */
  intervalMs?: number
  /** first run delay, ms (default: a minute after boot) */
  initialDelayMs?: number
}

export const DEFAULT_RETENTION: RetentionOptions = {
  auditDays: 90,
  sessionDays: 60,
}

/**
 * the daily cleanup task. runOnce is the real worker (testable, loggable);
 * start wires it to a plain setInterval — backend is a single bun process,
 * no scheduler framework needed.
 */
export class RetentionService {
  private readonly audit: AuditLogPurgePort
  private readonly serverEvents: ServerEventPurgePort
  private readonly sessions: SessionPurgePort
  private readonly snapshots: GuestSnapshotPurgePort
  private readonly options: RetentionOptions
  private readonly logger: {
    info(obj: unknown, msg: string): void
    warn(obj: unknown, msg: string): void
  }
  private timer: ReturnType<typeof setInterval> | null = null

  constructor(deps: {
    audit: AuditLogPurgePort
    serverEvents: ServerEventPurgePort
    sessions: SessionPurgePort
    snapshots: GuestSnapshotPurgePort
    options?: Partial<RetentionOptions>
    logger: { info(obj: unknown, msg: string): void; warn(obj: unknown, msg: string): void }
  }) {
    this.audit = deps.audit
    this.serverEvents = deps.serverEvents
    this.sessions = deps.sessions
    this.snapshots = deps.snapshots
    this.options = { ...DEFAULT_RETENTION, ...deps.options }
    this.logger = deps.logger
  }

  /** one full cleanup pass; every step is independent, one failure never blocks the rest */
  async runOnce(now = new Date()): Promise<RetentionReport> {
    const auditCutoff = new Date(now.getTime() - this.options.auditDays * 86_400_000)
    const sessionCutoff = new Date(now.getTime() - this.options.sessionDays * 86_400_000)

    const auditLogsDeleted = await safeCount(() => this.audit.deleteOlderThan(auditCutoff))
    const serverEventsDeleted = await safeCount(() => this.serverEvents.deleteOlderThan(auditCutoff))
    const sessionsDeleted = await safeCount(() => this.sessions.deleteClosedOlderThan(sessionCutoff))
    const prunedActive = await safeCount(() => this.sessions.pruneDuplicateActive())
    const guestSnapshotsDeleted = await safeCount(() => this.snapshots.deleteOrphanedOlderThan(sessionCutoff))

    const report: RetentionReport = {
      auditLogsDeleted,
      serverEventsDeleted,
      sessionsDeleted: sessionsDeleted + prunedActive,
      guestSnapshotsDeleted,
      ranAt: now,
    }
    this.logger.info(
      {
        ...report,
        auditCutoff: auditCutoff.toISOString(),
        sessionCutoff: sessionCutoff.toISOString(),
      },
      "retention cleanup done",
    )
    return report
  }

  /** starts the daily cadence; returns a stop function */
  start(): () => void {
    if (this.timer !== null) return () => undefined
    const interval = this.options.intervalMs ?? 86_400_000
    const initialDelay = this.options.initialDelayMs ?? 60_000
    const initial = setTimeout(() => {
      void this.runOnce().catch((err: unknown) => this.logger.warn({ err }, "retention run failed"))
      this.timer = setInterval(() => {
        void this.runOnce().catch((err: unknown) => this.logger.warn({ err }, "retention run failed"))
      }, interval)
    }, initialDelay)
    // keep the timeout ref alive through the timer field dance: initial fires
    // once, then the interval takes over
    void initial
    return () => {
      clearTimeout(initial)
      if (this.timer !== null) {
        clearInterval(this.timer)
        this.timer = null
      }
    }
  }
}

async function safeCount(run: () => Promise<number>): Promise<number> {
  try {
    return await run()
  } catch {
    return 0
  }
}
