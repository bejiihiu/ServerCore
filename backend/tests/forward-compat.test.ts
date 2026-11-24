import { describe, expect, test } from "bun:test"
import type { Logger } from "../src/logging/logger.js"
import type { MessageType } from "../src/websocket/protocol/message-types.js"
import { signEnvelope, verifyWireMessage } from "../src/websocket/protocol/signing.js"
import { ServerConnection, type ConnectionOptions, type GatewaySocketData } from "../src/websocket/connection.js"

const SECRET = "a".repeat(32)
const NOW = 1_700_000_000_000
const UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5"

/** minimal stand-in for Bun's server socket: records writes, never throws */
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

function options(): ConnectionOptions {
  return {
    secret: SECRET,
    heartbeatIntervalMs: 15_000,
    heartbeatTimeoutMs: 10_000,
    maxPacketBytes: 262_144,
    replayWindowMs: 30_000,
    replayGuardTtlMs: 150_000,
  }
}

type LogRecord = { obj: Record<string, unknown>; msg: string }

/** logger that records only what the connection emits on the warn channel */
function recordingLogger(): { logger: Logger; warnings: LogRecord[] } {
  const warnings: LogRecord[] = []
  const logger = {
    debug: () => undefined,
    info: () => undefined,
    warn: (obj: Record<string, unknown>, msg: string) => {
      warnings.push({ obj, msg })
    },
    error: () => undefined,
  }
  return { logger: logger as unknown as Logger, warnings }
}

/** a frame signed with the real secret but carrying a type this build has never seen */
function signedFrame(type: string, payload: Record<string, unknown>, id: string): string {
  return JSON.stringify(
    signEnvelope({ id, type: type as MessageType, timestamp: NOW, payload }, SECRET),
  )
}

describe("verification of unknown wire types", () => {
  const verifyOptions = { maxSkewMs: 30_000, maxPacketBytes: 262_144 }

  test("a validly signed unknown type verifies as unknown, not as a failure", () => {
    const result = verifyWireMessage(
      signedFrame("FUTURE_EVENT", { payloadVersion: 2 }, "frame-0001"),
      SECRET,
      NOW,
      verifyOptions,
    )
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.known).toBe(false)
      expect(result.envelope.type).toBe("FUTURE_EVENT")
      expect(result.envelope.payload).toStrictEqual({ payloadVersion: 2 })
    }
  })

  test("an unknown type with a bad hmac is still a forgery", () => {
    const wire = JSON.parse(signedFrame("FUTURE_EVENT", {}, "frame-0002")) as Record<string, unknown>
    wire["hmac"] = "0".repeat(64)
    const result = verifyWireMessage(JSON.stringify(wire), SECRET, NOW, verifyOptions)
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("BAD_HMAC")
  })

  test("a known type keeps its payload validation", () => {
    const result = verifyWireMessage(
      signedFrame("SERVER_HEARTBEAT", { onlinePlayers: -1, maxPlayers: 20 }, "frame-0003"),
      SECRET,
      NOW,
      verifyOptions,
    )
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.failure.reason).toBe("BAD_PAYLOAD")
  })
})

describe("connection survives unknown wire types", () => {
  test("acks an unknown type, routes nothing and stays alive", () => {
    const fake = fakeSocket()
    const { logger } = recordingLogger()
    const connection = new ServerConnection("game-1", fake.socket, options(), logger)
    const routed: string[] = []

    const failure = connection.handleFrame(
      signedFrame("FUTURE_EVENT", { payloadVersion: 2 }, "frame-0010"),
      NOW,
      async (envelope) => {
        routed.push(envelope.id)
      },
    )

    // a null failure is exactly the signal the gateway uses to keep the socket open
    expect(failure).toBeNull()
    expect(connection.isAlive).toBe(true)
    expect(routed).toEqual([])
    expect(fake.sent).toHaveLength(1)
    const ack = JSON.parse(fake.sent[0]!) as { type: string; payload: { ackedId: string } }
    expect(ack.type).toBe("ACK")
    expect(ack.payload.ackedId).toBe("frame-0010")
  })

  test("the first unknown type logs once, later frames of it stay quiet", () => {
    const fake = fakeSocket()
    const { logger, warnings } = recordingLogger()
    const connection = new ServerConnection("game-1", fake.socket, options(), logger)
    const routed: string[] = []
    const route = async (envelope: { id: string }) => {
      routed.push(envelope.id)
    }

    connection.handleFrame(signedFrame("FUTURE_EVENT", {}, "frame-0011"), NOW, route)
    connection.handleFrame(signedFrame("FUTURE_EVENT", {}, "frame-0012"), NOW, route)
    connection.handleFrame(signedFrame("FUTURE_EVENT", {}, "frame-0011"), NOW, route)

    // one log for the type, one ack per delivery (the third is a redelivery)
    expect(warnings.filter((w) => w.msg === "unknown message type, acked and ignored")).toHaveLength(1)
    expect(fake.sent.filter((text) => text.includes('"ackedId":"frame-0011"'))).toHaveLength(2)
    expect(fake.sent.filter((text) => text.includes('"ackedId":"frame-0012"'))).toHaveLength(1)
    expect(routed).toEqual([])
    expect(connection.isAlive).toBe(true)
  })

  test("a different unknown type gets its own single log line", () => {
    const fake = fakeSocket()
    const { logger, warnings } = recordingLogger()
    const connection = new ServerConnection("game-1", fake.socket, options(), logger)

    connection.handleFrame(signedFrame("FUTURE_EVENT", {}, "frame-0013"), NOW, async () => {})
    connection.handleFrame(signedFrame("ANOTHER_FUTURE_EVENT", {}, "frame-0014"), NOW, async () => {})

    expect(warnings.filter((w) => w.msg === "unknown message type, acked and ignored")).toHaveLength(2)
  })

  test("known frames keep routing on the same connection after an unknown one", () => {
    const fake = fakeSocket()
    const { logger } = recordingLogger()
    const connection = new ServerConnection("game-1", fake.socket, options(), logger)
    const routed: string[] = []
    const route = async (envelope: { id: string }) => {
      routed.push(envelope.id)
    }

    connection.handleFrame(signedFrame("FUTURE_EVENT", {}, "frame-0015"), NOW, route)
    connection.handleFrame(
      signedFrame("PLAYER_JOINED", { minecraftUuid: UUID, username: "bejiihiu" }, "frame-0016"),
      NOW,
      route,
    )

    expect(routed).toEqual(["frame-0016"])
    expect(connection.isAlive).toBe(true)
  })

  test("an unknown type with a bad hmac still fails the frame", () => {
    const fake = fakeSocket()
    const { logger } = recordingLogger()
    const connection = new ServerConnection("game-1", fake.socket, options(), logger)
    const routed: string[] = []
    const wire = JSON.parse(signedFrame("FUTURE_EVENT", {}, "frame-0017")) as Record<string, unknown>
    wire["hmac"] = "f".repeat(64)

    const failure = connection.handleFrame(JSON.stringify(wire), NOW, async (envelope) => {
      routed.push(envelope.id)
    })

    expect(failure?.reason).toBe("BAD_HMAC")
    expect(routed).toEqual([])
  })
})
