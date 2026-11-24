/**
 * typography layer: every bot message is html (parse_mode "HTML"). these
 * helpers wrap escaped text so raw user data can never inject markup.
 *
 * design rules (see DESIGN.md next to the screens):
 * - bold marks state and titles, never decoration
 * - blockquote is for asides and provenance, expandable one for long blocks
 * - no exclamation marks, no greeting openers, sentence case everywhere
 */

import type { I18nProvider } from "../../../i18n/index.js"

export function escapeHtml(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
}

export function bold(text: string): string {
  return `<b>${escapeHtml(text)}</b>`
}

export function italic(text: string): string {
  return `<i>${escapeHtml(text)}</i>`
}

export function underline(text: string): string {
  return `<u>${escapeHtml(text)}</u>`
}

export function strikethrough(text: string): string {
  return `<s>${escapeHtml(text)}</s>`
}

export function code(text: string): string {
  return `<code>${escapeHtml(text)}</code>`
}

/** blockquote — short aside, provenance note */
export function quote(text: string): string {
  return `<blockquote>${escapeHtml(text)}</blockquote>`
}

/** expandable blockquote — long blocks (rules, logs) collapsed by default */
export function expandableQuote(text: string): string {
  return `<blockquote expandable>${escapeHtml(text)}</blockquote>`
}

/** spoiler — sensitive data (ids, tokens, coordinates) */
export function spoiler(text: string): string {
  return `<tg-spoiler>${escapeHtml(text)}</tg-spoiler>`
}

export function link(text: string, href: string): string {
  return `<a href="${escapeHtml(href)}">${escapeHtml(text)}</a>`
}

/** renders a resolved custom emoji as a tg-emoji html tag */
export function tgEmoji(emojiId: string, fallback: string): string {
  return `<tg-emoji emoji-id="${escapeHtml(emojiId)}">${fallback}</tg-emoji>`
}

/**
 * replaces tg-emoji tags with their inner fallback text — the degradation
 * path when telegram rejects custom emoji entities (premium lost, etc).
 */
export function stripCustomEmoji(text: string): string {
  return text.replace(/<tg-emoji emoji-id="[^"]*">(.*?)<\/tg-emoji>/gs, "$1")
}

/** joins fragments into a message body, dropping empty lines */
export function joinLines(...parts: ReadonlyArray<string | false | undefined>): string {
  return parts.filter((part): part is string => typeof part === "string" && part.length > 0).join("\n")
}

/**
 * key-value row: bold label, plain value on the same line — the standard way
 * screens present fields.
 */
export function kv(label: string, value: string): string {
  return `${bold(label)} ${escapeHtml(value)}`
}

/** section label inside a message body */
export function section(title: string): string {
  return bold(title)
}

/** base payload for sendMessage/editMessageText: html + no link previews */
export type ComposedText = {
  parse_mode: "HTML"
  link_preview_options: { is_disabled: true }
}

export function composeText(): ComposedText {
  return { parse_mode: "HTML", link_preview_options: { is_disabled: true } }
}

/**
 * bot-side translator: identical surface to the i18n provider, but every
 * substituted value is html-escaped — bot messages always render as html, so
 * user-controlled data (nicknames etc) can never inject markup.
 */
export type Translator = {
  t(key: string, params?: Readonly<Record<string, string | number>>): string
  tPlural(key: string, count: number, params?: Readonly<Record<string, string | number>>): string
}

function escapedParams(
  params: Readonly<Record<string, string | number>> | undefined,
): Record<string, string> | undefined {
  if (params === undefined) return undefined
  const escaped: Record<string, string> = {}
  for (const [key, value] of Object.entries(params)) escaped[key] = escapeHtml(String(value))
  return escaped
}

export function createTranslator(provider: I18nProvider): Translator {
  return {
    t: (key, params) => provider.t(key, escapedParams(params)),
    tPlural: (key, count, params) => provider.tPlural(key, count, escapedParams(params)),
  }
}
