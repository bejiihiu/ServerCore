/**
 * data retention for the atlas free tier (M0, 512 mb): the backend is the
 * sole db owner, so it is also the sole janitor. every purge target is a
 * port implemented by mongo for real and by in-memory fakes in tests.
 */

import { MS_PER_DAY, MS_PER_MINUTE } from "../../utils/time.js"

export type RetentionReport = {
  auditLogsDeleted: number
  serverEventsDeleted: number
  sessionsDeleted: number
  guestSnapshotsDeleted: number
  votingMessagesDeleted: number
  groupInvitesDeleted: number
  votesDeleted: number
  applicationsDeleted: number
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
   * from anymore. the auth phase drops snapshots on session completion; this
   * sweep catches everything that would otherwise accumulate forever
   */
  deleteOrphanedOlderThan(cutoff: Date): Promise<number>
}

/**
 * voting keyboards are removed when a voting closes, but a crash between the
 * decision and that cleanup leaves rows behind — and an application purged
 * later orphans its messages too. a message is dead once its application is
 * gone or was decided longer ago than the cutoff; live votings always survive.
 */export interface VotingMessagePurgePort {
  deleteDeadOlderThan(cutoff: Date): Promise<number>
}

/**
 * invites are one-time and short-lived (the telegram link itself dies within
 * hours). revoked links and links that expired unused are dead weight, so
 * they are collected after `inviteDays`. used invites stay as evidence of how
 * a member got in for `usedInviteDays`, then go too.
 */
export interface GroupInvitePurgePort {
  deleteDeadOlderThan(cutoff: Date, usedCutoff: Date): Promise<number>
}

/**
 * votes only matter while a voting is being counted. once the application is
 * decided the tally lives on the application row, so votes of old decisions
 * (and votes whose application row is already gone) are dropped.
 */
export interface VotePurgePort {
  deleteDecidedOlderThan(cutoff: Date): Promise<number>
}

/**
 * rejected/expired applications are dead ends: kept a year for the record,
 * then dropped. approved ones are left alone — they are the admission record
 * behind an active account, and their count is bounded by the player base.
 */
export interface ApplicationPurgePort {
  deleteClosedOlderThan(cutoff: Date): Promise<number>
}

export type RetentionOptions = {
  /** audit_logs + server_events lifetime, days */
  auditDays: number
  /** closed sessions lifetime, days */
  sessionDays: number
  /** voting_messages + votes of decided applications lifetime, days */
  votingDays: number
  /** revoked invites and invites that expired unused, days */
  inviteDays: number
  /** used invites kept as join evidence, days */
  usedInviteDays: number
  /** rejected/expired applications lifetime, days */
  applicationDays: number
  /** how often the cleanup runs, ms (default: once a day) */
  intervalMs?: number
  /** first run delay, ms (default: a minute after boot) */
  initialDelayMs?: number
}

