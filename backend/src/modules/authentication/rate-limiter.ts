/**
 * sliding window rate limiter, in-memory. tracks hit timestamps per key and
 * answers whether another hit fits the budget. one instance per bucket
 * (per-player issuance, per-telegram prompts, per-telegram approval attempts).
 */
export type SlidingWindowConfig = {
  maxEvents: number
  windowMs: number
}

export class SlidingWindowLimiter {
  private readonly hits = new Map<string, number[]>()

  constructor(private readonly config: SlidingWindowConfig) {}

  /**
   * records a hit for the key. false means over budget: the hit is NOT
   * recorded, so a rejected attempt costs nothing.
   */
  tryAcquire(key: string, now: number): boolean {
    const fresh = this.freshHits(key, now)
    if (fresh.length >= this.config.maxEvents) {
      this.hits.set(key, fresh)
      return false
    }
    fresh.push(now)
    this.hits.set(key, fresh)
    return true
  }

  /** drops keys with no live hits, keeps the map from growing forever */
  prune(now: number): void {
    for (const [key, hits] of this.hits) {
      const fresh = hits.filter((t) => t > now - this.config.windowMs)
      if (fresh.length === 0) this.hits.delete(key)
      else this.hits.set(key, fresh)
    }
  }

  /** test/ops visibility: how many keys currently hold live hits */
  get trackedKeys(): number {
    return this.hits.size
  }

  private freshHits(key: string, now: number): number[] {
    const cutoff = now - this.config.windowMs
    return (this.hits.get(key) ?? []).filter((t) => t > cutoff)
  }
}
