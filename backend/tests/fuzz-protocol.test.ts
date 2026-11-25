import { describe, expect, test } from "bun:test"
import * as fc from "fast-check"
import { readFileSync } from "node:fs"
import path from "node:path"
import pino from "pino"
import { canonicalize, hmacSha256Hex } from "../src/utils/crypto.js"
import {
  envelopeMetaSchema,
  isMessageType,
  MESSAGE_TYPES,
  payloadSchemas,
  type MessageType,
} from "../src/websocket/protocol/message-types.js"
import {
  signEnvelope,
  signingInput,
  verifyWireMessage,
  type VerifyFailure,
} from "../src/websocket/protocol/signing.js"
import { ServerConnection, type ConnectionOptions, type GatewaySocketData } from "../src/websocket/connection.js"
import { ReplayGuard } from "../src/websocket/security/replay-guard.js"

/**
 * adversarial fuzzing of the wire protocol: envelope parsing, signing,
 * canonical json, every payload schema and the replay/dedupe path. property
 * based, deliberately hostile input, no mocks in the parse/hmac path.
 *
 * budget: 200 runs per property, suite stays well under the 10s test budget.
 *
 * repro a failure: fast-check prints `seed` and `path` with every shrunk
 * counterexample, feed the seed back through the env var:
 *   FUZZ_SEED=<seed> bun test tests/fuzz-protocol.test.ts
 */

const NUM_RUNS = 200
const seedEnv = process.env["FUZZ_SEED"]
const FUZZ_SEED = seedEnv === undefined ? undefined : Number(seedEnv)

const SECRET = "fuzz-secret-0123456789abcdef0123456789"
const UUID = "0f4d2f3e-8a44-4c21-9d0e-3b1f2a4c5d60"
const NOW = 1_700_000_000_000
const MAX_PACKET_BYTES = 262_144
const OPTIONS = { maxSkewMs: 30_000, maxPacketBytes: MAX_PACKET_BYTES }

const FAILURE_REASONS = new Set(["BAD_JSON", "BAD_ENVELOPE", "BAD_PAYLOAD", "BAD_HMAC", "STALE_TIMESTAMP"])

function fuzz<T>(property: fc.IProperty<T>, params: fc.Parameters<T> = {}): void {
  fc.assert(property, {
    numRuns: NUM_RUNS,
    ...(FUZZ_SEED !== undefined ? { seed: FUZZ_SEED } : {}),
    ...params,
  })
}

// ---------------------------------------------------------------------------
// fixtures
// ---------------------------------------------------------------------------

const MANIFEST_PATH = path.join(import.meta.dir, "../../protocol/messages.json")

interface ManifestField {
  name: string
  type: "uuid" | "objectId" | "string" | "integer" | "number" | "boolean" | "enum" | "object"
  required: boolean
  values?: string[]
  fields?: ManifestField[]
}

interface ManifestMessage {
  type: MessageType
  fields: ManifestField[]
  example: Record<string, unknown>
}

const manifest = JSON.parse(readFileSync(MANIFEST_PATH, "utf8")) as { messages: ManifestMessage[] }

type FrameSpec = {
  id?: string
  type?: string
  timestamp?: number
  correlationId?: string
  payload?: unknown
}

/** signs an envelope with the test secret; the type is deliberately loose so unknown types can be fuzzed */
function signedFrame(spec: FrameSpec = {}): string {
  const type = (spec.type ?? "SERVER_HEARTBEAT") as MessageType
  return JSON.stringify(
    signEnvelope(
      {
        id: spec.id ?? "frame-0001",
        type,
        timestamp: spec.timestamp ?? NOW,
        ...(spec.correlationId !== undefined ? { correlationId: spec.correlationId } : {}),
        payload: spec.payload === undefined ? { onlinePlayers: 3, maxPlayers: 40 } : spec.payload,
      },
      SECRET,
    ),
  )
}

/** minimal stand-in for Bun's server socket: records writes, never throws */
function fakeSocket() {
  const sent: string[] = []
  let closed = false
  const socket = {
    sendText: (text: string) => {
      sent.push(text)
    },
    getBufferedAmount: () => 0,
    ping: () => {},
    close: () => {
      closed = true
    },
    terminate: () => {
      closed = true
    },
  }
  return {
    socket: socket as unknown as Bun.ServerWebSocket<GatewaySocketData>,
    sent,
    isClosed: () => closed,
  }
}

