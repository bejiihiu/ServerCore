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

/** runs the loader and returns the ConfigError message, failing if it accepted the env */
function rejectionMessage(overrides: Record<string, string>): string {
  try {
    loadConfig(env(overrides))
  } catch (error) {
    if (error instanceof ConfigError) return error.message
    throw error
  }
  throw new Error("expected the config to be rejected")
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

  test("an empty BOT_TOKEN means no bot instead of a validation error", () => {
    // dotenv keeps a bare `BOT_TOKEN=` as an empty string; the docs promise
    // "leave empty to run without the bot"
    expect(loadConfig(env({ BOT_TOKEN: "" })).botToken).toBeUndefined()
  })

  test("an empty AUTH_MESSAGE_EFFECT_ID means no effect", () => {
    expect(loadConfig(env({ AUTH_MESSAGE_EFFECT_ID: "" })).authMessageEffectId).toBeUndefined()
  })

  test("the questionnaire idle window defaults to 30 minutes of silence", () => {
    expect(loadConfig(env()).applyIdleTimeoutMs).toBe(30 * 60_000)
    expect(loadConfig(env({ APPLY_IDLE_TIMEOUT_MINUTES: "5" })).applyIdleTimeoutMs).toBe(5 * 60_000)
  })

  test("rejects an idle window of zero or more than a day", () => {
    expect(() => loadConfig(env({ APPLY_IDLE_TIMEOUT_MINUTES: "0" }))).toThrow(ConfigError)
    expect(() => loadConfig(env({ APPLY_IDLE_TIMEOUT_MINUTES: "1441" }))).toThrow(ConfigError)
  })
})

describe("config validation messages", () => {
  test("names MONGODB_URI when the connection string is blank", () => {
    const message = rejectionMessage({ MONGODB_URI: "" })
    expect(message).toContain("MONGODB_URI")
  })

  test("reports every bad key at once instead of stopping at the first", () => {
    const message = rejectionMessage({ MONGODB_URI: "", WS_PORT: "nope" })
    expect(message).toContain("MONGODB_URI")
    expect(message).toContain("WS_PORT")
  })

  test("names WS_PORT for a non-numeric value", () => {
    expect(rejectionMessage({ WS_PORT: "http" })).toContain("WS_PORT")
  })

  test("names WS_PORT for an out-of-range value", () => {
    expect(rejectionMessage({ WS_PORT: "70000" })).toContain("WS_PORT")
  })

  test("explains malformed SERVERCORE_SERVER_CREDENTIALS json", () => {
    const message = rejectionMessage({ SERVERCORE_SERVER_CREDENTIALS: "{oops" })
    expect(message).toContain("SERVERCORE_SERVER_CREDENTIALS")
    expect(message).toContain("not valid json")
  })

  test("explains a server secret shorter than 32 characters", () => {
    const message = rejectionMessage({
      SERVERCORE_SERVER_CREDENTIALS: JSON.stringify({ "game-1": "short" }),
    })
    expect(message).toContain("SERVERCORE_SERVER_CREDENTIALS")
  })

  test("names LOG_TIMEZONE for an unknown IANA zone", () => {
    const message = rejectionMessage({ LOG_TIMEZONE: "Mars/Olympus" })
    expect(message).toContain("LOG_TIMEZONE")
    expect(message).toContain("IANA")
  })

  test("accepts a valid IANA zone", () => {
    expect(loadConfig(env({ LOG_TIMEZONE: "Europe/Berlin" })).logTimezone).toBe("Europe/Berlin")
  })

  test("reports mismatched questionnaire lengths", () => {
    const message = rejectionMessage({
      APPLICATION_QUESTIONS_RU: JSON.stringify(["one question"]),
      APPLICATION_QUESTIONS_EN: JSON.stringify(["one", "two"]),
    })
    expect(message).toContain("same length")
  })

  test("explains a malformed questionnaire list", () => {
    const message = rejectionMessage({ APPLICATION_QUESTIONS_RU: "not json" })
    expect(message).toContain("APPLICATION_QUESTIONS_RU")
    expect(message).toContain("not valid json")
  })

  test("the shipped .env.example parses as a valid config", async () => {
    const text = await Bun.file(new URL("../.env.example", import.meta.url)).text()
    const parsed: Record<string, string> = {}
    for (const line of text.split(/\r?\n/)) {
      const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line)
      const key = match?.[1]
      if (key) parsed[key] = match?.[2] ?? ""
    }
    const config = loadConfig(parsed)
    // the placeholder secret must already satisfy the 32-char rule, otherwise
    // copying the example verbatim would fail the boot
    expect(Object.keys(config.serverCredentials)).toEqual(["game-1"])
    expect(config.serverCredentials["game-1"]?.length ?? 0).toBeGreaterThanOrEqual(32)
  })
})
