import type { EmojiRenderer } from "../../../i18n/emoji.js"
import { bold, expandableQuote, joinLines, kv, quote, type Translator } from "../text/index.js"
import { backRow, inlineKeyboard, menuGrid, type ActionButton } from "../keyboards/index.js"
import type { ScreenMessage } from "./types.js"
import { dotFor, type NoticeTone } from "./notice.js"

export type ApplicationCardParams = {
  t: Translator
  e: EmojiRenderer
  tone?: NoticeTone
  /** screen title (draft summary vs saved application) */
  title: string
  /** minecraft nickname the application belongs to */
  nickname: string
  /** pre-rendered numbered answers, one string per question */
  answers: ReadonlyArray<string>
  /** verdict line under the answers */
  footer?: string
  aside?: string
  actions?: ReadonlyArray<ActionButton>
  back?: string
}

/**
 * one application: nickname row plus the answers as a collapsed block, so the
 * message never turns into a wall of text on a phone. the same builder serves
 * the questionnaire summary (with submit/cancel) and the /myapplication view.
 */
export function applicationCardScreen(params: ApplicationCardParams): ScreenMessage {
  const { t, e, title, nickname, answers, footer, aside, actions = [], back } = params

  const text = joinLines(
    `${dotFor(e, params.tone ?? "pending")} ${bold(title)}`,
    "",
    kv("Minecraft", nickname),
    "",
    expandableQuote(answers.join("\n\n")),
    aside === undefined ? false : "",
    aside === undefined ? false : quote(aside),
    footer === undefined ? false : "",
    footer,
  )

  const rows = actions.length > 0 ? menuGrid(actions, 2) : []
  if (back !== undefined) rows.push(backRow(back, t.t("common.back")))

  return { text, markup: rows.length > 0 ? inlineKeyboard(rows) : undefined }
}