function connectionOptions(): ConnectionOptions {
  return {
    secret: SECRET,
    heartbeatIntervalMs: 15_000,
    heartbeatTimeoutMs: 10_000,
    maxPacketBytes: MAX_PACKET_BYTES,
    replayWindowMs: OPTIONS.maxSkewMs,
    replayGuardTtlMs: 150_000,
  }
}

const silentLogger = pino({ level: "silent" })

function newConnection(serverId = "game-1") {
  const fake = fakeSocket()
  const connection = new ServerConnection(serverId, fake.socket, connectionOptions(), silentLogger)
  return { fake, connection }
}

/** recursively re-order object keys, arrays keep their order */
function shuffleKeys(value: unknown, order: (keys: string[]) => string[]): unknown {
  if (value === null || typeof value !== "object") return value
  if (Array.isArray(value)) return value.map((item) => shuffleKeys(item, order))
  const record = value as Record<string, unknown>
  // null prototype so an own "__proto__" key stays an own key instead of
  // rewiring the prototype while we rebuild the object
  const out: Record<string, unknown> = Object.create(null) as Record<string, unknown>
  for (const key of order(Object.keys(record))) out[key] = shuffleKeys(record[key], order)
  return out
}

/** arbitrary json-shaped value as the peer could send it on the wire */
const arbitraryJson = fc.jsonValue()

/** arbitrary raw frames: text, unicode, json, pretty json, json with a trailing comma */
const arbitraryRawFrame = fc.oneof(
  fc.string({ maxLength: 64 }),
  fc.string({ unit: "binary", maxLength: 64 }),
  fc.string({ unit: "grapheme", maxLength: 64 }),
  arbitraryJson.map((value) => JSON.stringify(value)),
  arbitraryJson.map((value) => JSON.stringify(value, null, 2)),
  arbitraryJson.map((value) => `${JSON.stringify(value)},`),
)

const arbitraryId = fc.stringMatching(/^[A-Za-z0-9_-]{8,32}$/)

function expectFailure(result: ReturnType<typeof verifyWireMessage>, reasons: string[]): VerifyFailure {
  if (result.ok) throw new Error(`expected a failure (${reasons.join("/")}), got ok`)
  expect(reasons).toContain(result.failure.reason)
  return result.failure
}

// ---------------------------------------------------------------------------
// envelope parsing: total, never throws, never kills the process
// ---------------------------------------------------------------------------

describe("envelope parsing is total", () => {
  test("any raw frame yields a discriminated result instead of throwing", () => {
    fuzz(
      fc.property(arbitraryRawFrame, (raw) => {
        const result = verifyWireMessage(raw, SECRET, NOW, OPTIONS)
        if (result.ok) {
          expect(typeof result.known).toBe("boolean")
          expect(result.envelope.id.length).toBeGreaterThanOrEqual(8)
        } else {
          expect(FAILURE_REASONS.has(result.failure.reason)).toBe(true)
        }
      }),
    )
  })

  test("handleFrame never throws and never closes the socket on hostile input", () => {
    fuzz(
      fc.property(arbitraryRawFrame, (raw) => {
        const { fake, connection } = newConnection()
        const routed: string[] = []
        const carried: (VerifyFailure | null)[] = []
        expect(() =>
          carried.push(connection.handleFrame(raw, NOW, async (envelope) => void routed.push(envelope.id))),
        ).not.toThrow()
        const failure = carried[0] ?? null
        if (failure !== null) expect(FAILURE_REASONS.has(failure.reason)).toBe(true)
        // parsing a hostile frame is a caller decision (error frame + close), the parser
        // itself must never tear the socket down
        expect(fake.isClosed()).toBe(false)
        expect(connection.isAlive).toBe(true)
      }),
    )
  })

  test("a correct hmac over a garbage payload is never a connection-level failure", () => {
    fuzz(
      fc.property(
        fc.constantFrom(...MESSAGE_TYPES),
        arbitraryJson,
        arbitraryId,
        (type, payload, id) => {
          const raw = signedFrame({ type, payload, id })
          const result = verifyWireMessage(raw, SECRET, NOW, OPTIONS)
          // the signature is genuine: only the schema may reject this frame
          expect(result.ok || result.failure.reason === "BAD_PAYLOAD").toBe(true)
        },
      ),
    )
  })

  test("oversized frames are refused before json is even attempted", () => {
    fuzz(
      fc.property(fc.integer({ min: 1, max: 4_096 }), (extra) => {
        const raw = "x".repeat(MAX_PACKET_BYTES + extra)
        const failure = expectFailure(verifyWireMessage(raw, SECRET, NOW, OPTIONS), ["BAD_ENVELOPE"])
        expect(failure.reason === "BAD_ENVELOPE" && failure.detail).toContain("size")
      }),
    )
  })

  test("a frame exactly at the byte ceiling still reaches the parser", () => {
    const raw = "x".repeat(MAX_PACKET_BYTES)
    // ascii only, so characters == bytes: the size gate must not fire
    expectFailure(verifyWireMessage(raw, SECRET, NOW, OPTIONS), ["BAD_JSON"])
  })

  test("binary frames decoded to text never escape the parser", () => {
    fuzz(
      fc.property(fc.uint8Array({ maxLength: 128 }), (bytes) => {
        const raw = new TextDecoder().decode(bytes)
        const result = verifyWireMessage(raw, SECRET, NOW, OPTIONS)
        expect(result.ok || FAILURE_REASONS.has(result.failure.reason)).toBe(true)
      }),
    )
  })
})

