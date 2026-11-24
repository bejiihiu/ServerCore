/**
 * fixed-window rate limiter, process-local. keyed counters that expire after
 * the window passes. good enough for per-telegram-id abuse protection; the
 * real hammer comes from telegram itself anyway.
 */
export type RateLimiterOptions = {
  /** max hits allowed per window per key */
  limit: number
  /** window length in ms */
  windowMs: number
}

export type RateLimitDecision = {
  allowed: boolean
  /** how many hits are left in the current window (0 when blocked) */
  remaining: number
  /** ms until the window resets, meaningful when blocked */
  retryAfterMs: number
}

type Window = {
  /** window start, ms epoch */
  startedAt: number
  hits: number
}

export class RateLimiter {
  private readonly windows = new Map<string, Window>()
  private readonly limit: number
  private readonly windowMs: number

  constructor(options: RateLimiterOptions) {
    this.limit = options.limit
    this.windowMs = options.windowMs
  }

  hit(key: string, now: number): RateLimitDecision {
    this.purge(now)

    const existing = this.windows.get(key)
    if (existing === undefined || now - existing.startedAt >= this.windowMs) {
      this.windows.set(key, { startedAt: now, hits: 1 })
      return { allowed: true, remaining: this.limit - 1, retryAfterMs: 0 }
    }

    existing.hits += 1
    if (existing.hits > this.limit) {
      return { allowed: false, remaining: 0, retryAfterMs: existing.startedAt + this.windowMs - now }
    }
    return { allowed: true, remaining: this.limit - existing.hits, retryAfterMs: 0 }
  }

  private purge(now: number): void {
    for (const [key, window] of this.windows) {
      if (now - window.startedAt >= this.windowMs) this.windows.delete(key)
    }
  }

  get size(): number {
    return this.windows.size
  }
}
