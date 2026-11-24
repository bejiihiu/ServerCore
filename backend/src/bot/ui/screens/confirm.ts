import type { Translator } from "../text/index.js"
import { bold, joinLines, quote } from "../text/index.js"
import { confirmKeyboard, type ConfirmSpec } from "../keyboards/index.js"
import type { ScreenMessage } from "./types.js"

export type ConfirmDialogParams = {
  t: Translator
  title?: string
  body: string
  /** extra context shown as a blockquote above the buttons */
  aside?: string
  keyboard: ConfirmSpec
  tone?: "danger" | "success"
}

/**
 * confirmation dialog: title, body, optional aside (blockquote), and the
 * confirm/cancel row. the keyboard carries all the callback data.
 */
export function confirmDialogScreen(params: ConfirmDialogParams): ScreenMessage {
  const { t, title, body, aside, keyboard, tone } = params

  const text = joinLines(
    bold(title ?? t.t("confirm.defaultTitle")),
    "",
    body,
    aside === undefined ? false : "",
    aside === undefined ? false : quote(aside),
  )

  return {
    text,
    markup: { inline_keyboard: confirmKeyboard({ ...keyboard, tone }) },
  }
}
