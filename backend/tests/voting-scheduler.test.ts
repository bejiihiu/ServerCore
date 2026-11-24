import { describe, expect, test } from "bun:test"
import { VotingScheduler } from "../src/services/voting-scheduler.js"
import { silentLogger } from "../src/websocket/handlers/registry.js"

type Deferred = { promise: Promise<void>; resolve: () => void }

function deferred(): Deferred {
  let resolve!: () => void
  const promise = new Promise<void>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

function fakeDeps(decideExpired: () => Promise<{ decisions: []; extended: number }>) {
  return {
    voting: { decideExpired, getApplicationById: async () => null } as never,
    group: { issueInvite: async () => ({ ok: true, invite: {} }) } as never,
    logger: silentLogger(),
    intervalMs: 60_000,
  }
}

describe("voting scheduler", () => {
  test("runOnce never overlaps itself", async () => {
    // a sweep slower than the interval must skip ticks: a concurrent sweep
    // would decide the same voting twice (double invites, double dms)
    const gate = deferred()
    let sweeps = 0
    const deps = fakeDeps(async () => {
      sweeps += 1
      await gate.promise
      return { decisions: [], extended: 0 }
    })
    const scheduler = new VotingScheduler(deps)

    const first = scheduler.runOnce()
    const second = await scheduler.runOnce() // must skip, not queue up
    expect(second).toBeUndefined()
    expect(sweeps).toBe(1)

    gate.resolve()
    await first
    expect(sweeps).toBe(1)

    // once the slow sweep finished, the next tick runs normally
    await scheduler.runOnce()
    expect(sweeps).toBe(2)
  })

  test("a failing sweep releases the reentrancy guard", async () => {
    let sweeps = 0
    const deps = fakeDeps(async () => {
      sweeps += 1
      throw new Error("mongo down")
    })
    const scheduler = new VotingScheduler(deps)

    await scheduler.runOnce()
    await scheduler.runOnce()
    expect(sweeps).toBe(2)
  })
})
