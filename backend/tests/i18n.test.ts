import { describe, expect, test } from "bun:test"
import {
  createI18n,
  defaultPlural,
  ruPlural,
  I18nError,
  type I18nParams,
  type LocaleDict,
} from "../src/i18n/index.js"

const dict: LocaleDict = {
  plain: "значение",
  greet: "Привет, {{name}}!",
  multi: "{{a}} и {{b}}",
  players: {
    one: "{{count}} игрок",
    few: "{{count}} игрока",
    many: "{{count}} игроков",
    other: "{{count}} игрока",
  },
  partial: {
    one: "один",
    other: "несколько ({{count}})",
  },
}

function make(): ReturnType<typeof createI18n> {
  return createI18n({ ru: dict }, { defaultLocale: "ru", pluralRules: { ru: ruPlural } })
}

describe("i18n lookup", () => {
  test("resolves dotted keys", () => {
    expect(make().t("plain")).toBe("значение")
    expect(make().t("players.one", { count: 1 })).toBe("1 игрок")
  })

  test("interpolates placeholders", () => {
    expect(make().t("greet", { name: "Соня" })).toBe("Привет, Соня!")
    expect(make().t("multi", { a: 1, b: "два" })).toBe("1 и два")
  })

  test("throws on a missing key with the key in the error", () => {
    expect(() => make().t("nope.missing")).toThrow(I18nError)
    expect(() => make().t("nope.missing")).toThrow("nope.missing")
  })

  test("throws when a placeholder param is missing", () => {
    expect(() => make().t("greet")).toThrow(I18nError)
    expect(() => make().t("multi", { a: 1 })).toThrow(/"b"/)
  })

  test("escape option html-escapes substituted values", () => {
    expect(make().t("greet", { name: "<b>&" }, { escape: true })).toBe("Привет, &lt;b&gt;&amp;!")
    expect(make().tPlural("players", 1, { junk: "<x>" }, { escape: true })).toBe("1 игрок")
  })

  test("plain interpolation never escapes (plugin texts are not html)", () => {
    expect(make().t("greet", { name: "<b>" })).toBe("Привет, <b>!")
  })

  test("leaves text intact when there are no placeholders", () => {
    expect(make().t("plain", { junk: 1 })).toBe("значение")
  })

  test("has() reports key presence", () => {
    const i18n = make()
    expect(i18n.has("plain")).toBe(true)
    expect(i18n.has("nope")).toBe(false)
    expect(i18n.has("players.one")).toBe(true)
  })
})

describe("i18n pluralization (ru)", () => {
  test("ru plural classes", () => {
    expect(ruPlural(1)).toBe("one")
    expect(ruPlural(21)).toBe("one")
    expect(ruPlural(101)).toBe("one")
    expect(ruPlural(2)).toBe("few")
    expect(ruPlural(4)).toBe("few")
    expect(ruPlural(22)).toBe("few")
    expect(ruPlural(5)).toBe("many")
    expect(ruPlural(11)).toBe("many")
    expect(ruPlural(112)).toBe("many")
    expect(ruPlural(0)).toBe("many")
    expect(ruPlural(1.5)).toBe("other")
  })

  test("default plural rule (english-like)", () => {
    expect(defaultPlural(1)).toBe("one")
    expect(defaultPlural(0)).toBe("other")
    expect(defaultPlural(2)).toBe("other")
  })

  test("tPlural picks the ru form and injects count", () => {
    const i18n = make()
    expect(i18n.tPlural("players", 1)).toBe("1 игрок")
    expect(i18n.tPlural("players", 3)).toBe("3 игрока")
    expect(i18n.tPlural("players", 7)).toBe("7 игроков")
    expect(i18n.tPlural("players", 11)).toBe("11 игроков")
    expect(i18n.tPlural("players", 2, { junk: "x" })).toBe("2 игрока")
  })

  test("tPlural falls back to other when the form is absent", () => {
    expect(make().tPlural("partial", 3)).toBe("несколько (3)")
  })

  test("tPlural throws when neither the form nor other exists", () => {
    expect(() => make().tPlural("plain", 5)).toThrow(I18nError)
  })
})

describe("i18n multi-locale", () => {
  test("provider() falls back to the default locale", () => {
    const i18n = make()
    expect(i18n.provider("de").t("plain")).toBe("значение")
    expect(i18n.provider("ru").t("greet", { name: "x" })).toBe("Привет, x!")
  })

  test("addLocale registers a new dictionary at runtime", () => {
    const i18n = make()
    i18n.addLocale("en", { plain: "value" })
    expect(i18n.provider("en").t("plain")).toBe("value")
    // default untouched
    expect(i18n.t("plain")).toBe("значение")
  })

  test("createI18n rejects an empty dictionary set", () => {
    expect(() => createI18n({}, { defaultLocale: "ru" })).toThrow(I18nError)
  })
})

describe("i18n params typing", () => {
  test("string and number params both work", () => {
    const params: I18nParams = { count: 3, name: "ник" }
    expect(make().t("greet", params)).toBe("Привет, ник!")
  })
})
