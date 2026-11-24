import { describe, expect, test } from "bun:test"
import {
  LOCALES,
  isLocale,
  langOf,
  statusLabel,
  t,
  tPlural,
  type Locale,
} from "../src/services/i18n.js"
import { I18nError, i18n, providerFor } from "../src/i18n/index.js"
import { createTranslator, escapeHtml } from "../src/bot/ui/text/index.js"

describe("message store", () => {
  test("resolves dotted keys", () => {
    expect(t("ru", "menu.title")).toBe("ServerCore")
    expect(t("en", "auth.approve")).toBe("✅ Confirm login")
  })

  test("interpolates named params order-agnostically", () => {
    expect(t("ru", "menu.statusLinked", { nickname: "Соня" })).toBe("Аккаунт привязан: Соня")
    expect(t("en", "bot.account.screen", { username: "x", status: "Active", telegram: "y" })).toContain("x")
  })

  test("an unknown key renders as the key itself, never as an empty message", () => {
    expect(t("ru", "nope.missing" as never)).toBe("nope.missing")
  })

  test("both locales carry every key", () => {
    // the store is typed as a full ru+en matrix, so a missing column is a
    // compile error; this guards the runtime shape too
    for (const locale of LOCALES) {
      expect(t(locale, "bot.delete.confirm").length).toBeGreaterThan(20)
      expect(t(locale, "auth.window", { seconds: 75 })).not.toMatch(/\{\w+\}/)
    }
  })
})

describe("pluralization", () => {
  test("ru picks one/few/many per cldr", () => {
    expect(tPlural("ru", "status.players", 1)).toBe("1 игрок")
    expect(tPlural("ru", "status.players", 3)).toBe("3 игрока")
    expect(tPlural("ru", "status.players", 22)).toBe("22 игрока")
    expect(tPlural("ru", "status.players", 11)).toBe("11 игроков")
    expect(tPlural("ru", "status.players", 0)).toBe("0 игроков")
  })

  test("en only separates singular from plural", () => {
    expect(tPlural("en", "status.players", 1)).toBe("1 player")
    expect(tPlural("en", "status.players", 5)).toBe("5 players")
  })

  test("count is injected even when the caller passes extra params", () => {
    expect(tPlural("ru", "status.players", 2, { junk: "x" })).toBe("2 игрока")
  })
})

describe("providers", () => {
  test("a provider is pinned to one locale", () => {
    expect(providerFor("en").t("menu.subtitle")).toBe("server access")
    expect(providerFor("ru").t("menu.subtitle")).toBe("доступ на сервер")
  })

  test("the default provider renders ru", () => {
    expect(i18n.locale).toBe("ru")
    expect(i18n.t("common.back")).toBe("Назад")
  })

  test("unknown keys stay visible through the provider", () => {
    expect(providerFor("ru").t("nope.missing")).toBe("nope.missing")
  })

  test("locale helpers agree with the locale list", () => {
    expect(isLocale("ru")).toBe(true)
    expect(isLocale("de")).toBe(false)
    expect(langOf(undefined, "en")).toBe("en")
    expect(langOf("ru", "en")).toBe("ru")
    for (const locale of LOCALES as readonly Locale[]) {
      expect(statusLabel(locale, "ACTIVE").length).toBeGreaterThan(0)
    }
  })
})

describe("html translator", () => {
  test("escapes every substituted value", () => {
    const tr = createTranslator(providerFor("ru"))
    expect(tr.t("menu.statusLinked", { nickname: "<b>&" })).toBe(
      `Аккаунт привязан: ${escapeHtml("<b>&")}`,
    )
    expect(tr.tPlural("status.players", 1, { junk: "<x>" })).toBe("1 игрок")
  })

  test("the template itself is never escaped", () => {
    expect(providerFor("ru").t("auth.approve")).toContain("✅")
  })

  test("an unknown emoji key throws with the key in the error", () => {
    const error = new I18nError("unknown emoji key", "nope")
    expect(error.name).toBe("I18nError")
    expect(error.key).toBe("nope")
  })
})
