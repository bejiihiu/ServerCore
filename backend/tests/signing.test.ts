import { describe, expect, test } from "bun:test"
import { canonicalize, sha256Hex } from "../src/utils/crypto.js"
import { signEnvelope, verifyWireMessage, signingInput } from "../src/websocket/protocol/signing.js"
import { randomHex } from "../src/utils/crypto.js"

const SECRET = "test-secret-0123456789abcdef0123456789abcdef"
const NOW = 1_700_000_000_000

function makeEnvelope(overrides: Record<string, unknown> = {}): {
  id: string
  type: string
  timestamp: number
  payload: Record<string, unknown>
} {
  return {
    id: randomHex(12),
    type: "SERVER_HEARTBEAT",
    timestamp: NOW,
    payload: { onlinePlayers: 3, maxPlayers: 40 },
    ...overrides,
  }
}

describe("canonicalize", () => {
  test("is key-order independent", () => {
    expect(canonicalize({ a: 1, b: 2 })).toBe(canonicalize({ b: 2, a: 1 }))
  })

  test("sorts nested objects too", () => {
    expect(canonicalize({ z: { b: 1, a: 2 }, a: 3 })).toBe('{"a":3,"z":{"a":2,"b":1}}')
  })

  test("drops undefined fields", () => {
    expect(canonicalize({ a: undefined, b: 1 })).toBe('{"b":1}')
  })

  test("serializes arrays in order", () => {
    expect(canonicalize([3, 1, 2])).toBe("[3,1,2]")
  })

  test("handles nested arrays of objects", () => {
    expect(canonicalize([{ b: 1, a: 2 }])).toBe('[{"a":2,"b":1}]')
  })
})

describe("signing", () => {
  test("signing input is stable across key order", () => {
    const one = signingInput({ id: "x".repeat(12), type: "ACK", timestamp: 1, payload: { a: 1, b: 2 } })
    const two = signingInput({ id: "x".repeat(12), type: "ACK", timestamp: 1, payload: { b: 2, a: 1 } })
    expect(one).toBe(two)
  })

  test("correlationId changes the signature", () => {
    const withCorr = signingInput({ id: "x".repeat(12), type: "ACK", timestamp: 1, correlationId: "c".repeat(12), payload: {} })
    const withoutCorr = signingInput({ id: "x".repeat(12), type: "ACK", timestamp: 1, payload: {} })
    expect(withCorr).not.toBe(withoutCorr)
  })

  test("hmac is deterministic for identical input", () => {
    const envelope = makeEnvelope()
    expect(signEnvelope(envelope, SECRET)).toStrictEqual(signEnvelope(envelope, SECRET))
  })

  test("different secrets produce different signatures", () => {
    const envelope = makeEnvelope()
    const a = signEnvelope(envelope, SECRET)
    const b = signEnvelope(envelope, SECRET + "x")
    expect(a.hmac).not.toBe(b.hmac)
  })
})

describe("verifyWireMessage", () => {
  const options = { maxSkewMs: 30_000, maxPacketBytes: 262_144 }

  test("accepts a correctly signed message and returns typed envelope", () => {
    const wire = signEnvelope(makeEnvelope(), SECRET)
    const result = verifyWireMessage(JSON.stringify(wire), SECRET, NOW, options)
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.envelope.type).toBe("SERVER_HEARTBEAT")
      expect(result.envelope.payload).toStrictEqual({ onlinePlayers: 3, maxPlayers: 40 })
    }
  })

  test("rejects a tampered payload", () => {
    const wire = signEnvelope(makeEnvelope(), SECRET)
    wire.payload = { onlinePlayers: 4, maxPlayers: 40 }
    const result = verifyWireMessage(JSON.stringify(wire), SECRET, NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("BAD_HMAC")
  })

  test("rejects a tampered type", () => {
    const wire = signEnvelope(makeEnvelope(), SECRET)
    wire.type = "PLAYER_JOINED"
    const result = verifyWireMessage(JSON.stringify(wire), SECRET, NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("BAD_HMAC")
  })

  test("rejects a wrong secret", () => {
    const wire = signEnvelope(makeEnvelope(), SECRET)
    const result = verifyWireMessage(JSON.stringify(wire), SECRET + "wrong", NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("BAD_HMAC")
  })

  test("rejects a stale timestamp beyond the window", () => {
    const wire = signEnvelope(makeEnvelope({ timestamp: NOW - 60_000 }), SECRET)
    const result = verifyWireMessage(JSON.stringify(wire), SECRET, NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("STALE_TIMESTAMP")
  })

  test("rejects a timestamp too far in the future", () => {
    const wire = signEnvelope(makeEnvelope({ timestamp: NOW + 60_000 }), SECRET)
    const result = verifyWireMessage(JSON.stringify(wire), SECRET, NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("STALE_TIMESTAMP")
  })

  test("accepts a timestamp within the skew window", () => {
    const wire = signEnvelope(makeEnvelope({ timestamp: NOW - 10_000 }), SECRET)
    const result = verifyWireMessage(JSON.stringify(wire), SECRET, NOW, options)
    expect(result.ok).toBe(true)
  })

  test("rejects invalid json", () => {
    const result = verifyWireMessage("{not json", SECRET, NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("BAD_JSON")
  })

  test("rejects an oversized packet before even parsing", () => {
    const result = verifyWireMessage("x".repeat(300_000), SECRET, NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.failure.reason).toBe("BAD_ENVELOPE")
      expect(result.failure.reason === "BAD_ENVELOPE" && result.failure.detail.includes("size")).toBe(true)
    }
  })

  test("rejects a validly signed message with a bad payload", () => {
    // signed with the right secret but the payload doesn't match the schema
    const wire = signEnvelope(makeEnvelope({ type: "SERVER_HEARTBEAT", payload: { onlinePlayers: -5 } }), SECRET)
    const result = verifyWireMessage(JSON.stringify(wire), SECRET, NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("BAD_PAYLOAD")
  })

  test("rejects a message missing the hmac field", () => {
    const wire = signEnvelope(makeEnvelope(), SECRET) as Record<string, unknown>
    delete wire["hmac"]
    const result = verifyWireMessage(JSON.stringify(wire), SECRET, NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("BAD_HMAC")
  })

  test("hmac covers the correlation id", () => {
    const envelope = makeEnvelope()
    const signed = signEnvelope({ ...envelope, correlationId: "corr12345678" }, SECRET)
    // strip correlation from the json, keep everything else — must fail
    const stripped = JSON.parse(JSON.stringify(signed)) as Record<string, unknown>
    delete stripped["correlationId"]
    const result = verifyWireMessage(JSON.stringify(stripped), SECRET, NOW, options)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("BAD_HMAC")
  })

  test("sha256 hashes are stable", () => {
    expect(sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
  })
})
