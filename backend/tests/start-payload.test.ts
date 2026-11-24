import { describe, expect, test } from "bun:test"
import { parseStartPayload } from "../src/bot/commands/start-payload.js"

describe("start deep-link payload", () => {
  test("accepts a clean 16-char token", () => {
    const result = parseStartPayload("AbCdEf1234567890")
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.token).toBe("AbCdEf1234567890")
  })

  test("trims surrounding whitespace", () => {
    const result = parseStartPayload("  AbCdEf1234567890 \n")
    expect(result.ok).toBe(true)
  })

  test("rejects a missing payload", () => {
    const result = parseStartPayload("")
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.reason).toBe("MISSING")
  })

  test("rejects a too-short payload", () => {
    const result = parseStartPayload("short")
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.reason).toBe("BAD_FORMAT")
  })

  test("rejects payloads with special characters", () => {
    const result = parseStartPayload("has/dots&chars!!")
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.reason).toBe("BAD_FORMAT")
  })

  test("rejects an overlong payload", () => {
    const result = parseStartPayload("a".repeat(64))
    expect(result.ok).toBe(false)
  })
})
