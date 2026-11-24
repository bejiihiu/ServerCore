import type { EmojiKey, EmojiRenderer } from "../../../i18n/emoji.js"
import { bold, expandableQuote, joinLines, quote, type Translator } from "../text/index.js"
import { backRow, inlineKeyboard, menuGrid, type ActionButton } from "../keyboards/index.js"
import type { ScreenMessage } from "./types.js"

/** screen verdict — maps onto the one semantic dot a screen opens with */
export type NoticeTone = "active" | "pending" | "attention" | "neutral"

const DOT: Record<NoticeTone, EmojiKey> = {
  active: "status.active",
  pending: "status.pending",
  attention: "status.attention",
  neutral: "status.neutral",
}

/** the semantic dot for a screen verdict, rendered from the emoji registry */
export function dotFor(e: EmojiRenderer, tone: NoticeTone = "neutral"): string {
  return e.token(DOT[tone])
}

export type NoticeParams = {
  t: Translator
  e: EmojiRenderer
  /** what the screen says at a glance; neutral when omitted */
  tone?: NoticeTone
  title: string
  /** plain body paragraph(s) */
  body?: string
  /** short aside rendered as a blockquote */
  aside?: string
  /** long block (rules, answers) collapsed by default */
  details?: string
  /** buttons above the back row */
  actions?: ReadonlyArray<ActionButton>
  /** optional back target; the main menu and notifications carry none */
  back?: string
  backLabel?: string
}

/**
 * the workhorse screen: one dot, a title, then whatever the flow needs —
 * body, aside, collapsed details, actions, back. confirmations, success and
 * failure states all render through it, so a screen never invents its own
 * layout or its own dot.
 */
export function noticeScreen(params: NoticeParams): ScreenMessage {
  const { t, e, tone = "neutral", title, body, aside, details, actions = [], back, backLabel } = params

  const text = joinLines(
    `${e.token(DOT[tone])} ${bold(title)}`,
    body === undefined ? false : "",
    body,
    aside === undefined ? false : "",
    aside === undefined ? false : quote(aside),
    details === undefined ? false : "",
    details === undefined ? false : expandableQuote(details),
  )

  const rows = actions.length > 0 ? menuGrid(actions, 2) : []
  if (back !== undefined) rows.push(backRow(back, backLabel ?? t.t("common.back")))

  return { text, markup: rows.length > 0 ? inlineKeyboard(rows) : undefined }
}