// ---------------------------------------------------------------------------
// hmac integrity: tampering anywhere is BAD_HMAC, correct signatures survive re-encoding
// ---------------------------------------------------------------------------

describe("hmac integrity", () => {
  test("flipping any single hmac character is rejected", () => {
    const valid = signedFrame()
    fuzz(
      fc.property(fc.integer({ min: 0, max: 63 }), (index) => {
        const wire = JSON.parse(valid) as Record<string, unknown>
        const hmac = wire["hmac"] as string
        const original = hmac[index] as string
        const replacement = original === "0" ? "1" : "0"
        wire["hmac"] = `${hmac.slice(0, index)}${replacement}${hmac.slice(index + 1)}`
        expectFailure(verifyWireMessage(JSON.stringify(wire), SECRET, NOW, OPTIONS), ["BAD_HMAC"])
      }),
    )
  })

  test("tampering any signed field is rejected", () => {
    fuzz(
      fc.property(fc.integer({ min: 0, max: 1_000 }), fc.stringMatching(/^[A-Za-z_-]{3,16}$/), (delta, suffix) => {
        const wire = JSON.parse(signedFrame()) as Record<string, unknown>
        const payload = wire["payload"] as Record<string, unknown>
        const mutated: Array<Record<string, unknown>> = [
          { ...wire, payload: { ...payload, onlinePlayers: Number(payload["onlinePlayers"]) + delta + 1 } },
          { ...wire, id: `${wire["id"] as string}${suffix}` },
          { ...wire, type: "SERVER_STATUS" },
          { ...wire, timestamp: Number(wire["timestamp"]) + delta + 1 },
          { ...wire, correlationId: suffix.repeat(4) },
        ]
        for (const candidate of mutated) {
          expectFailure(verifyWireMessage(JSON.stringify(candidate), SECRET, NOW, OPTIONS), ["BAD_HMAC"])
        }
      }),
    )
  })

  test("a wrong secret is rejected", () => {
    fuzz(
      fc.property(fc.string({ minLength: 1, maxLength: 40 }).filter((candidate) => candidate !== SECRET), (wrong) => {
        expectFailure(verifyWireMessage(signedFrame(), wrong, NOW, OPTIONS), ["BAD_HMAC"])
      }),
    )
  })

  test("a valid hmac with the wrong envelope shape still fails cleanly", () => {
    fuzz(
      fc.property(
        fc.oneof(
          fc.constant(null),
          fc.constant("string"),
          fc.integer(),
          fc.array(fc.integer()),
          fc.dictionary(fc.string(), fc.jsonValue()),
        ),
        arbitraryId,
        (payload, id) => {
          // hmac is computed over whatever payload arrives, so the signature is only
          // as meaningful as the envelope around it — a non-object payload can never pass
          const raw = signedFrame({ payload, id })
          const result = verifyWireMessage(raw, SECRET, NOW, OPTIONS)
          expect(result.ok || result.failure.reason === "BAD_PAYLOAD").toBe(true)
        },
      ),
    )
  })

  test("reordering keys and whitespace never changes the signature", () => {
    fuzz(
      fc.property(fc.array(fc.integer({ min: 0, max: 10 }), { maxLength: 8 }), (seed) => {
        const wire = signEnvelope(
          {
            id: "frame-0001",
            type: "GUEST_STARTED",
            timestamp: NOW,
            payload: {
              minecraftUuid: UUID,
              username: "bejiihiu",
              sessionId: "guest-1",
              snapshot: {
                world: "world", x: 1, y: 2, z: 3, yaw: 4, pitch: 5,
                gameMode: "SURVIVAL", flying: false, flySpeed: 0.1, walkSpeed: 0.2,
              },
            },
          },
          SECRET,
        )
        const shuffled = shuffleKeys(wire, (keys) => {
          const copy = [...keys]
          // deterministic but input-dependent reorder
          for (let i = copy.length - 1; i > 0; i -= 1) {
            const j = (seed[i % Math.max(seed.length, 1)] ?? i) % (i + 1)
            const left = copy[i] as string
            copy[i] = copy[j] as string
            copy[j] = left
          }
          return copy
        })
        const result = verifyWireMessage(JSON.stringify(shuffled), SECRET, NOW, OPTIONS)
        expect(result.ok).toBe(true)
        // whitespace between tokens means nothing to the parser or the signature
        expect(verifyWireMessage(JSON.stringify(shuffled, null, 2), SECRET, NOW, OPTIONS).ok).toBe(true)
      }),
    )
  })
})

