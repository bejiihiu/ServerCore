import { describe, expect, test } from "bun:test"
import ruDict from "../src/i18n/locales/ru.json"
import enDict from "../src/i18n/locales/en.json"
import { t, LOCALES, type Locale } from "../src/services/i18n.js"

/**
 * parity + tone guards for the two text layers:
 *
 * - structured dictionaries (src/i18n/locales/*.json) render the html screens;
 * - the flat map (src/services/i18n.ts) renders the plain-text channel.
 *
 * these tests are the machine part of the copy review: they cannot judge
 * whether a sentence is good, but they catch the failures that slip in when
 * a dozen strings are written by hand — a key present in one locale only, a
 * placeholder that drifted, a formal "вы" in a bot that talks in "ты", an
 * emoji that bypassed the registry, a shout in caps.
 */

type Dict = { readonly [key: string]: string | Dict }

function flatten(dict: Dict, prefix = ""): Map<string, string> {
  const out = new Map<string, string>()
  for (const [key, value] of Object.entries(dict)) {
    const path = prefix === "" ? key : `${prefix}.${key}`
    if (typeof value === "string") out.set(path, value)
    else for (const [nested, text] of flatten(value, path)) out.set(nested, text)
  }
  return out
}

function placeholders(text: string): string[] {
  return [...text.matchAll(/\{\{(\w+)\}\}/g)].map((match) => match[1]!).sort()
}

/** emoji ranges; arrows, quotes and bullets are punctuation, not emoji */
const EMOJI = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{2300}-\u{23FF}\u{FE0F}]/u

/**
 * the polite plural is off-tone for this bot: it says "ты" everywhere.
 * js \b is ascii-only, so the boundaries are spelled out — a cyrillic word
 * boundary bug is exactly how this guard would silently pass.
 */
const FORMAL_YOU = /(?:^|[^А-Яа-яЁё])(Вы|Вас|Вам|Ваш|ваш|вас|вам|вы)(?:[^А-Яа-яЁё]|$)/u

/** caps used as emphasis (verdicts and the FOR/AGAINST stamps are allowed) */
const SHOUT = /(?:^|[^А-ЯЁа-яёA-Za-z])([А-ЯЁA-Z]{4,})(?:[^А-ЯЁа-яёA-Za-z]|$)/u

const ru = flatten(ruDict as Dict)
const en = flatten(enDict as Dict)

/** values that are proper nouns or pure placeholders, so identical is fine */
const IDENTICAL_ALLOWED = new Set([
  "menu.title",
  "common.page",
  "link.confirmFact",
  "link.successFact",
  "deletion.doneFact",
  "lang.nameRu",
  "lang.nameEn",
  "stats.ws",
])

describe("structured locale parity", () => {
  test("both locales carry exactly the same keys", () => {
    const missingInEn = [...ru.keys()].filter((key) => !en.has(key))
    const missingInRu = [...en.keys()].filter((key) => !ru.has(key))
    expect(missingInEn).toEqual([])
    expect(missingInRu).toEqual([])
  })

  test("placeholders match per key, so no translation drops a value", () => {
    for (const [key, text] of ru) {
      expect({ key, params: placeholders(text) }).toEqual({ key, params: placeholders(en.get(key)!) })
    }
  })

  test("plural groups ship an `other` form in both locales", () => {
    const groups = new Set(
      [...ru.keys()].filter((key) => /\.(one|few|many|other)$/.test(key)).map((key) => key.replace(/\.(one|few|many|other)$/, "")),
    )
    expect(groups.size).toBeGreaterThan(0)
    for (const group of groups) {
      expect(ru.has(`${group}.other`)).toBe(true)
      expect(en.has(`${group}.other`)).toBe(true)
    }
  })

  test("russian and english actually differ wherever they should", () => {
    const identical = [...ru.entries()]
      .filter(([key, text]) => en.get(key) === text)
      .map(([key]) => key)
      .filter((key) => !IDENTICAL_ALLOWED.has(key))
    expect(identical).toEqual([])
  })
})

