import { describe, expect, test } from "bun:test"
import { payloadSchemas } from "../src/websocket/protocol/message-types.js"

const UUID = "0f4d2f3e-8a44-4c21-9d0e-3b1f2a4c5d60"

describe("account linking message payloads", () => {
  test("LINK_TOKEN accepts a 16-char token with expiry", () => {
    const result = payloadSchemas.LINK_TOKEN.safeParse({
      minecraftUuid: UUID,
      username: "steve",
      token: "abcd1234ABCD5678",
      expiresAt: Date.now() + 60_000,
    })
    expect(result.success).toBe(true)
  })

  test("LINK_TOKEN rejects tokens of the wrong length or charset", () => {
    for (const token of ["short", "abcd1234ABCD567!", "abcd 1234ABCD567"]) {
      const result = payloadSchemas.LINK_TOKEN.safeParse({
        minecraftUuid: UUID,
        username: "steve",
        token,
        expiresAt: Date.now(),
      })
      expect(result.success).toBe(false)
    }
  })

  test("SESSION_CHECK carries just the uuid", () => {
    const result = payloadSchemas.SESSION_CHECK.safeParse({ minecraftUuid: UUID })
    expect(result.success).toBe(true)
    const bad = payloadSchemas.SESSION_CHECK.safeParse({ minecraftUuid: "not-a-uuid" })
    expect(bad.success).toBe(false)
  })

  test("SESSION_CHECK_RESULT online without session id", () => {
    const result = payloadSchemas.SESSION_CHECK_RESULT.safeParse({ minecraftUuid: UUID, online: false })
    expect(result.success).toBe(true)
  })

  test("SESSION_CHECK_RESULT online with session id and kind", () => {
    const result = payloadSchemas.SESSION_CHECK_RESULT.safeParse({
      minecraftUuid: UUID,
      online: true,
      sessionId: "sess-1",
      sessionKind: "GUEST",
    })
    expect(result.success).toBe(true)
    expect(result.data?.sessionId).toBe("sess-1")
  })

  test("SESSION_CHECK_RESULT rejects unknown session kinds", () => {
    const result = payloadSchemas.SESSION_CHECK_RESULT.safeParse({
      minecraftUuid: UUID,
      online: true,
      sessionKind: "SPECTATING",
    })
    expect(result.success).toBe(false)
  })

  test("ACCOUNT_DELETED carries the deletion reason", () => {
    const result = payloadSchemas.ACCOUNT_DELETED.safeParse({ minecraftUuid: UUID, reason: "SELF_REQUESTED" })
    expect(result.success).toBe(true)
    const bad = payloadSchemas.ACCOUNT_DELETED.safeParse({ minecraftUuid: UUID, reason: "BECAUSE" })
    expect(bad.success).toBe(false)
  })
})
