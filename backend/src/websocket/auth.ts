import { hmacSha256Hex, safeEqualHex } from "../utils/crypto.js"

export type HandshakeQuery = {
  server: string
  ts: string
  nonce: string
  sig: string
}

export type HandshakeResult =
  | { ok: true; serverId: string }
  | { ok: false; reason: string }

/** nonce -> timestamp of first successful use, kept in gateway state */
export type NonceCache = Map<string, number>

/** exact string both sides sign. plugin mirrors this byte for byte */
export function signaturePayload(serverId: string, ts: string, nonce: string): string {
  return `${serverId}|${ts}|${nonce}`
}

export function expectedSignature(secret: string, serverId: string, ts: string, nonce: string): string {
  return hmacSha256Hex(secret, signaturePayload(serverId, ts, nonce))
}

/**
 * connection-level auth, checked in the fetch handler BEFORE upgrade.
 * proves the game server knows its per-server secret; every frame after the
 * upgrade is additionally envelope-signed with the same secret (see signing.ts).
 */
export function verifyHandshake(
  query: Partial<HandshakeQuery>,
  credentials: Record<string, string>,
  nonceCache: NonceCache,
  now: number,
  replayWindowMs: number,
  nonceTtlMs: number,
): HandshakeResult {
  const { server, ts, nonce, sig } = query
  if (server === undefined || ts === undefined || nonce === undefined || sig === undefined) {
    return { ok: false, reason: "missing handshake fields" }
  }

  const secret = credentials[server]
  if (secret === undefined) return { ok: false, reason: "unknown server id" }

  const tsNum = Number(ts)
  if (!Number.isInteger(tsNum) || tsNum <= 0) return { ok: false, reason: "bad timestamp" }
  // absolute window so mild clock skew in either direction still connects
  if (Math.abs(now - tsNum) > replayWindowMs) {
    return { ok: false, reason: "timestamp outside replay window" }
  }

  const expected = expectedSignature(secret, server, ts, nonce)
  if (!safeEqualHex(expected, sig)) return { ok: false, reason: "signature mismatch" }

  // replay protection: burn the nonce on first successful use
  purgeNonces(nonceCache, now, nonceTtlMs)
  const seenAt = nonceCache.get(nonce)
  if (seenAt !== undefined && now - seenAt < nonceTtlMs) {
    return { ok: false, reason: "nonce replay" }
  }
  nonceCache.set(nonce, now)

  return { ok: true, serverId: server }
}

function purgeNonces(cache: NonceCache, now: number, ttlMs: number): void {
  for (const [nonce, seenAt] of cache) {
    if (now - seenAt >= ttlMs) cache.delete(nonce)
  }
}
