import { describe, expect, test } from "bun:test"
import { t, DEFAULT_LOCALE, isLocale } from "../src/services/i18n.js"
import { eventForState, OpsNotifier } from "../src/services/ops-notifier.js"
import { silentLogger } from "../src/websocket/handlers/registry.js"
import type { NotificationService } from "../src/services/notification-service.js"

describe("i18n ops keys", () => {
  test("every key resolves in both locales with params interpolated", () => {
    const ru = t("ru", "ops.admin.offline_alert", { server: "game-1", minutes: 7 })
    expect(ru).toContain("game-1")
    expect(ru).toContain("7")
    expect(ru).not.toContain("{server}")

    const en = t("en", "ops.admin.offline_alert", { server: "game-1", minutes: 7 })
    expect(en).toContain("game-1")
    expect(en).not.toContain("{minutes}")
  })

  test("default locale is ru", () => {
    expect(DEFAULT_LOCALE).toBe("ru")
  })

  test("locale guard accepts exactly ru and en", () => {
    expect(isLocale("ru")).toBe(true)
    expect(isLocale("en")).toBe(true)
    expect(isLocale("de")).toBe(false)
  })

  test("user-facing texts contain no placeholder residue", () => {
    for (const key of ["ops.server.starting", "ops.server.running", "ops.server.restarting", "ops.server.stopping", "ops.server.offline", "ops.server.update_ready"] as const) {
      for (const locale of ["ru", "en"] as const) {
        const text = t(locale, key)
        expect(text).not.toMatch(/\{[a-z]+\}/)
      }
    }
  })
})

describe("eventForState", () => {
  test("RESTARTING with the SCHEDULED detail maps to the scheduled event", () => {
    expect(eventForState("RESTARTING", "SCHEDULED")).toBe("SCHEDULED_RESTART")
    expect(eventForState("RESTARTING", undefined)).toBe("RESTARTING")
    expect(eventForState("RESTARTING", "other")).toBe("RESTARTING")
  })

  test("everything else maps 1:1", () => {
    expect(eventForState("STARTING")).toBe("STARTING")
    expect(eventForState("RUNNING")).toBe("RUNNING")
    expect(eventForState("STOPPING")).toBe("STOPPING")
    expect(eventForState("OFFLINE")).toBe("OFFLINE")
    expect(eventForState("UPDATE_READY")).toBe("UPDATE_READY")
  })
})

describe("OpsNotifier dedupe", () => {
  function notifierWithSpy() {
    const broadcasts: { text: string; admins: string[] }[] = []
    const fake = {
      broadcast: async (text: string, admins: string[] = []) => {
        broadcasts.push({ text, admins: [...admins] })
        return { recipients: 1, sent: 1, failed: 0 }
      },
    } as unknown as NotificationService
    const notifier = new OpsNotifier({
      notifications: fake,
      adminChatIds: ["42"],
      locale: "ru",
      logger: silentLogger(),
    })
    return { notifier, broadcasts }
  }

  test("repeated identical events are suppressed", async () => {
    const { notifier, broadcasts } = notifierWithSpy()
    await notifier.announce("game-1", "RUNNING")
    await notifier.announce("game-1", "RUNNING")
    await notifier.announce("game-1", "RUNNING")
    expect(broadcasts).toHaveLength(1)
  })

  test("a different event goes out, and forced events bypass dedupe", async () => {
    const { notifier, broadcasts } = notifierWithSpy()
    await notifier.announce("game-1", "RUNNING")
    await notifier.announce("game-1", "RESTARTING")
    await notifier.announce("game-1", "RESTARTING", { force: true })
    expect(broadcasts).toHaveLength(3)
  })

  test("dedupe is per server", async () => {
    const { notifier, broadcasts } = notifierWithSpy()
    await notifier.announce("game-1", "RUNNING")
    await notifier.announce("game-2", "RUNNING")
    expect(broadcasts).toHaveLength(2)
  })

  test("forget re-announces after reconnect", async () => {
    const { notifier, broadcasts } = notifierWithSpy()
    await notifier.announce("game-1", "OFFLINE")
    notifier.forget("game-1")
    await notifier.announce("game-1", "OFFLINE")
    expect(broadcasts).toHaveLength(2)
  })

  test("admin chat ids ride along on every broadcast", async () => {
    const { notifier, broadcasts } = notifierWithSpy()
    await notifier.announce("game-1", "STARTING")
    expect(broadcasts[0]?.admins).toEqual(["42"])
  })

  test("offline alert keeps ops jargon out of the user text", async () => {
    const broadcasts: { text: string; admins: string[] }[] = []
    const calls: { text: string; admins: string[] }[] = broadcasts
    const fake = {
      broadcast: async (text: string, admins: string[] = []) => {
        calls.push({ text, admins: [...admins] })
        return { recipients: 1, sent: 1, failed: 0 }
      },
    } as unknown as NotificationService
    const notifier = new OpsNotifier({ notifications: fake, adminChatIds: ["42"], locale: "ru", logger: silentLogger() })

    await notifier.offlineAlert("game-1", 12, true)
    // two broadcasts: admin alert with server id + generic user text
    expect(calls).toHaveLength(2)
    expect(calls[0]?.text).toContain("game-1")
    expect(calls[1]?.text).not.toContain("game-1")
  })
})
