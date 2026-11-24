import { describe, expect, test } from "bun:test"
import { envelopeMetaSchema, payloadSchemas } from "../src/websocket/protocol/message-types.js"

const UUID = "0f4d2f3e-8a44-4c21-9d0e-3b1f2a4c5d60"

describe("envelope meta schema", () => {
  const base = {
    id: "a1b2c3d4e5f6",
    type: "SERVER_HEARTBEAT",
    timestamp: Date.now(),
  }

  test("accepts a minimal envelope", () => {
    const result = envelopeMetaSchema.safeParse(base)
    expect(result.success).toBe(true)
  })

  test("accepts an envelope with correlation id", () => {
    const result = envelopeMetaSchema.safeParse({ ...base, correlationId: "corr12345678" })
    expect(result.success).toBe(true)
  })

  test("rejects unknown message types", () => {
    const result = envelopeMetaSchema.safeParse({ ...base, type: "SOMETHING_ELSE" })
    expect(result.success).toBe(false)
  })

  test("rejects short ids", () => {
    const result = envelopeMetaSchema.safeParse({ ...base, id: "short" })
    expect(result.success).toBe(false)
  })

  test("rejects non-integer timestamps", () => {
    const result = envelopeMetaSchema.safeParse({ ...base, timestamp: 123.45 })
    expect(result.success).toBe(false)
  })

  test("rejects ids with special characters", () => {
    const result = envelopeMetaSchema.safeParse({ ...base, id: "bad id with spaces!!" })
    expect(result.success).toBe(false)
  })
})

describe("payload schemas", () => {
  test("PLAYER_JOINED requires uuid + username", () => {
    expect(payloadSchemas.PLAYER_JOINED.safeParse({ minecraftUuid: UUID, username: "bejiihiu" }).success).toBe(true)
    expect(payloadSchemas.PLAYER_JOINED.safeParse({ minecraftUuid: UUID }).success).toBe(false)
  })

  test("PLAYER_JOINED rejects a bad uuid", () => {
    const result = payloadSchemas.PLAYER_JOINED.safeParse({ minecraftUuid: "not-a-uuid", username: "bejiihiu" })
    expect(result.success).toBe(false)
  })

  test("PLAYER_JOINED rejects an invalid username", () => {
    const result = payloadSchemas.PLAYER_JOINED.safeParse({ minecraftUuid: UUID, username: "has space" })
    expect(result.success).toBe(false)
  })

  test("PLAYER_LEFT defaults reason to DISCONNECT", () => {
    const result = payloadSchemas.PLAYER_LEFT.safeParse({ minecraftUuid: UUID })
    expect(result.success).toBe(true)
    if (result.success) expect(result.data.reason).toBe("DISCONNECT")
  })

  test("PLAYER_LEFT rejects an unknown reason", () => {
    const result = payloadSchemas.PLAYER_LEFT.safeParse({ minecraftUuid: UUID, reason: "TELEPORTED" })
    expect(result.success).toBe(false)
  })

  test("GUEST_STARTED validates the snapshot block", () => {
    const payload = {
      minecraftUuid: UUID,
      username: "bejiihiu",
      sessionId: "game-1",
      snapshot: {
        world: "world",
        x: 1.5,
        y: 64,
        z: -3.25,
        yaw: 90,
        pitch: 0,
        gameMode: "SURVIVAL",
        flying: false,
        flySpeed: 0.1,
        walkSpeed: 0.2,
      },
    }
    expect(payloadSchemas.GUEST_STARTED.safeParse(payload).success).toBe(true)
    expect(payloadSchemas.GUEST_STARTED.safeParse({ ...payload, snapshot: { ...payload.snapshot, world: "" } }).success).toBe(false)
    expect(payloadSchemas.GUEST_STARTED.safeParse({ ...payload, snapshot: { ...payload.snapshot, flySpeed: 99 } }).success).toBe(false)
  })

  test("AUTH_REQUEST requires uuid, username and session", () => {
    const payload = { minecraftUuid: UUID, username: "bejiihiu", sessionId: "game-1" }
    expect(payloadSchemas.AUTH_REQUEST.safeParse(payload).success).toBe(true)
    expect(payloadSchemas.AUTH_REQUEST.safeParse({ ...payload, sessionId: "" }).success).toBe(false)
  })

  test("APPLICATION_* validate objectid-shaped ids", () => {
    const app = { applicationId: "64b1f0c0a1b2c3d4e5f60718", minecraftUuid: UUID }
    expect(payloadSchemas.APPLICATION_SUBMITTED.safeParse({ ...app, telegramId: "12345" }).success).toBe(true)
    expect(payloadSchemas.APPLICATION_SUBMITTED.safeParse({ ...app, telegramId: "" }).success).toBe(false)
    expect(payloadSchemas.APPLICATION_APPROVED.safeParse({ ...app, applicationId: "zzz" }).success).toBe(false)
  })

  test("SERVER_STATUS requires counts", () => {
    expect(payloadSchemas.SERVER_STATUS.safeParse({ onlinePlayers: 3, maxPlayers: 40 }).success).toBe(true)
    expect(payloadSchemas.SERVER_STATUS.safeParse({ onlinePlayers: -1, maxPlayers: 40 }).success).toBe(false)
    expect(payloadSchemas.SERVER_STATUS.safeParse({ onlinePlayers: 1.5, maxPlayers: 40 }).success).toBe(false)
  })

  test("SERVER_HELLO validates the server id format", () => {
    expect(payloadSchemas.SERVER_HELLO.safeParse({ serverId: "game-1", clientNonce: "nonce123456" }).success).toBe(true)
    expect(payloadSchemas.SERVER_HELLO.safeParse({ serverId: "GAME 1!", clientNonce: "nonce123456" }).success).toBe(false)
  })

  test("ACK requires the acked id", () => {
    expect(payloadSchemas.ACK.safeParse({ ackedId: "a1b2c3d4e5f6" }).success).toBe(true)
    expect(payloadSchemas.ACK.safeParse({}).success).toBe(false)
  })

  test("ERROR payload shape", () => {
    expect(payloadSchemas.ERROR.safeParse({ code: "BAD_HMAC", message: "nope" }).success).toBe(true)
    expect(payloadSchemas.ERROR.safeParse({ code: "BAD_HMAC" }).success).toBe(false)
  })
})
