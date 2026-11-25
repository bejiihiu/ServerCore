import { describe, expect, test } from "bun:test"
import { loadConfig } from "../src/config/env.js"

/**
 * the presets under examples/ are shipped documentation, and documentation that
 * cannot boot is worse than none. every env file is parsed exactly the way
 * docker compose and systemd read it (one KEY=value per line, '#' only on its
 * own line) and pushed through the real config loader.
 */

const EXAMPLE_DIR = new URL("../../examples/backend/", import.meta.url)
const PLUGIN_EXAMPLE_DIR = new URL("../../examples/plugin/", import.meta.url)

async function readExample(name: string): Promise<string> {
  return Bun.file(new URL(name, EXAMPLE_DIR)).text()
}

/** the same parse the backend does at boot, minus dotenv's file plumbing */
function parseEnvFile(text: string): Record<string, string> {
  const parsed: Record<string, string> = {}
  for (const line of text.split(/\r?\n/)) {
    const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line)
    const key = match?.[1]
    if (key) parsed[key] = match?.[2] ?? ""
  }
  return parsed
}

async function documentedKeys(): Promise<string[]> {
  const text = await Bun.file(new URL("../.env.example", import.meta.url)).text()
  return Object.keys(parseEnvFile(text))
}

/** the plugin preset's backend.secret, so the pairs can be checked as pairs */
async function pluginPresetSecret(name: string): Promise<string | undefined> {
  const text = await Bun.file(new URL(name, PLUGIN_EXAMPLE_DIR)).text()
  return /^ {2}secret: "([^"]+)"/m.exec(text)?.[1]
}

describe("example env presets", () => {
  test("dev.env parses with the real loader", async () => {
    const parsed = parseEnvFile(await readExample("dev.env"))
    const config = loadConfig(parsed)

    expect(config.env).toBe("development")
    // local stack, loopback only
    expect(config.host).toBe("127.0.0.1")
    expect(config.mongoUri).toContain("127.0.0.1")
    // verbose logging is the whole point of the dev preset
    expect(config.logLevel).toBe("debug")
    // short testing windows, not the 3-hour product value
    expect(config.votingDurationMs).toBe(5 * 60_000)
    expect(config.authRequestTtlSeconds).toBe(60)
    expect(config.retentionAuditDays).toBe(7)
    expect(config.retentionSessionDays).toBe(7)
  })

  test("production.env parses with the real loader", async () => {
    const parsed = parseEnvFile(await readExample("production.env"))
    const config = loadConfig(parsed)

    expect(config.env).toBe("production")
    // values the product is built around
    expect(config.logTimezone).toBe("Europe/Moscow")
    expect(config.votingDurationMs).toBe(180 * 60_000)
    expect(config.retentionAuditDays).toBe(90)
    expect(config.retentionSessionDays).toBe(60)
    expect(config.groupInviteTtlMs).toBe(48 * 3_600_000)
    expect(config.logLevel).toBe("info")
    // a production preset that boots but cannot admit anyone would be a trap:
    // BOT_TOKEN and GROUP_CHAT_ID stay empty on purpose (called out with
    // CHANGE_ME in the file), so the placeholder secret must at least satisfy
    // the 32-character rule or the file would not boot at all
    expect(config.serverCredentials["game-1"]?.length ?? 0).toBeGreaterThanOrEqual(32)
  })

  test("every key in both presets exists in the documented schema", async () => {
    const known = new Set(await documentedKeys())
    for (const file of ["dev.env", "production.env"]) {
      const unknown = Object.keys(parseEnvFile(await readExample(file))).filter((key) => !known.has(key))
      expect({ file, unknown }).toEqual({ file, unknown: [] })
    }
  })

  test("production.env carries every documented key", async () => {
    // an operator copying the preset should never have to guess a missing key,
    // and a new key added to the schema should show up here
    const parsed = parseEnvFile(await readExample("production.env"))
    for (const key of await documentedKeys()) {
      expect(key in parsed).toBe(true)
    }
  })

  test("the plugin presets share the backend preset secret", async () => {
    // a preset pair is meant to be copied together; if the placeholder secrets
    // drift, the first boot ends in a signature mismatch that looks like a bug
    const dev = loadConfig(parseEnvFile(await readExample("dev.env")))
    const prod = loadConfig(parseEnvFile(await readExample("production.env")))
    for (const preset of ["small-server.yml", "large-server.yml"]) {
      const secret = await pluginPresetSecret(preset)
      expect(secret).toBe(dev.serverCredentials["game-1"] as string)
      expect(secret).toBe(prod.serverCredentials["game-1"] as string)
      expect(secret?.length ?? 0).toBeGreaterThanOrEqual(32)
    }
  })
})
