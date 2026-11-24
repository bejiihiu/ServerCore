import { describe, expect, test } from "bun:test"
import { ConfigError, loadConfig } from "../src/config/env.js"

function env(overrides: Record<string, string> = {}): Record<string, string> {
  return {
    NODE_ENV: "test",
    PORT: "8080",
    HOST: "127.0.0.1",
    MONGODB_URI: "mongodb://localhost:27017/servercore-test",
    SERVERCORE_SERVER_CREDENTIALS: JSON.stringify({ "game-1": "s".repeat(32) }),
    ...overrides,
  }
}

describe("config", () => {
  test("applies defaults to an empty env", () => {
    const config = loadConfig({})
    expect(config.port).toBe(8080)
    expect(config.wsPort).toBe(8081)
    expect(config.host).toBe("0.0.0.0")
    expect(config.env).toBe("development")
    expect(config.logLevel).toBe("info")
    expect(config.wsHeartbeatIntervalMs).toBe(15_000)
    expect(config.authRequestTtlSeconds).toBe(75)
    expect(config.serverCredentials).toEqual({})
    expect(config.botToken).toBeUndefined()
  })

  test("parses a valid env with defaults", () => {
    const config = loadConfig(env())
    expect(config.env).toBe("test")
    expect(config.port).toBe(8080)
    expect(config.wsHeartbeatIntervalMs).toBe(15_000)
    expect(config.authRequestTtlSeconds).toBe(75)
    expect(config.botToken).toBeUndefined()
  })

  test("parses server credentials json", () => {
    const config = loadConfig(env())
    expect(config.serverCredentials["game-1"]).toBe("s".repeat(32))
  })

  test("rejects secrets shorter than 32 chars", () => {
    expect(() =>
      loadConfig(env({ SERVERCORE_SERVER_CREDENTIALS: JSON.stringify({ "game-1": "short" }) })),
    ).toThrow(ConfigError)
  })

  test("rejects server ids with bad characters", () => {
    expect(() =>
      loadConfig(env({ SERVERCORE_SERVER_CREDENTIALS: JSON.stringify({ "GAME ONE": "s".repeat(32) }) })),
    ).toThrow(ConfigError)
  })

  test("rejects invalid credentials json", () => {
    expect(() => loadConfig(env({ SERVERCORE_SERVER_CREDENTIALS: "{not json" }))).toThrow(ConfigError)
  })

  test("rejects a non-numeric port", () => {
    expect(() => loadConfig(env({ PORT: "http" }))).toThrow(ConfigError)
  })

  test("rejects an unknown log level", () => {
    expect(() => loadConfig(env({ LOG_LEVEL: "loud" }))).toThrow(ConfigError)
  })

  test("rejects an out-of-band auth ttl", () => {
    expect(() => loadConfig(env({ AUTH_REQUEST_TTL_SECONDS: "120" }))).toThrow(ConfigError)
  })

  test("coerces numeric strings for numeric fields", () => {
    const config = loadConfig(env({ PORT: "9999", WS_MAX_PACKET_BYTES: "65536" }))
    expect(config.port).toBe(9999)
    expect(config.wsMaxPacketBytes).toBe(65_536)
  })

  test("accepts a bot token when present", () => {
    const config = loadConfig(env({ BOT_TOKEN: "123:abc" }))
    expect(config.botToken).toBe("123:abc")
  })

  test("boolean flags read 'false' as false, not truthy-string", () => {
    // z.coerce.boolean would make Boolean("false") === true
    const off = loadConfig(env({ VOTE_BUTTON_ICONS_ENABLED: "false" }))
    expect(off.voteButtonIcons.enabled).toBe(false)
    const on = loadConfig(env({ VOTE_BUTTON_ICONS_ENABLED: "true" }))
    expect(on.voteButtonIcons.enabled).toBe(true)
  })
})