// ---------------------------------------------------------------------------
// timestamp window / replay window
// ---------------------------------------------------------------------------

describe("timestamp window", () => {
  test("stale and future frames outside the window are rejected, inside it accepted", () => {
    fuzz(
      fc.property(fc.integer({ min: -600_000, max: 600_000 }), (delta) => {
        const raw = signedFrame({ timestamp: NOW + delta })
        const result = verifyWireMessage(raw, SECRET, NOW, OPTIONS)
        if (Math.abs(delta) > OPTIONS.maxSkewMs) {
          expectFailure(result, ["STALE_TIMESTAMP"])
        } else {
          expect(result.ok).toBe(true)
        }
      }),
    )
  })

  test("the window boundary itself is inclusive", () => {
    for (const delta of [-OPTIONS.maxSkewMs, OPTIONS.maxSkewMs]) {
      expect(verifyWireMessage(signedFrame({ timestamp: NOW + delta }), SECRET, NOW, OPTIONS).ok).toBe(true)
    }
    for (const delta of [-OPTIONS.maxSkewMs - 1, OPTIONS.maxSkewMs + 1]) {
      expectFailure(verifyWireMessage(signedFrame({ timestamp: NOW + delta }), SECRET, NOW, OPTIONS), [
        "STALE_TIMESTAMP",
      ])
    }
  })
})

// ---------------------------------------------------------------------------
// canonical json
// ---------------------------------------------------------------------------

