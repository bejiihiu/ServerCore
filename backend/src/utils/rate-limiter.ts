/**
 * process-local rate limiter, keyed counters with a sliding window.
 *
 * every acquisition stores its own timestamp and expires windowMs later, so a
 * burst spread across a window boundary cannot double the budget the way a
 * fixed window would. good enough for per-telegram-id abuse protection; the
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
  /** ms until the oldest live hit expires, meaningful when blocked */
  retryAfterMs: number
}

export class RateLimiter {
  private readonly windows = new Map<string, number[]>()

  constructor(private readonly options: RateLimiterOptions) {}

  /**
   * records a hit for the key and reports the budget. a blocked hit is not
   * recorded, so a rejected attempt costs nothing.
   */
  hit(key: string, now: number): RateLimitDecision {
    this.prune(now)
    const live = this.windows.get(key) ?? []
    if (live.length >= this.options.limit) {
      const oldest = live[0] ?? now
      return { allowed: false, remaining: 0, retryAfterMs: Math.max(0, oldest + this.options.windowMs - now) }
    }
    live.push(now)
    this.windows.set(key, live)
    return { allowed: true, remaining: this.options.limit - live.length, retryAfterMs: 0 }
  }

  /** boolean form for call sites that only branch on the verdict */
  tryAcquire(key: string, now: number): boolean {
    return this.hit(key, now).allowed
  }

  /** drops keys with no live hits, keeps the map from growing forever */
  prune(now: number): void {
    const cutoff = now - this.options.windowMs
    for (const [key, hits] of this.windows) {
      const live = hits.filter((t) => t > cutoff)
      if (live.length === 0) this.windows.delete(key)
      else this.windows.set(key, live)
    }
  }

  get size(): number {
    return this.windows.size
  }
}
