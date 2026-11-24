import type { Translator } from "../text/index.js"
import { bold, joinLines, quote } from "../text/index.js"
import type { EmojiRenderer } from "../../../i18n/emoji.js"
import { confirmKeyboard, type ConfirmSpec } from "../keyboards/index.js"
import { dotFor, type NoticeTone } from "./notice.js"
import type { ScreenMessage } from "./types.js"

export type ConfirmDialogParams = {
  t: Translator
  e: EmojiRenderer
  /** screen verdict; destructive questions use "attention" */
  tone?: NoticeTone
  title?: string
  body: string
  /** extra context shown as a blockquote above the buttons */
  aside?: string
  keyboard: ConfirmSpec
  /** button tone: danger for destructive confirms */
  buttonTone?: "danger" | "success"
}

/**
 * confirmation dialog: dot, title, body, optional aside (blockquote), and the
 * confirm/cancel row. the keyboard carries all the callback data, so the
 * dialog itself stays stateless.
 */
export function confirmDialogScreen(params: ConfirmDialogParams): ScreenMessage {
  const { t, e, title, body, aside, keyboard, buttonTone } = params

  const text = joinLines(
    `${dotFor(e, params.tone ?? "neutral")} ${bold(title ?? t.t("confirm.defaultTitle"))}`,
    "",
    body,
    aside === undefined ? false : "",
    aside === undefined ? false : quote(aside),
  )

  return {
    text,
    markup: { inline_keyboard: confirmKeyboard({ ...keyboard, tone: buttonTone }) },
  }
}