describe("canonical json", () => {
  test("canonicalization is stable across a json round trip", () => {
    fuzz(
      fc.property(arbitraryJson, (value) => {
        const once = canonicalize(value)
        expect(canonicalize(JSON.parse(JSON.stringify(value)) as unknown)).toBe(once)
      }),
    )
  })

  test("key order never matters, at any nesting level", () => {
    fuzz(
      fc.property(arbitraryJson, fc.integer(), (value, rotate) => {
        const canonical = canonicalize(value)
        const reordered = shuffleKeys(value, (keys) => {
          if (keys.length === 0) return keys
          const at = Math.abs(rotate) % keys.length
          return [...keys.slice(at), ...keys.slice(0, at)]
        })
        expect(canonicalize(reordered)).toBe(canonical)
      }),
    )
  })

  test("unicode and escape forms canonicalize to the same bytes", () => {
    fuzz(
      fc.property(fc.string({ unit: "binary", maxLength: 40 }), (text) => {
        const literal = canonicalize({ value: text })
        const escaped = canonicalize(JSON.parse(JSON.stringify({ value: text })) as unknown)
        expect(escaped).toBe(literal)
      }),
    )
    // explicit escape-form equivalence: \u00e9 == é
    const fromEscapes = JSON.parse('{"v":"\\u00e9\\u00e9"}') as unknown
    expect(canonicalize(fromEscapes)).toBe(canonicalize({ v: "éé" }))
    // lone surrogates survive json.parse and never blow up the canonicalizer
    const lone = JSON.parse('"\\ud800"') as string
    expect(() => canonicalize({ lone })).not.toThrow()
    expect(canonicalize({ lone })).toBe('{"lone":"\\ud800"}')
  })

  test("nested escapes do not change the signature", () => {
    const message = 'quote " backslash \\ newline \n tab \t emoji 😀'
    const raw = JSON.stringify(
      signEnvelope({ id: "frame-0001", type: "ERROR", timestamp: NOW, payload: { code: "X", message } }, SECRET),
    )
    expect(verifyWireMessage(raw, SECRET, NOW, OPTIONS).ok).toBe(true)
    // an escaped form of the same string verifies just as well
    const withEscapes = raw.replace("😀", "\\ud83d\\ude00")
    expect(verifyWireMessage(withEscapes, SECRET, NOW, OPTIONS).ok).toBe(true)
  })

  test("duplicate keys in raw json are last-wins, both for parsing and signing", () => {
    expect(JSON.parse('{"a":1,"a":2}')).toStrictEqual({ a: 2 })
    expect(canonicalize(JSON.parse('{"a":1,"a":2}') as unknown)).toBe('{"a":2}')

    // a frame with a duplicated payload key: the last one is what gets signed and
    // what the schema sees, so a peer cannot smuggle a second value past verification
    const signed = JSON.parse(signedFrame()) as Record<string, unknown>
    const hmac = signed["hmac"] as string
    const duplicated = `{"id":${JSON.stringify(signed["id"])},"type":"SERVER_HEARTBEAT","timestamp":${NOW},` +
      `"payload":{"onlinePlayers":999},"payload":{"onlinePlayers":3,"maxPlayers":40},"hmac":"${hmac}"}`
    const result = verifyWireMessage(duplicated, SECRET, NOW, OPTIONS)
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.envelope.payload).toStrictEqual({ onlinePlayers: 3, maxPlayers: 40 })

    // same trick with a mismatching tail is a plain bad signature
    const smuggled = `{"id":${JSON.stringify(signed["id"])},"type":"SERVER_HEARTBEAT","timestamp":${NOW},` +
      `"payload":{"onlinePlayers":3,"maxPlayers":40},"payload":{"onlinePlayers":999},"hmac":"${hmac}"}`
    expectFailure(verifyWireMessage(smuggled, SECRET, NOW, OPTIONS), ["BAD_HMAC"])
  })

  test("a __proto__ key in the payload is data, never a prototype rewrite", () => {
    const raw =
      `{"id":"frame-0001","type":"SERVER_HEARTBEAT","timestamp":${NOW},` +
      `"payload":{"onlinePlayers":3,"maxPlayers":40,"__proto__":{"polluted":true}},"hmac":"${"0".repeat(64)}"}`
    const result = verifyWireMessage(raw, SECRET, NOW, OPTIONS)
    expect(result.ok).toBe(false)
    expect(({} as Record<string, unknown>)["polluted"]).toBeUndefined()
    // json.parse makes it an own key and the canonicalizer walks it as data
    expect(canonicalize(JSON.parse(raw) as unknown)).toContain('"__proto__"')
  })

  test("pathological nesting is refused without throwing", () => {
    const depth = 50_000
    const raw = `{"id":"frame-0001","type":"SERVER_HEARTBEAT","timestamp":${NOW},` +
      `"payload":${"[".repeat(depth)}1${"]".repeat(depth)},"hmac":"${"0".repeat(64)}"}`
    expect(Buffer.byteLength(raw, "utf8")).toBeLessThan(MAX_PACKET_BYTES)

    const outcome: Array<ReturnType<typeof verifyWireMessage>> = []
    expect(() => outcome.push(verifyWireMessage(raw, SECRET, NOW, OPTIONS))).not.toThrow()
    expectFailure(outcome[0] as ReturnType<typeof verifyWireMessage>, ["BAD_HMAC", "BAD_PAYLOAD"])

    // and the canonicalizer itself refuses instead of recursing to a stack overflow
    expect(() => canonicalize(JSON.parse("[".repeat(depth) + "1" + "]".repeat(depth)) as unknown)).toThrow()
  })

  test("non-finite numbers cannot be smuggled in as 1e400", () => {
    // json.parse turns an overflowing literal into Infinity, which used to canonicalize
    // to null: two different wire payloads with one signature
    expect(JSON.parse("1e400")).toBe(Infinity)
    expect(() => canonicalize({ junk: JSON.parse("1e400") as number })).toThrow()

    const forged = hmacSha256Hex(
      SECRET,
      signingInput({
        id: "frame-0001",
        type: "SERVER_HEARTBEAT",
        timestamp: NOW,
        payload: { onlinePlayers: 3, maxPlayers: 40, junk: null },
      }),
    )
    const raw =
      `{"id":"frame-0001","type":"SERVER_HEARTBEAT","timestamp":${NOW},` +
      `"payload":{"onlinePlayers":3,"maxPlayers":40,"junk":1e400},"hmac":"${forged}"}`
    expectFailure(verifyWireMessage(raw, SECRET, NOW, OPTIONS), ["BAD_PAYLOAD"])

    // the same frame with a plain null is still fine — only non-finite numbers are refused
    const honest = raw.replace("1e400", "null")
    expect(verifyWireMessage(honest, SECRET, NOW, OPTIONS).ok).toBe(true)
  })

  test("negative zero signs like zero (documented canonical choice)", () => {
    expect(canonicalize({ v: -0 })).toBe(canonicalize({ v: 0 }))
    expect(canonicalize({ v: -0 })).toBe('{"v":0}')
  })
})

