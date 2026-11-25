import { describe, expect, test } from "bun:test"
import { ServerConnection } from "../src/websocket/connection.js"
import { WS_NOW as NOW, connectionOptions as options, fakeSocket, signedFrame, silentPino } from "./helpers/ws-harness.js"

const UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5"

function guestStartedPayload(sessionId = "guest-abc123"): Record<string, unknown> {
  return {
    minecraftUuid: UUID,
    username: "bejiihiu",
    sessionId,
    snapshot: {
      world: "world", x: 0, y: 64, z: 0, yaw: 0, pitch: 0,
      gameMode: "SURVIVAL", flying: false, flySpeed: 0.1, walkSpeed: 0.2,
    },
  }
}

describe("connection-level dedupe of redelivered frames", () => {
  test("a redelivered frame is acked again but processed exactly once", () => {
    const fake = fakeSocket()
    const connection = new ServerConnection("game-1", fake.socket, options(), silentPino())
    const routed: string[] = []
    const raw = signedFrame("GUEST_STARTED", guestStartedPayload(), "frame-0001")

    const first = connection.handleFrame(raw, NOW, async (envelope) => {
      routed.push(envelope.id)
    })
    expect(first).toBeNull()
    expect(routed).toEqual(["frame-0001"])

    // the exact same bytes arrive again: lost-ack redelivery from the plugin
    const second = connection.handleFrame(raw, NOW, async (envelope) => {
      routed.push(envelope.id)
    })
    expect(second).toBeNull()
    expect(routed).toEqual(["frame-0001"])
    // both deliveries were acked so the sender can retire the frame
    const acks = fake.sent.filter((text) => text.includes('"ackedId":"frame-0001"'))
    expect(acks).toHaveLength(2)
    expect(fake.isClosed()).toBe(false)
  })

  test("distinct ids still route normally next to duplicates", () => {
    const fake = fakeSocket()
    const connection = new ServerConnection("game-1", fake.socket, options(), silentPino())
    const routed: string[] = []
    const route = async (envelope: { id: string }) => {
      routed.push(envelope.id)
    }

    connection.handleFrame(signedFrame("GUEST_STARTED", guestStartedPayload("guest-a"), "frame-0001"), NOW, route)
    connection.handleFrame(signedFrame("GUEST_STARTED", guestStartedPayload("guest-b"), "frame-0002"), NOW, route)
    connection.handleFrame(signedFrame("GUEST_STARTED", guestStartedPayload("guest-a"), "frame-0001"), NOW, route)

    expect(routed).toEqual(["frame-0001", "frame-0002"])
  })

  test("a duplicate of a fire-and-forget type gets no ack loop", () => {
    const fake = fakeSocket()
    const connection = new ServerConnection("game-1", fake.socket, options(), silentPino())
    const raw = signedFrame("SERVER_HEARTBEAT", { onlinePlayers: 1, maxPlayers: 20 }, "hb-0000001")
    connection.handleFrame(raw, NOW, async () => {})
    connection.handleFrame(raw, NOW, async () => {})
    // heartbeats are acked by design (plugin liveness signal), but exactly one
    // ack per delivery, not an echo chamber
    expect(fake.sent.filter((text) => text.includes('"ackedId":"hb-0000001"'))).toHaveLength(2)
  })
})

describe("ack correlation", () => {
  test("awaitAck resolves when the peer's ack arrives", async () => {
    const fake = fakeSocket()
    const connection = new ServerConnection("game-1", fake.socket, options(), silentPino())
    const pending = connection.send(
      "AUTH_REQUEST",
      { minecraftUuid: UUID, username: "bejiihiu", sessionId: "auth-abc123" },
      { awaitAck: true, correlationId: "corr-00001" },
    )
    const ack = signedFrame("ACK", { ackedId: "x".repeat(12) }, "ackframe01", "corr-00001")
    const failure = connection.handleFrame(ack, NOW, async () => {})
    expect(failure).toBeNull()
    await pending
  })

  test("connection close rejects a pending ack, the caller parks the push", async () => {
    const fake = fakeSocket()
    const connection = new ServerConnection("game-1", fake.socket, options(), silentPino())
    const pending = connection.send(
      "AUTH_APPROVED",
      { minecraftUuid: UUID, sessionId: "auth-abc123" },
      { awaitAck: true, correlationId: "corr-00002" },
    )
    connection.close(1001, "test shutdown")
    await expect(pending).rejects.toThrow("connection closed")
  })
})

describe("graceful drain", () => {
  test("drain resolves as soon as in-flight handlers finish", async () => {
    const fake = fakeSocket()
    const connection = new ServerConnection("game-1", fake.socket, options(), silentPino())
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })

    connection.handleFrame(signedFrame("GUEST_STARTED", guestStartedPayload(), "frame-0003"), NOW, () => gate)
    expect(connection.inFlightHandlers).toBe(1)

    const draining = connection.drain(5_000)
    release()
    await draining
    expect(connection.inFlightHandlers).toBe(0)
  })

  test("drain gives up after the timeout instead of hanging forever", async () => {
    const fake = fakeSocket()
    const connection = new ServerConnection("game-1", fake.socket, options(), silentPino())
    connection.handleFrame(signedFrame("GUEST_STARTED", guestStartedPayload(), "frame-0004"), NOW, () => {
      return new Promise<void>(() => {}) // never resolves
    })
    const started = Date.now()
    await connection.drain(120)
    expect(Date.now() - started).toBeGreaterThanOrEqual(100)
    expect(connection.inFlightHandlers).toBe(1)
  })
})