describe("copy guards: structured screens", () => {
  test("no exclamation marks", () => {
    const offenders = [...ru, ...en].filter(([, text]) => text.includes("!")).map(([key]) => key)
    expect(offenders).toEqual([])
  })

  test("no greeting openers", () => {
    const offenders = [...ru, ...en]
      .filter(([, text]) => /^(привет|здравствуй|добро пожаловать|hi\b|hello|welcome)/i.test(text))
      .map(([key]) => key)
    expect(offenders).toEqual([])
  })

  test("no emphasis in caps", () => {
    const offenders = [...ru, ...en].filter(([, text]) => SHOUT.test(text)).map(([key]) => key)
    expect(offenders).toEqual([])
  })

  test("the bot says ты, never вы", () => {
    const offenders = [...ru].filter(([, text]) => FORMAL_YOU.test(text)).map(([key]) => key)
    expect(offenders).toEqual([])
  })

  test("no emoji: state markers come from the registry renderer, not from text", () => {
    const offenders = [...ru, ...en].filter(([, text]) => EMOJI.test(text)).map(([key]) => key)
    expect(offenders).toEqual([])
  })

  test("no legacy mixed keys: screens never live in the plain map", () => {
    // the old single-dictionary era named screen keys bot.*; their return
    // would mean the channel split documented in the design doc is gone
    expect([...ru.keys()].filter((key) => key.startsWith("bot."))).toEqual([])
    expect([...en.keys()].filter((key) => key.startsWith("bot."))).toEqual([])
  })
})

describe("copy guards: flat plain-text channel", () => {
  const KEYS = [
    "ops.server.starting",
    "ops.server.running",
    "ops.server.restarting",
    "ops.server.restarting_scheduled",
    "ops.server.stopping",
    "ops.server.offline",
    "ops.server.update_ready",
    "ops.admin.server_back",
    "vote.announce",
    "vote.extended",
    "vote.resultLine",
    "vote.confirmation",
    "vote.confirmationQuorum",
    "vote.feedbackAccepted",
    "vote.feedbackAlready",
    "vote.feedbackSelf",
    "vote.feedbackInactive",
    "vote.feedbackNotInGroup",
    "vote.feedbackClosed",
    "vote.feedbackUnknown",
    "result.approvedToApplicant",
    "result.rejectedToApplicant",
    "result.expiredToApplicant",
    "group.inviteText",
    "group.activated",
    "admission.dm.vote_opened",
    "admission.admin.vote_opened",
    "admission.admin.vote_decided",
    "admission.admin.invite_issued",
    "account.linked",
    "account.deleted",
    "account.deletion_failed",
  ] as const

  test("no exclamation marks and no caps shouting", () => {
    for (const key of KEYS) {
      for (const locale of LOCALES) {
        const text = t(locale, key, {})
        expect({ key, locale, bang: text.includes("!") }).toEqual({ key, locale, bang: false })
        expect({ key, locale, shout: SHOUT.test(text) }).toEqual({ key, locale, shout: false })
      }
    }
  })

  test("no emoji in the plain channel either", () => {
    for (const key of KEYS) {
      for (const locale of LOCALES) {
        expect({ key, locale, emoji: EMOJI.test(t(locale, key, {})) }).toEqual({ key, locale, emoji: false })
      }
    }
  })

  test("the bot says ты, never вы", () => {
    for (const key of KEYS) {
      expect({ key, formal: FORMAL_YOU.test(t("ru", key, {})) }).toEqual({ key, formal: false })
    }
  })

  test("locale columns never fall back silently: ru and en differ", () => {
    for (const key of KEYS) {
      expect({ key, same: t("ru", key, {}) === t("en", key, {}) }).toEqual({ key, same: false })
    }
  })

  test("no legacy mixed keys in the flat map either", () => {
    for (const key of KEYS) expect(key.startsWith("bot.")).toBe(false)
  })
})

describe("locale union", () => {
  test("every locale in the union is a real file", () => {
    const dicts: Record<Locale, unknown> = { ru: ruDict, en: enDict }
    for (const locale of LOCALES) expect(dicts[locale]).toBeDefined()
  })
})
