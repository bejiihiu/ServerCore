import { randomHex } from "../../utils/crypto.js"

/**
 * replay protection: remembers every message id we accepted per server and
 * rejects duplicates. entries expire after a ttl slightly larger than the
 * timestamp window, after which the timestamp check alone is enough to reject
 * old replays.
 */
export class ReplayGuard {
  private readonly seen = new Map<string, number>()
  private readonly ttlMs: number

  constructor(ttlMs: number) {
    this.ttlMs = ttlMs
  }

  /** true if this id was never seen before (and is now recorded) */
  checkAndRecord(id: string, now: number): boolean {
    this.purge(now)
    if (this.seen.has(id)) return false
    this.seen.set(id, now)
    return true
  }

  /** true if the id is currently known as accepted */
  has(id: string): boolean {
    return this.seen.has(id)
  }

  private purge(now: number): void {
    for (const [key, at] of this.seen) {
      if (now - at > this.ttlMs) this.seen.delete(key)
    }
  }

  get size(): number {
    return this.seen.size
  }
}

/** new message id for outbound envelopes */
export function newMessageId(): string {
  return randomHex(12)
}
