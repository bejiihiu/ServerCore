import type { Clock } from "./ports.js"

/**
 * simple fixed-window rate limiter, process-local. tracks how many times a
 * key acquired a slot inside the current window. used to stop questionnaire
 * spam: n starts per window per telegram account.
 */
export class RateLimiter {
  private readonly hits = new Map<string, number[]>()

  constructor(
    private readonly max: number,
    private readonly windowMs: number,
    private readonly clock: Clock,
  ) {}

  tryAcquire(key: string): boolean {
    const now = this.clock.nowMs()
    const windowStart = now - this.windowMs
    const previous = this.hits.get(key) ?? []
    const stillInWindow = previous.filter((t) => t > windowStart)
    if (stillInWindow.length >= this.max) {
      this.hits.set(key, stillInWindow)
      return false
    }
    stillInWindow.push(now)
    this.hits.set(key, stillInWindow)
    return true
  }
}