// ---------------------------------------------------------------------------
// payload schemas, driven by the protocol manifest
// ---------------------------------------------------------------------------

interface ScalarPath {
  path: string[]
  type: ManifestField["type"]
  required: boolean
}

function scalarPaths(fields: ManifestField[], prefix: string[] = []): ScalarPath[] {
  const out: ScalarPath[] = []
  for (const field of fields) {
    const path = [...prefix, field.name]
    if (field.type === "object" && field.fields !== undefined) out.push(...scalarPaths(field.fields, path))
    else out.push({ path, type: field.type, required: field.required })
  }
  return out
}

const manifestCases = manifest.messages.flatMap((message) =>
  scalarPaths(message.fields).map((field) => ({ message, field })),
)

function cloneExample(message: ManifestMessage): Record<string, unknown> {
  return JSON.parse(JSON.stringify(message.example)) as Record<string, unknown>
}

function wrongValue(type: ManifestField["type"]): unknown {
  switch (type) {
    case "uuid":
      return 12345
    case "objectId":
      return {}
    case "string":
      return ["x"]
    case "integer":
      return 1.5
    case "number":
      return "1.5"
    case "boolean":
      return "yes"
    case "enum":
      return "__NOT_A_VALUE__"
    case "object":
      return "not-an-object"
  }
}