export const DEFAULT_RETENTION: RetentionOptions = {
  auditDays: 90,
  sessionDays: 60,
  votingDays: 90,
  inviteDays: 30,
  usedInviteDays: 365,
  applicationDays: 365,
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
  private readonly votingMessages: VotingMessagePurgePort
  private readonly invites: GroupInvitePurgePort
  private readonly votes: VotePurgePort
  private readonly applications: ApplicationPurgePort
  private readonly options: RetentionOptions
  private readonly logger: {
    info(obj: unknown, msg: string): void
    warn(obj: unknown, msg: string): void
  }
  private timer: ReturnType<typeof setInterval> | null = null
  // a pass over completed collections can outrun the interval when mongo is
  // slow (more so on a busy m0); a tick that lands mid-pass is skipped instead
  // of stacking a second cleanup on the same database
  private running = false

  constructor(deps: {
    audit: AuditLogPurgePort
    serverEvents: ServerEventPurgePort
    sessions: SessionPurgePort
    snapshots: GuestSnapshotPurgePort
    votingMessages: VotingMessagePurgePort
    invites: GroupInvitePurgePort
    votes: VotePurgePort
    applications: ApplicationPurgePort
    options?: Partial<RetentionOptions>
    logger: { info(obj: unknown, msg: string): void; warn(obj: unknown, msg: string): void }
  }) {
    this.audit = deps.audit
    this.serverEvents = deps.serverEvents
    this.sessions = deps.sessions
    this.snapshots = deps.snapshots
    this.votingMessages = deps.votingMessages
    this.invites = deps.invites
    this.votes = deps.votes
    this.applications = deps.applications
    this.options = { ...DEFAULT_RETENTION, ...deps.options }
    this.logger = deps.logger
  }

  /** one full cleanup pass; every step is independent, one failure never blocks the rest */
  async runOnce(now = new Date()): Promise<RetentionReport> {
    const day = MS_PER_DAY
    const cut = (days: number): Date => new Date(now.getTime() - days * day)
    const auditCutoff = cut(this.options.auditDays)
    const sessionCutoff = cut(this.options.sessionDays)
    const votingCutoff = cut(this.options.votingDays)
    const inviteCutoff = cut(this.options.inviteDays)
    const usedInviteCutoff = cut(this.options.usedInviteDays)
    const applicationCutoff = cut(this.options.applicationDays)

    const auditLogsDeleted = await safeCount(() => this.audit.deleteOlderThan(auditCutoff))
    const serverEventsDeleted = await safeCount(() => this.serverEvents.deleteOlderThan(auditCutoff))
    const sessionsDeleted = await safeCount(() => this.sessions.deleteClosedOlderThan(sessionCutoff))
    const prunedActive = await safeCount(() => this.sessions.pruneDuplicateActive())
    const guestSnapshotsDeleted = await safeCount(() => this.snapshots.deleteOrphanedOlderThan(sessionCutoff))
    // votes/messages first, applications last: their purge queries still see
    // the decided applications they key off
    const votingMessagesDeleted = await safeCount(() => this.votingMessages.deleteDeadOlderThan(votingCutoff))
    const votesDeleted = await safeCount(() => this.votes.deleteDecidedOlderThan(votingCutoff))
    const groupInvitesDeleted = await safeCount(() =>
      this.invites.deleteDeadOlderThan(inviteCutoff, usedInviteCutoff),
    )
    const applicationsDeleted = await safeCount(() =>
      this.applications.deleteClosedOlderThan(applicationCutoff),
    )

    const report: RetentionReport = {
      auditLogsDeleted,
      serverEventsDeleted,
      sessionsDeleted: sessionsDeleted + prunedActive,
      guestSnapshotsDeleted,
      votingMessagesDeleted,
      groupInvitesDeleted,
      votesDeleted,
      applicationsDeleted,
      ranAt: now,
    }
    this.logger.info(
      {
        ...report,
        auditCutoff: auditCutoff.toISOString(),
        sessionCutoff: sessionCutoff.toISOString(),
        votingCutoff: votingCutoff.toISOString(),
        inviteCutoff: inviteCutoff.toISOString(),
        applicationCutoff: applicationCutoff.toISOString(),
      },
      "retention cleanup done",
    )
    return report
  }

  /** starts the daily cadence; returns a stop function */
  start(): () => void {
    if (this.timer !== null) return () => undefined
    const interval = this.options.intervalMs ?? MS_PER_DAY
    const initialDelay = this.options.initialDelayMs ?? MS_PER_MINUTE
    const initial = setTimeout(() => {
      void this.tick()
      this.timer = setInterval(() => {
        void this.tick()
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

  /**
   * one scheduled pass with the reentrancy guard and its own error boundary.
   * a failing or hanging pass must neither overlap the next tick nor stop the
   * cadence — the guard is always released in `finally`.
   */
  private async tick(): Promise<void> {
    if (this.running) {
      this.logger.warn({}, "retention pass still running, skipping this tick")
      return
    }
    this.running = true
    try {
      await this.runOnce()
    } catch (err: unknown) {
      this.logger.warn({ err }, "retention run failed")
    } finally {
      this.running = false
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
