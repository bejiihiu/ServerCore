import ruDict from "./locales/ru.json"
import enDict from "./locales/en.json"
import { createI18n, defaultPlural, ruPlural, type I18n, type LocaleDict } from "./index.js"

/**
 * bundled i18n instance. ru is the canonical seed and the fallback; en ships
 * with the same key set (parity is enforced by tests). github-hosted locale
 * files plug in later via i18n.addLocale without touching any call site.
 */
const dictionaries: Record<string, LocaleDict> = {
  ru: ruDict as LocaleDict,
  en: enDict as LocaleDict,
}

export const i18n: I18n = createI18n(dictionaries, {
  defaultLocale: "ru",
  pluralRules: { ru: ruPlural, en: defaultPlural },
})