describe("payload schemas are total and strict", () => {
  test("every schema survives arbitrary json without throwing", () => {
    fuzz(
      fc.property(fc.constantFrom(...MESSAGE_TYPES), arbitraryJson, (type, payload) => {
        const result = payloadSchemas[type].safeParse(payload)
        expect(typeof result.success).toBe("boolean")
      }),
    )
  })

  test("every schema survives arbitrary objects and arrays without throwing", () => {
    const arbitraryShape = fc.oneof(
      fc.dictionary(fc.string(), arbitraryJson),
      fc.array(arbitraryJson),
      fc.constant(null),
      fc.constant(undefined),
      fc.string(),
      fc.double(),
      fc.boolean(),
    )
    fuzz(
      fc.property(fc.constantFrom(...MESSAGE_TYPES), arbitraryShape, (type, payload) => {
        const result = payloadSchemas[type].safeParse(payload)
        expect(typeof result.success).toBe("boolean")
      }),
    )
  })

  test("every manifest example passes its own schema and survives signing", () => {
    for (const message of manifest.messages) {
      const parsed = payloadSchemas[message.type].safeParse(message.example)
      expect(parsed.success, `${message.type}: example must be schema-valid`).toBe(true)
      const raw = signedFrame({ type: message.type, payload: message.example })
      const result = verifyWireMessage(raw, SECRET, NOW, OPTIONS)
      expect(result.ok, `${message.type}: signed example must verify`).toBe(true)
      if (result.ok) {
        expect(result.known).toBe(true)
        expect(result.envelope.type).toBe(message.type)
        expect(result.envelope.payload).toStrictEqual(parsed.success ? parsed.data : message.example)
      }
    }
  })

  test("only the valid ones pass: a wrong-typed field is always rejected", () => {
    fuzz(
      fc.property(fc.constantFrom(...manifestCases), (entry) => {
        const payload = cloneExample(entry.message)
        let cursor: Record<string, unknown> = payload
        for (const key of entry.field.path.slice(0, -1)) {
          cursor = cursor[key] as Record<string, unknown>
        }
        cursor[entry.field.path[entry.field.path.length - 1] as string] = wrongValue(entry.field.type)
        expect(
          payloadSchemas[entry.message.type].safeParse(payload).success,
          `${entry.message.type}.${entry.field.path.join(".")} accepted a ${entry.field.type} field with a wrong type`,
        ).toBe(false)
      }),
    )
  })

  test("only the valid ones pass: a missing required field is always rejected", () => {
    const required = manifestCases.filter((entry) => entry.field.required)
    fuzz(
      fc.property(fc.constantFrom(...required), (entry) => {
        const payload = cloneExample(entry.message)
        let cursor: Record<string, unknown> = payload
        for (const key of entry.field.path.slice(0, -1)) {
          cursor = cursor[key] as Record<string, unknown>
        }
        delete cursor[entry.field.path[entry.field.path.length - 1] as string]
        expect(
          payloadSchemas[entry.message.type].safeParse(payload).success,
          `${entry.message.type}.${entry.field.path.join(".")} is required by the manifest but the schema accepted a payload without it`,
        ).toBe(false)
      }),
    )
  })

  test("unknown keys are stripped, not rejected (documented forward-compat choice)", () => {
    fuzz(
      fc.property(fc.constantFrom(...manifest.messages), fc.string({ maxLength: 8 }), (message, suffix) => {
        const extraKey = `zz${suffix}`
        const payload = { ...cloneExample(message), [extraKey]: { nest: [1, 2, 3] } }
        const result = payloadSchemas[message.type].safeParse(payload)
        expect(result.success).toBe(true)
        if (result.success) expect(Object.hasOwn(result.data as object, extraKey)).toBe(false)
      }),
    )
  })

  test("envelope meta is total and rejects off-spec identifiers", () => {
    const arbitraryMeta = fc.dictionary(fc.string(), arbitraryJson)
    fuzz(
      fc.property(arbitraryMeta, (meta) => {
        const result = envelopeMetaSchema.safeParse(meta)
        expect(typeof result.success).toBe("boolean")
      }),
    )
    fuzz(
      fc.property(fc.string({ unit: "binary", maxLength: 24 }), (id) => {
        const result = envelopeMetaSchema.safeParse({ id, type: "SERVER_HEARTBEAT", timestamp: NOW })
        expect(typeof result.success).toBe("boolean")
      }),
    )
  })
})

// ---------------------------------------------------------------------------
// dedupe / replay guard
// ---------------------------------------------------------------------------

