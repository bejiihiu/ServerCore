import { randomToken, sha256Hex, timingSafeEqualHex } from "../utils/crypto.js"

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
  /** returns the record only if it exists and is not expired/consumed */
  findActiveByHash(tokenHash: string, now: number): Promise<StoredToken | null>
  /** atomically marks the record consumed; second call returns null */
  consume(tokenHash: string, now: number): Promise<StoredToken | null>
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

    const tokenHash = sha256Hex(token)
    const record = await this.store.findActiveByHash(tokenHash, now)
    if (!record) return { valid: false, reason: "NOT_FOUND" }
    if (record.expiresAt <= now) return { valid: false, reason: "EXPIRED" }
    if (record.consumedAt !== null) return { valid: false, reason: "CONSUMED" }

    return { valid: true, meta: record.meta }
  }

  /** one-time consume: verify + burn in one go */
  async redeem(token: string, now = Date.now()): Promise<VerifyOutcome> {
    if (!isValidLinkTokenFormat(token)) return { valid: false, reason: "BAD_FORMAT" }

    const tokenHash = sha256Hex(token)
    const record = await this.store.consume(tokenHash, now)
    if (!record) return { valid: false, reason: "NOT_FOUND" }
    if (record.expiresAt <= now) return { valid: false, reason: "EXPIRED" }
    if (record.consumedAt !== null) return { valid: false, reason: "CONSUMED" }

    return { valid: true, meta: record.meta }
  }
}

/**
 * process-local store. fine for a single-instance backend; the mongo-backed
 * store replaces it without touching TokenService when persistence lands.
 */
export class InMemoryTokenStore implements TokenStore {
  private readonly tokens = new Map<string, StoredToken>()

  async save(record: StoredToken): Promise<void> {
    this.tokens.set(record.tokenHash, record)
  }

  async findActiveByHash(tokenHash: string, now: number): Promise<StoredToken | null> {
    const record = this.tokens.get(tokenHash)
    if (!record) return null
    if (record.expiresAt <= now) return null
    if (record.consumedAt !== null) return null
    return record
  }

  async consume(tokenHash: string, now: number): Promise<StoredToken | null> {
    const record = this.tokens.get(tokenHash)
    if (!record) return null
    record.consumedAt = now
    return record
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
  return timingSafeEqualHex(sha256Hex(token), tokenHash)
}
