import type { Logger } from "../logging/logger.js"
import type { GroupService } from "./group-service.js"
import type { VotingService } from "./voting-service.js"

/**
 * real timer that expires votings. on boot it runs an immediate catch-up
 * scan, so votings that expired while the bot was down get processed; then
 * it keeps scanning on a fixed interval.
 */
export class VotingScheduler {
  private timer: ReturnType<typeof setInterval> | null = null
  // setInterval does not wait for async sweeps: a sweep slower than the
  // interval (telegram fan-out for many expired votings) must not overlap
  // itself, or the same voting gets decided twice — double invites, double dms
  private running = false

  constructor(
    private readonly deps: {
      voting: VotingService
      group: GroupService
      logger: Logger
      intervalMs: number
    },
  ) {}

  start(): void {
    if (this.timer !== null) return
    // catch-up first: votings that expired while we were offline
    void this.runOnce()
    this.timer = setInterval(() => {
      void this.runOnce()
    }, this.deps.intervalMs)
    this.deps.logger.info({ intervalMs: this.deps.intervalMs }, "voting scheduler started")
  }

  stop(): void {
    if (this.timer === null) return
    clearInterval(this.timer)
    this.timer = null
    this.deps.logger.info("voting scheduler stopped")
  }

  /** one sweep: decide every expired voting, follow up on approvals. a sweep
   * that is still running skips the next tick instead of running concurrently */
  async runOnce(): Promise<void> {
    if (this.running) return
    this.running = true
    try {
      const { decisions, extended } = await this.deps.voting.decideExpired()
      if (extended > 0) {
        this.deps.logger.info({ extended }, "votings auto-extended for missing quorum")
      }
      for (const decision of decisions) {
        this.deps.logger.info(
          {
            applicationId: decision.applicationId,
            status: decision.status,
            votesFor: decision.votesFor,
            votesAgainst: decision.votesAgainst,
          },
          "voting decided",
        )
        if (decision.status === "APPROVED") {
          const application = await this.deps.voting.getApplicationById(decision.applicationId)
          if (!application) continue
          const invite = await this.deps.group.issueInvite(application)
          if (!invite.ok) {
            this.deps.logger.warn(
              { applicationId: decision.applicationId, reason: invite.reason },
              "group invite was not issued",
            )
          }
        }
      }
    } catch (err) {
      this.deps.logger.error({ err }, "voting scheduler sweep failed")
    } finally {
      this.running = false
    }
  }
}
