import { describe, expect, test } from "bun:test"
import { expectedSignature, verifyHandshake, type NonceCache } from "../src/websocket/auth.js"

const SECRET = "a".repeat(32)
const CREDENTIALS = { "game-1": SECRET }
const NOW = 1_700_000_000_000
const REPLAY_WINDOW_MS = 30_000
const NONCE_TTL_MS = 120_000

function freshCache(): NonceCache {
  return new Map()
}

describe("verifyHandshake", () => {
  test("accepts a well-formed handshake", () => {
    const ts = String(NOW)
    const sig = expectedSignature(SECRET, "game-1", ts, "n1")
    const result = verifyHandshake({ server: "game-1", ts, nonce: "n1", sig }, CREDENTIALS, freshCache(), NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS)
    expect(result).toEqual({ ok: true, serverId: "game-1" })
  })

  test("rejects missing fields", () => {
    const result = verifyHandshake({ server: "game-1", ts: String(NOW) }, CREDENTIALS, freshCache(), NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS)
    expect(result.ok).toBe(false)
  })

  test("rejects an unknown server id", () => {
    const ts = String(NOW)
    const sig = expectedSignature(SECRET, "game-1", ts, "n1")
    const result = verifyHandshake({ server: "game-9", ts, nonce: "n1", sig }, CREDENTIALS, freshCache(), NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS)
    expect(result.ok).toBe(false)
  })

  test("rejects a bad signature", () => {
    const ts = String(NOW)
    const sig = expectedSignature("wrong-secret", "game-1", ts, "n1")
    const result = verifyHandshake({ server: "game-1", ts, nonce: "n1", sig }, CREDENTIALS, freshCache(), NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS)
    expect(result).toEqual({ ok: false, reason: "signature mismatch" })
  })

  test("rejects timestamps outside the replay window, both directions", () => {
    const oldTs = String(NOW - REPLAY_WINDOW_MS - 1)
    const sigOld = expectedSignature(SECRET, "game-1", oldTs, "n1")
    expect(verifyHandshake({ server: "game-1", ts: oldTs, nonce: "n1", sig: sigOld }, CREDENTIALS, freshCache(), NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS).ok).toBe(false)

    const futureTs = String(NOW + REPLAY_WINDOW_MS + 1)
    const sigFuture = expectedSignature(SECRET, "game-1", futureTs, "n2")
    expect(verifyHandshake({ server: "game-1", ts: futureTs, nonce: "n2", sig: sigFuture }, CREDENTIALS, freshCache(), NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS).ok).toBe(false)
  })

  test("rejects a replayed nonce but allows a fresh one", () => {
    const cache = freshCache()
    const ts = String(NOW)
    const sig = expectedSignature(SECRET, "game-1", ts, "n1")

    expect(verifyHandshake({ server: "game-1", ts, nonce: "n1", sig }, CREDENTIALS, cache, NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS).ok).toBe(true)
    expect(verifyHandshake({ server: "game-1", ts, nonce: "n1", sig }, CREDENTIALS, cache, NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS)).toEqual({ ok: false, reason: "nonce replay" })

    const sig2 = expectedSignature(SECRET, "game-1", ts, "n2")
    expect(verifyHandshake({ server: "game-1", ts, nonce: "n2", sig: sig2 }, CREDENTIALS, cache, NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS).ok).toBe(true)
  })

  test("forgets nonces once their ttl passes", () => {
    const cache = freshCache()
    const ts = String(NOW)
    const sig = expectedSignature(SECRET, "game-1", ts, "n1")
    expect(verifyHandshake({ server: "game-1", ts, nonce: "n1", sig }, CREDENTIALS, cache, NOW, REPLAY_WINDOW_MS, NONCE_TTL_MS).ok).toBe(true)

    const later = NOW + NONCE_TTL_MS
    expect(verifyHandshake({ server: "game-1", ts, nonce: "n1", sig }, CREDENTIALS, cache, later, REPLAY_WINDOW_MS, NONCE_TTL_MS).ok).toBe(false)
  })
})
