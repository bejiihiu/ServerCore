/**
 * i18n consumer layer for the bot.
 *
 * locale dictionaries live as json (bundled seed here, later fetched from
 * github by the locale distribution flow) — this module never hardcodes text.
 * placeholders use {{name}} syntax, pluralization follows the locale's
 * plural rule (ru: one/few/many/other).
 */

export type LocaleDict = { readonly [key: string]: string | LocaleDict }

export type PluralForm = "one" | "few" | "many" | "other"

export type PluralRule = (count: number) => PluralForm

export type I18nParams = Readonly<Record<string, string | number>>

export class I18nError extends Error {
  readonly key: string

  constructor(message: string, key: string) {
    super(message)
    this.name = "I18nError"
    this.key = key
  }
}

export interface I18nProvider {
  readonly locale: string
  /** plain lookup with {{placeholder}} substitution; throws on missing keys */
  t(key: string, params?: I18nParams, options?: TranslateOptions): string
  /** plural lookup: resolves key.one/key.few/key.many/key.other, {{count}} injected */
  tPlural(key: string, count: number, params?: I18nParams, options?: TranslateOptions): string
  has(key: string): boolean
}

export type TranslateOptions = {
  /** html-escape substituted values (bot messages are html; plugin texts are not) */
  escape?: boolean
}

export type I18nOptions = {
  defaultLocale?: string
  /** per-locale plural rules; locales without one fall back to defaultPlural */
  pluralRules?: Record<string, PluralRule>
}

const PLACEHOLDER = /\{\{(\w+)\}\}/g

function lookup(dict: LocaleDict, key: string): string | undefined {
  let node: string | LocaleDict | undefined = dict
  for (const part of key.split(".")) {
    if (typeof node !== "object" || node === null) return undefined
    node = node[part]
  }
  return typeof node === "string" ? node : undefined
}

function escapeValue(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
}

function interpolate(template: string, params: I18nParams | undefined, key: string, escape: boolean): string {
  return template.replace(PLACEHOLDER, (_match: string, name: string): string => {
    const value = params === undefined ? undefined : params[name]
    if (value === undefined) {
      throw new I18nError(`missing parameter "${name}" for key "${key}"`, key)
    }
    const text = String(value)
    return escape ? escapeValue(text) : text
  })
}

/** russian plural classes per CLDR: one/few/many for integers, other for fractions */
export function ruPlural(count: number): PluralForm {
  if (!Number.isInteger(count)) return "other"
  const mod100 = Math.abs(count) % 100
  const mod10 = mod100 % 10
  if (mod10 === 1 && mod100 !== 11) return "one"
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "few"
  return "many"
}

/** english-like fallback for locales without their own rule */
export function defaultPlural(count: number): PluralForm {
  if (!Number.isInteger(count)) return "other"
  return count === 1 ? "one" : "other"
}

class Locale implements I18nProvider {
  constructor(
    readonly locale: string,
    private readonly dict: LocaleDict,
    private readonly plural: PluralRule,
  ) {}

  has(key: string): boolean {
    return typeof lookup(this.dict, key) === "string"
  }

  t(key: string, params?: I18nParams, options?: TranslateOptions): string {
    const template = lookup(this.dict, key)
    if (typeof template !== "string") {
      throw new I18nError(`missing i18n key "${key}" for locale "${this.locale}"`, key)
    }
    return interpolate(template, params, key, options?.escape === true)
  }

  tPlural(key: string, count: number, params?: I18nParams, options?: TranslateOptions): string {
    const form = this.plural(count)
    const withCount: I18nParams = { ...params, count }
    const escape = options?.escape === true
    const direct = lookup(this.dict, `${key}.${form}`)
    if (typeof direct === "string") return interpolate(direct, withCount, `${key}.${form}`, escape)
    const other = lookup(this.dict, `${key}.other`)
    if (typeof other === "string") return interpolate(other, withCount, `${key}.other`, escape)
    throw new I18nError(`missing plural key "${key}.${form}" for locale "${this.locale}"`, key)
  }
}

export interface I18n extends I18nProvider {
  readonly defaultLocale: string
  /** provider bound to a specific locale, falls back to the default one */
  provider(locale: string): I18nProvider
  /** registers a dictionary at runtime (github-downloaded locales plug in here) */
  addLocale(locale: string, dict: LocaleDict, plural?: PluralRule): void
}

export function createI18n(dicts: Record<string, LocaleDict>, options: I18nOptions = {}): I18n {
  const locales = new Map<string, Locale>()
  const rules = { ...options.pluralRules }

  const add = (locale: string, dict: LocaleDict, plural?: PluralRule): Locale => {
    const instance = new Locale(locale, dict, plural ?? rules[locale] ?? defaultPlural)
    if (plural !== undefined) rules[locale] = plural
    locales.set(locale, instance)
    return instance
  }

  for (const [locale, dict] of Object.entries(dicts)) add(locale, dict)

  const defaultLocale = options.defaultLocale ?? Object.keys(dicts)[0]
  if (defaultLocale === undefined) {
    throw new I18nError("no locale dictionaries provided", "i18n")
  }
  const fallback = locales.get(defaultLocale)
  if (fallback === undefined) {
    throw new I18nError(`no dictionary registered for default locale "${defaultLocale}"`, defaultLocale)
  }

  return {
    defaultLocale,
    locale: fallback.locale,
    provider(locale: string): I18nProvider {
      return locales.get(locale) ?? fallback
    },
    addLocale: (locale, dict, plural) => {
      add(locale, dict, plural)
    },
    has: (key) => fallback.has(key),
    t: (key, params, options) => fallback.t(key, params, options),
    tPlural: (key, count, params, options) => fallback.tPlural(key, count, params, options),
  }
}
