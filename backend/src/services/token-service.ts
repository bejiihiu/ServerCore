import { randomToken, safeEqualHex, sha256Hex } from "../utils/crypto.js"

/** link tokens are 16 chars of [A-Za-z0-9], as the deep-link format requires */
export const LINK_TOKEN_LENGTH = 16

/** format check for a raw deep-link token, usable without any store */
export function isValidLinkTokenFormat(token: string): boolean {
  return /^[A-Za-z0-9]{16}$/.test(token)
}

export type StoredToken = {
  /** only the sha-256 of the token is ever stored, never the token itself */
  tokenHash: string
  meta: Record<string, string>
  createdAt: number
  expiresAt: number
  consumedAt: number | null
}

export interface TokenStore {
  save(record: StoredToken): Promise<void>
  /** raw lookup, no expiry/consumption filtering — the service decides */
  find(tokenHash: string): Promise<StoredToken | null>
  /**
   * marks the record consumed (atomically) and returns it with a flag saying
   * whether it had been consumed before this call; null when it never existed
   */
  consume(tokenHash: string, now: number): Promise<{ record: StoredToken; alreadyConsumed: boolean } | null>
}

export type VerifyOutcome =
  | { valid: true; meta: Record<string, string> }
  | { valid: false; reason: "BAD_FORMAT" | "NOT_FOUND" | "EXPIRED" | "CONSUMED" }

/**
 * one-time tokens for account linking and login confirmation.
 * tokens exist raw only at issuance time; storage keeps hashes.
 */
export class TokenService {
  private readonly store: TokenStore
  private readonly ttlMs: number

  constructor(store: TokenStore, ttlSeconds: number) {
    this.store = store
    this.ttlMs = ttlSeconds * 1000
  }

  /** creates a one-time token. meta travels with it (uuid, session, ...) */
  async issue(meta: Record<string, string>, now = Date.now()): Promise<{ token: string; expiresAt: number }> {
    const token = randomToken(LINK_TOKEN_LENGTH)
    await this.store.save({
      tokenHash: sha256Hex(token),
      meta,
      createdAt: now,
      expiresAt: now + this.ttlMs,
      consumedAt: null,
    })
    return { token, expiresAt: now + this.ttlMs }
  }

  async verify(token: string, now = Date.now()): Promise<VerifyOutcome> {
    if (!isValidLinkTokenFormat(token)) return { valid: false, reason: "BAD_FORMAT" }

    const record = await this.store.find(sha256Hex(token))
    if (!record) return { valid: false, reason: "NOT_FOUND" }
    if (record.expiresAt <= now) return { valid: false, reason: "EXPIRED" }
    if (record.consumedAt !== null) return { valid: false, reason: "CONSUMED" }

    return { valid: true, meta: record.meta }
  }

  /** one-time consume: verify + burn in one go */
  async redeem(token: string, now = Date.now()): Promise<VerifyOutcome> {
    if (!isValidLinkTokenFormat(token)) return { valid: false, reason: "BAD_FORMAT" }

    const result = await this.store.consume(sha256Hex(token), now)
    if (!result) return { valid: false, reason: "NOT_FOUND" }
    if (result.alreadyConsumed) return { valid: false, reason: "CONSUMED" }
    if (result.record.expiresAt <= now) return { valid: false, reason: "EXPIRED" }

    return { valid: true, meta: result.record.meta }
  }
}

/**
 * process-local store. fine for a single-instance backend; the mongo-backed
 * store replaces it without touching TokenService when persistence lands.
 */
export class InMemoryTokenStore implements TokenStore {
  private readonly tokens = new Map<string, StoredToken>()

  async save(record: StoredToken): Promise<void> {
    // the only growth point of this map is issue(): sweep dead records here
    // so an unlinked player rejoining forever cannot balloon memory
    this.purge(record.createdAt)
    this.tokens.set(record.tokenHash, record)
  }

  async find(tokenHash: string): Promise<StoredToken | null> {
    return this.tokens.get(tokenHash) ?? null
  }

  async consume(tokenHash: string, now: number): Promise<{ record: StoredToken; alreadyConsumed: boolean } | null> {
    const record = this.tokens.get(tokenHash)
    if (!record) return null
    const alreadyConsumed = record.consumedAt !== null
    record.consumedAt = now
    return { record, alreadyConsumed }
  }

  /** drops expired/consumed records, called opportunistically */
  purge(now: number): void {
    for (const [hash, record] of this.tokens) {
      if (record.expiresAt <= now || record.consumedAt !== null) this.tokens.delete(hash)
    }
  }

  get size(): number {
    return this.tokens.size
  }
}

/** constant-time token hash comparison for flows that verify without lookup */
export function compareTokenHash(token: string, tokenHash: string): boolean {
  return safeEqualHex(sha256Hex(token), tokenHash)
}
