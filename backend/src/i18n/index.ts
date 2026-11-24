/**
 * locale-bound view over the message store in services/i18n.ts.
 *
 * the store owns every string (ru + en, one entry per key); this module only
 * pins a provider to one locale so screens cannot render the wrong language.
 * bot texts are html, so the translator in bot/ui/text escapes substituted
 * values before they reach the template.
 */

import {
  DEFAULT_LOCALE,
  t as translate,
  tPlural as translatePlural,
  type Locale,
  type MessageKey,
  type PluralKey,
} from "../services/i18n.js"

export type I18nParams = Readonly<Record<string, string | number>>

export interface I18nProvider {
  readonly locale: Locale
  /** plain lookup; an unknown key renders as the key itself, so gaps are loud */
  t(key: string, params?: I18nParams): string
  /** plural lookup over a family such as "status.players" */
  tPlural(key: string, count: number, params?: I18nParams): string
}

/** broken locale data (unknown emoji key, missing family) dies loudly */
export class I18nError extends Error {
  readonly key: string

  constructor(message: string, key: string) {
    super(message)
    this.name = "I18nError"
    this.key = key
  }
}

export function providerFor(locale: Locale): I18nProvider {
  return {
    locale,
    t: (key, params) => translate(locale, key as MessageKey, params),
    tPlural: (key, count, params) => translatePlural(locale, key as PluralKey, count, params),
  }
}

/** default-locale provider for call sites that have no user language yet */
export const i18n: I18nProvider = providerFor(DEFAULT_LOCALE)
