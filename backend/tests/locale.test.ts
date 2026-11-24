import { describe, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { payloadSchemas } from "../src/websocket/protocol/message-types.js"
import {
  LocaleService,
  MAX_BUNDLE_BYTES,
  revisionOf,
  type LocaleBundle,
} from "../src/services/locale-service.js"
import { HandlerRegistry, silentLogger } from "../src/websocket/handlers/registry.js"
import { registerLocaleHandlers } from "../src/websocket/handlers/locale-handlers.js"
import { ServerConnection, type ConnectionOptions, type GatewaySocketData } from "../src/websocket/connection.js"
import { signEnvelope } from "../src/websocket/protocol/signing.js"

const BACKEND_BUNDLE = new URL("../src/locales/ru.yml", import.meta.url)
const PLUGIN_SEED = new URL("../../plugin/src/main/resources/locales/ru.yml", import.meta.url)

function service(bundlePath: string | URL = BACKEND_BUNDLE): LocaleService {
  return new LocaleService({ logger: silentLogger(), bundlePath })
}

function normalizeNewlines(text: string): string {
  return text.replace(/\r\n/g, "\n")
}

type SentFrame = {
  type: string
  payload: { locale: string; revision: string; content: string }
  options?: { correlationId?: string }
}

function fakeConnection(serverId: string) {
  const sent: SentFrame[] = []
  const connection = {
    serverId,
    async send(type: string, payload: SentFrame["payload"], options?: SentFrame["options"]) {
      sent.push({ type, payload, ...(options !== undefined ? { options } : {}) })
    },
  } as unknown as ServerConnection
  return { connection, sent }
}

describe("locale bundle", () => {
  test("ships the canonical bundle and validates against the push schema", () => {
    const bundle: LocaleBundle = service().current
    expect(bundle.locale).toBe("ru")
    expect(bundle.content.length).toBeGreaterThan(0)
    expect(bundle.revision).toMatch(/^[0-9a-f]{16}$/)

    const parsed = payloadSchemas.LOCALE_PUSH.safeParse(bundle)
    expect(parsed.success).toBe(true)
  })

  test("revision is derived from content and stable across loads", () => {
    const first = service().current
    const second = service().current
    expect(first.revision).toBe(second.revision)
    expect(first.revision).toBe(revisionOf(first.content))
    expect(revisionOf(`${first.content}\n# drift`)).not.toBe(first.revision)
  })

  test("the backend copy matches the plugin seed byte for byte (line endings aside)", () => {
    // the plugin bundles this file as its fallback and the backend pushes the
    // canonical copy; drift between them means two different locales
    const backend = normalizeNewlines(readFileSync(BACKEND_BUNDLE, "utf8"))
    const plugin = normalizeNewlines(readFileSync(PLUGIN_SEED, "utf8"))
    expect(backend).toBe(plugin)
  })

  test("an empty bundle is a broken build", () => {
    const dir = mkdtempSync(join(tmpdir(), "sc-locale-empty-"))
    try {
      const path = join(dir, "ru.yml")
      writeFileSync(path, "\n\n")
      expect(() => service(path)).toThrow(/is empty/)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  test("a bundle above the push ceiling is rejected at boot", () => {
    const dir = mkdtempSync(join(tmpdir(), "sc-locale-big-"))
    try {
      const path = join(dir, "ru.yml")
      writeFileSync(path, `k: "${"x".repeat(MAX_BUNDLE_BYTES)}"\n`)
      expect(() => service(path)).toThrow(/push ceiling/)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})

describe("locale push -> ack seam", () => {
  test("push carries the full bundle and a correlation id", () => {
    const locales = service()
    const { connection, sent } = fakeConnection("game-1")

    const correlationId = locales.pushTo(connection)

    expect(correlationId).not.toBeNull()
    expect(sent).toHaveLength(1)
    const frame = sent[0]!
    expect(frame.type).toBe("LOCALE_PUSH")
    expect(frame.payload).toEqual(locales.current)
    expect(frame.options?.correlationId).toBe(correlationId!)
  })

  test("an accepted ack closes the loop for the server", () => {
    const locales = service()
    const { connection } = fakeConnection("game-1")
    const correlationId = locales.pushTo(connection)!

    const record = locales.recordAck(
      "game-1",
      { locale: "ru", revision: locales.current.revision, accepted: true },
      correlationId,
      1_700_000_000_000,
    )

    expect(record.status).toBe("applied")
    expect(record.correlationId).toBe(correlationId)
    expect(record.receivedAt).toBe(1_700_000_000_000)
    expect(locales.ackFor("game-1")).toEqual(record)
  })

  test("a rejected ack keeps the failure reason", () => {
    const locales = service()
    const { connection } = fakeConnection("game-1")
    locales.pushTo(connection)

    const record = locales.recordAck("game-1", {
      locale: "ru",
      revision: locales.current.revision,
      accepted: false,
      reason: "yaml parse failed",
    })

    expect(record.status).toBe("rejected")
    expect(record.reason).toBe("yaml parse failed")
  })

  test("an ack for a revision this backend no longer serves is stale", () => {
    const locales = service()
    const { connection } = fakeConnection("game-1")
    locales.pushTo(connection)

    const record = locales.recordAck("game-1", {
      locale: "ru",
      revision: "0000000000000000",
      accepted: true,
    })

    expect(record.status).toBe("stale")
  })

  test("an ack without a push on this process is reported, not trusted", () => {
    const locales = service()
    const record = locales.recordAck("game-9", {
      locale: "ru",
      revision: locales.current.revision,
      accepted: true,
    })
    expect(record.status).toBe("unknown-push")
  })

  test("the registered handler records the ack through the wiring", async () => {
    const locales = service()
    const { connection, sent } = fakeConnection("game-1")
    locales.pushTo(connection)
    const registry = new HandlerRegistry(silentLogger())
    registerLocaleHandlers(registry, { locale: locales, logger: silentLogger() })

    await registry.dispatch(
      {
        id: "msg_12345678",
        type: "LOCALE_ACK",
        timestamp: Date.now(),
        correlationId: sent[0]!.options!.correlationId!,
        payload: { locale: "ru", revision: locales.current.revision, accepted: true },
      },
      connection,
    )

    expect(locales.ackFor("game-1")?.status).toBe("applied")
  })
})

describe("locale push over a real connection", () => {
  const SECRET = "a".repeat(32)

  function fakeSocket() {
    const sent: string[] = []
    const socket = {
      sendText: (text: string) => {
        sent.push(text)
      },
      getBufferedAmount: () => 0,
      ping: () => {},
      close: () => {},
      terminate: () => {},
    }
    return { socket: socket as unknown as Bun.ServerWebSocket<GatewaySocketData>, sent }
  }

  function connectionOptions(): ConnectionOptions {
    return {
      secret: SECRET,
      heartbeatIntervalMs: 15_000,
      heartbeatTimeoutMs: 10_000,
      maxPacketBytes: 262_144,
      replayWindowMs: 30_000,
      replayGuardTtlMs: 150_000,
    }
  }

  test("a signed LOCALE_ACK frame routes through the registry and closes the push", async () => {
    const locales = service()
    const registry = new HandlerRegistry(silentLogger())
    registerLocaleHandlers(registry, { locale: locales, logger: silentLogger() })

    const { socket, sent } = fakeSocket()
    const connection = new ServerConnection("game-1", socket, connectionOptions(), silentLogger())

    const correlationId = locales.pushTo(connection)!
    const pushFrame = JSON.parse(sent[0]!) as { type: string; correlationId: string; payload: LocaleBundle }
    expect(pushFrame.type).toBe("LOCALE_PUSH")
    expect(pushFrame.correlationId).toBe(correlationId)
    expect(pushFrame.payload.revision).toBe(locales.current.revision)

    const ackWire = JSON.stringify(
      signEnvelope(
        {
          id: "ackframe0001",
          type: "LOCALE_ACK",
          timestamp: Date.now(),
          correlationId,
          payload: { locale: "ru", revision: locales.current.revision, accepted: true },
        },
        SECRET,
      ),
    )

    const failure = connection.handleFrame(ackWire, Date.now(), (envelope, conn) =>
      registry.dispatch(envelope, conn),
    )
    // wait for the fire-and-forget route to settle
    await Bun.sleep(0)

    expect(failure).toBeNull()
    expect(locales.ackFor("game-1")?.status).toBe("applied")
    expect(locales.ackFor("game-1")?.correlationId).toBe(correlationId)
    // the transport acks the plugin's frame independently of the app-level ack
    const transportAck = JSON.parse(sent[sent.length - 1]!) as { type: string; payload: { ackedId: string } }
    expect(transportAck.type).toBe("ACK")
    expect(transportAck.payload.ackedId).toBe("ackframe0001")
  })
})

describe("locale payload schemas", () => {
  test("LOCALE_PUSH rejects empty content and malformed language tags", () => {
    const valid = service().current
    expect(payloadSchemas.LOCALE_PUSH.safeParse({ ...valid, content: "" }).success).toBe(false)
    expect(payloadSchemas.LOCALE_PUSH.safeParse({ ...valid, locale: "РУ" }).success).toBe(false)
    expect(payloadSchemas.LOCALE_PUSH.safeParse({ ...valid, revision: "" }).success).toBe(false)
  })

  test("LOCALE_ACK requires the decision and keeps the reason optional", () => {
    const base = { locale: "ru", revision: "abcdef0123456789" }
    expect(payloadSchemas.LOCALE_ACK.safeParse({ ...base, accepted: true }).success).toBe(true)
    expect(
      payloadSchemas.LOCALE_ACK.safeParse({ ...base, accepted: false, reason: "no entries" }).success,
    ).toBe(true)
    expect(payloadSchemas.LOCALE_ACK.safeParse(base).success).toBe(false)
  })
})
