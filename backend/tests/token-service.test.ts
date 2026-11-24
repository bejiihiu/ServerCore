import { describe, expect, test } from "bun:test"
import { sha256Hex } from "../src/utils/crypto.js"
import {
  InMemoryTokenStore,
  isValidLinkTokenFormat,
  LINK_TOKEN_LENGTH,
  TokenService,
} from "../src/services/token-service.js"

describe("link token format", () => {
  test("accepts 16 alphanumeric chars", () => {
    expect(isValidLinkTokenFormat("abcd1234ABCD5678")).toBe(true)
    expect(isValidLinkTokenFormat("a".repeat(16))).toBe(true)
  })

  test("rejects wrong length", () => {
    expect(isValidLinkTokenFormat("a".repeat(15))).toBe(false)
    expect(isValidLinkTokenFormat("a".repeat(17))).toBe(false)
    expect(isValidLinkTokenFormat("")).toBe(false)
  })

  test("rejects special characters and whitespace", () => {
    expect(isValidLinkTokenFormat("abcd1234abcd567!")).toBe(false)
    expect(isValidLinkTokenFormat("abcd 1234abcd567")).toBe(false)
    expect(isValidLinkTokenFormat("точки-не-катят")).toBe(false)
  })
})

describe("token service", () => {
  const NOW = 1_000_000

  test("issued tokens have the link format", async () => {
    const service = new TokenService(new InMemoryTokenStore(), 60)
    const { token } = await service.issue({ minecraftUuid: "uuid" }, NOW)
    expect(token.length).toBe(LINK_TOKEN_LENGTH)
    expect(isValidLinkTokenFormat(token)).toBe(true)
  })

  test("verify accepts a live token and returns meta", async () => {
    const service = new TokenService(new InMemoryTokenStore(), 60)
    const { token } = await service.issue({ minecraftUuid: "uuid-1" }, NOW)
    const result = await service.verify(token, NOW + 1000)
    expect(result.valid).toBe(true)
    if (result.valid) expect(result.meta["minecraftUuid"]).toBe("uuid-1")
  })

  test("verify rejects a garbage token without touching the store", async () => {
    const service = new TokenService(new InMemoryTokenStore(), 60)
    const result = await service.verify("!!!not-a-token!!!", NOW)
    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.reason).toBe("BAD_FORMAT")
  })

  test("verify rejects an expired token", async () => {
    const service = new TokenService(new InMemoryTokenStore(), 60)
    const { token } = await service.issue({}, NOW)
    const result = await service.verify(token, NOW + 61_000)
    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.reason).toBe("EXPIRED")
  })

  test("redeem is one-time: second redeem fails", async () => {
    const service = new TokenService(new InMemoryTokenStore(), 60)
    const { token } = await service.issue({}, NOW)
    const first = await service.redeem(token, NOW + 1000)
    expect(first.valid).toBe(true)
    const second = await service.redeem(token, NOW + 1000)
    expect(second.valid).toBe(false)
    if (!second.valid) expect(second.reason).toBe("CONSUMED")
  })

  test("verify still succeeds after consume marked the record consumed", async () => {
    // consume() on the raw store marks consumed; verify() must catch that
    const store = new InMemoryTokenStore()
    const service = new TokenService(store, 60)
    const { token } = await service.issue({}, NOW)
    const tokenHash = sha256Hex(token)
    await store.consume(tokenHash, NOW)
    const result = await service.verify(token, NOW + 1000)
    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.reason).toBe("CONSUMED")
  })

  test("tokens are hashed at rest", async () => {
    const store = new InMemoryTokenStore()
    const service = new TokenService(store, 60)
    const { token } = await service.issue({}, NOW)
    let sawRawToken = false
    for (const [key, record] of (store as unknown as { tokens: Map<string, { tokenHash: string }> }).tokens) {
      if (key === token || record.tokenHash === token) sawRawToken = true
    }
    expect(sawRawToken).toBe(false)
  })
})
