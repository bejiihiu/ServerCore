import { EmojiRegistry, createEmojiRenderer } from "../../i18n/emoji.js"
import { i18n } from "../../i18n/instance.js"
import { createTranslator, type Translator } from "./text/index.js"

/**
 * process-wide bindings for the bot ui layer: one emoji registry (the pack
 * loader fills its custom ids, screens read them) and one translator per
 * locale. screens stay pure — they receive these as parameters.
 */
export const emojiRegistry = new EmojiRegistry(false)

export const emoji = createEmojiRenderer(emojiRegistry)

const translators = new Map<string, Translator>()

/** html-escaping translator bound to a locale (falls back to the default one) */
export function tr(locale: string): Translator {
  const resolved = i18n.provider(locale).locale
  const cached = translators.get(resolved)
  if (cached !== undefined) return cached
  const created = createTranslator(i18n.provider(resolved))
  translators.set(resolved, created)
  return created
}
