import { describe, expect, test } from "bun:test"
import { RetentionService, type RetentionOptions, type RetentionReport } from "../src/modules/retention/retention-service.js"

/** recording fakes; deleteX returns how many records would match */
class FakePurges {
  auditDeleted = 0
  eventsDeleted = 0
  sessionsDeleted = 0
  prunedDeleted = 0
  snapshotsDeleted = 0
  votingMessagesDeleted = 0
  votesDeleted = 0
  invitesDeleted = 0
  applicationsDeleted = 0
  failAudit = false

  readonly auditPort = {
    deleteOlderThan: async (_cutoff?: Date): Promise<number> => {
      if (this.failAudit) throw new Error("mongo gone")
      this.auditDeleted += 1
      return this.auditDeleted
    },
  }
  readonly eventsPort = {
    deleteOlderThan: async (_cutoff?: Date): Promise<number> => {
      this.eventsDeleted += 1
      return this.eventsDeleted
    },
  }
  readonly sessionsPort = {
    deleteClosedOlderThan: async (_cutoff?: Date): Promise<number> => {
      this.sessionsDeleted += 1
      return this.sessionsDeleted
    },
    pruneDuplicateActive: async (): Promise<number> => {
      this.prunedDeleted += 1
      return this.prunedDeleted
    },
  }
  readonly snapshotsPort = {
    deleteOrphanedOlderThan: async (): Promise<number> => {
      this.snapshotsDeleted += 1
      return this.snapshotsDeleted
    },
    deleteBySessionId: async (): Promise<number> => 1,
  }
  readonly votingMessagesPort = {
    deleteDeadOlderThan: async (_cutoff?: Date): Promise<number> => {
      this.votingMessagesDeleted += 1
      return this.votingMessagesDeleted
    },
  }
  readonly votesPort = {
    deleteDecidedOlderThan: async (_cutoff?: Date): Promise<number> => {
      this.votesDeleted += 1
      return this.votesDeleted
    },
  }
  readonly invitesPort = {
    deleteDeadOlderThan: async (_cutoff?: Date, _usedCutoff?: Date): Promise<number> => {
      this.invitesDeleted += 1
      return this.invitesDeleted
    },
  }
  readonly applicationsPort = {
    deleteClosedOlderThan: async (_cutoff?: Date): Promise<number> => {
      this.applicationsDeleted += 1
      return this.applicationsDeleted
    },
  }
}

const silent = {
  info: (_obj: unknown, _msg: string) => undefined,
  warn: (_obj: unknown, _msg: string) => undefined,
}

function buildService(purges: FakePurges, options: Partial<RetentionOptions> = {}): RetentionService {
  return new RetentionService({
    audit: purges.auditPort,
    serverEvents: purges.eventsPort,
    sessions: purges.sessionsPort,
    snapshots: purges.snapshotsPort,
    votingMessages: purges.votingMessagesPort,
    invites: purges.invitesPort,
    votes: purges.votesPort,
    applications: purges.applicationsPort,
    options,
    logger: silent,
  })
}