describe("replay guard", () => {
  test("matches a reference model under arbitrary id/time streams", () => {
    fuzz(
      fc.property(
        fc.array(
          fc.record({
            id: fc.constantFrom("a", "b", "c", "d", "e"),
            dt: fc.nat({ max: 400 }),
          }),
          { maxLength: 40 },
        ),
        (ops) => {
          const ttl = 1_000
          const guard = new ReplayGuard(ttl)
          const model = new Map<string, number>()
          let now = 0

          for (const op of ops) {
            now += op.dt
            for (const [key, at] of model) if (now - at > ttl) model.delete(key)
            const expected = !model.has(op.id)
            expect(guard.checkAndRecord(op.id, now)).toBe(expected)
            if (expected) model.set(op.id, now)
            expect(guard.size).toBe(model.size)
          }
        },
      ),
    )
  })

  test("the ttl boundary is exact: inside blocks, one millisecond past frees the id", () => {
    const guard = new ReplayGuard(1_000)
    expect(guard.checkAndRecord("msg-1", 1_000)).toBe(true)
    expect(guard.checkAndRecord("msg-1", 2_000)).toBe(false)
    expect(guard.checkAndRecord("msg-1", 2_001)).toBe(true)
  })

  test("ids may be reused on a new connection (guard is per connection, the timestamp window is the cross-connection defence)", () => {
    const raw = signedFrame({ id: "frame-0001" })
    const first = newConnection("game-1")
    const second = newConnection("game-1")
    const routed: string[] = []

    expect(first.connection.handleFrame(raw, NOW, async (envelope) => void routed.push(`a:${envelope.id}`))).toBeNull()
    expect(second.connection.handleFrame(raw, NOW, async (envelope) => void routed.push(`b:${envelope.id}`))).toBeNull()
    expect(routed).toEqual(["a:frame-0001", "b:frame-0001"])
  })

  test("the same frame twice is acked twice and routed once", () => {
    const raw = signedFrame({ id: "frame-0001" })
    const { fake, connection } = newConnection()
    const routed: string[] = []
    const route = async (envelope: { id: string }) => void routed.push(envelope.id)

    expect(connection.handleFrame(raw, NOW, route)).toBeNull()
    expect(connection.handleFrame(raw, NOW, route)).toBeNull()
    // a frame redelivered after a lost ack is not an attack: ack it again, do not
    // reprocess, do not close (that would turn one lost ack into a reconnect loop)
    expect(routed).toEqual(["frame-0001"])
    expect(fake.sent.filter((text) => text.includes('"ackedId":"frame-0001"'))).toHaveLength(2)
    expect(fake.isClosed()).toBe(false)
  })

  test("every accepted non-transport frame is acked exactly once with a valid ack payload", () => {
    fuzz(
      fc.property(
        fc.constantFrom(...MESSAGE_TYPES.filter((type) => type !== "SERVER_HELLO")),
        arbitraryJson,
        arbitraryId,
        (type, payload, id) => {
          const { fake, connection } = newConnection()
          connection.handleFrame(signedFrame({ type, payload, id }), NOW, async () => {})

          for (const text of fake.sent) {
            const ack = JSON.parse(text) as { type: string; payload: unknown }
            expect(ack.type).toBe("ACK")
            expect(payloadSchemas.ACK.safeParse(ack.payload).success).toBe(true)
          }
          // no-ack types stay silent, everything else including unknown types gets one ack
          const expectedAcks = ["SERVER_READY", "ACK", "ERROR"].includes(type) ? 0 : 1
          // a garbage payload never reaches the ack path: signature is genuine but the
          // schema rejects it before any ack is emitted or any handler runs
          const schemaOk = payloadSchemas[type].safeParse(payload).success
          expect(fake.sent.length).toBe(schemaOk ? expectedAcks : 0)
        },
      ),
    )
  })

  test("unknown types are acked and dropped, never routed", () => {
    const unknownType = fc
      .stringMatching(/^[A-Z][A-Z0-9_]{3,20}$/)
      .filter((type) => !isMessageType(type) && type !== "SERVER_HELLO")
    fuzz(
      fc.property(unknownType, arbitraryId, (type, id) => {
        const { fake, connection } = newConnection()
        const routed: string[] = []
        const result = connection.handleFrame(
          signedFrame({ type, id }),
          NOW,
          async (envelope) => void routed.push(envelope.id),
        )
        expect(result).toBeNull()
        expect(routed).toEqual([])
        expect(fake.sent).toHaveLength(1)
        expect(fake.isClosed()).toBe(false)
        expect(connection.isAlive).toBe(true)
      }),
    )
  })

  test("a signed SERVER_HELLO after the upgrade is a protocol violation", () => {
    const { connection } = newConnection()
    const result = connection.handleFrame(
      signedFrame({ type: "SERVER_HELLO", payload: { serverId: "game-1", clientNonce: "abcdefgh12" } }),
      NOW,
      async () => {},
    )
    expect(result?.reason).toBe("BAD_ENVELOPE")
  })
})