describe("retention service", () => {
  test("a full pass hits every purge target and logs the summary", async () => {
    const purges = new FakePurges()
    const service = buildService(purges)
    const report = await service.runOnce(new Date("2026-11-24T09:00:00Z"))
    expect(report.auditLogsDeleted).toBeGreaterThan(0)
    expect(report.serverEventsDeleted).toBeGreaterThan(0)
    expect(report.sessionsDeleted).toBeGreaterThan(0)
    expect(report.guestSnapshotsDeleted).toBeGreaterThan(0)
    expect(report.votingMessagesDeleted).toBeGreaterThan(0)
    expect(report.votesDeleted).toBeGreaterThan(0)
    expect(report.groupInvitesDeleted).toBeGreaterThan(0)
    expect(report.applicationsDeleted).toBeGreaterThan(0)
  })

  test("cutoffs derive from the configured retention windows", async () => {
    const purges = new FakePurges()
    // captured lists: ts narrowing can't see assignments inside async lambdas,
    // a plain array keeps the values observable without casts
    const seenAuditCutoffs: Date[] = []
    const seenSessionCutoffs: Date[] = []
    purges.auditPort.deleteOlderThan = async (cutoff?: Date): Promise<number> => {
      if (cutoff !== undefined) seenAuditCutoffs.push(cutoff)
      return 0
    }
    purges.sessionsPort.deleteClosedOlderThan = async (cutoff?: Date): Promise<number> => {
      if (cutoff !== undefined) seenSessionCutoffs.push(cutoff)
      return 0
    }
    const service = new RetentionService({
      audit: purges.auditPort,
      serverEvents: purges.eventsPort,
      sessions: purges.sessionsPort,
      snapshots: purges.snapshotsPort,
      votingMessages: purges.votingMessagesPort,
      invites: purges.invitesPort,
      votes: purges.votesPort,
      applications: purges.applicationsPort,
      options: { auditDays: 90, sessionDays: 60 },
      logger: silent,
    })
    const now = new Date("2026-11-24T09:00:00Z")
    await service.runOnce(now)
    // the service always passes a definite cutoff: exactly one call each
    expect(seenAuditCutoffs.length).toBe(1)
    expect(seenSessionCutoffs.length).toBe(1)
    // 2026-08-26 and 2026-09-25 respectively
    expect(seenAuditCutoffs[0]?.toISOString()).toBe(new Date(now.getTime() - 90 * 86_400_000).toISOString())
    expect(seenSessionCutoffs[0]?.toISOString()).toBe(new Date(now.getTime() - 60 * 86_400_000).toISOString())
  })

  test("dead-data cutoffs follow the defaults: votes 90d, invites 30d, used invites 1y, applications 1y", async () => {
    const purges = new FakePurges()
    const votingCutoffs: Date[] = []
    const inviteCutoffs: Date[] = []
    const usedInviteCutoffs: Date[] = []
    const applicationCutoffs: Date[] = []
    purges.votesPort.deleteDecidedOlderThan = async (cutoff?: Date): Promise<number> => {
      if (cutoff !== undefined) votingCutoffs.push(cutoff)
      return 0
    }
    purges.invitesPort.deleteDeadOlderThan = async (cutoff?: Date, usedCutoff?: Date): Promise<number> => {
      if (cutoff !== undefined && usedCutoff !== undefined) {
        inviteCutoffs.push(cutoff)
        usedInviteCutoffs.push(usedCutoff)
      }
      return 0
    }
    purges.applicationsPort.deleteClosedOlderThan = async (cutoff?: Date): Promise<number> => {
      if (cutoff !== undefined) applicationCutoffs.push(cutoff)
      return 0
    }
    const service = buildService(purges)

    const now = new Date("2026-11-24T09:00:00Z")
    await service.runOnce(now)
    const daysAgo = (days: number): string => new Date(now.getTime() - days * 86_400_000).toISOString()

    expect(votingCutoffs[0]?.toISOString()).toBe(daysAgo(90))
    expect(inviteCutoffs[0]?.toISOString()).toBe(daysAgo(30))
    expect(usedInviteCutoffs[0]?.toISOString()).toBe(daysAgo(365))
    expect(applicationCutoffs[0]?.toISOString()).toBe(daysAgo(365))
  })

  test("one failing purge target never blocks the others", async () => {
    const purges = new FakePurges()
    purges.failAudit = true
    const service = buildService(purges)
    const report: RetentionReport = await service.runOnce()
    expect(report.auditLogsDeleted).toBe(0)
    expect(report.serverEventsDeleted).toBeGreaterThan(0)
    expect(report.sessionsDeleted).toBeGreaterThan(0)
  })

  test("start schedules the daily cadence and stop cancels it", async () => {
    const purges = new FakePurges()
    const service = buildService(purges, { initialDelayMs: 20, intervalMs: 30 })
    const stop = service.start()
    await new Promise((resolve) => setTimeout(resolve, 120))
    expect(purges.auditDeleted).toBeGreaterThanOrEqual(2)
    stop()
    const afterStop = purges.auditDeleted
    await new Promise((resolve) => setTimeout(resolve, 80))
    expect(purges.auditDeleted).toBe(afterStop)
  })

  test("double start is a no-op", () => {
    const purges = new FakePurges()
    const service = buildService(purges, { initialDelayMs: 60_000 })
    const stop1 = service.start()
    const stop2 = service.start()
    stop1()
    stop2()
  })
})
