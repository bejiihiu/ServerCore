import type { EmojiRenderer } from "../../../i18n/emoji.js"
import { bold, joinLines, type Translator } from "../text/index.js"
import { backRow, inlineKeyboard, toggleButton } from "../keyboards/index.js"
import { dotFor } from "./notice.js"
import type { ScreenMessage } from "./types.js"

export type LanguageOption = {
  code: string
  label: string
  callbackData: string
}

export type LanguagePickerParams = {
  t: Translator
  e: EmojiRenderer
  /** currently stored language; marked in the label, not by color */
  current: string
  options: ReadonlyArray<LanguageOption>
  back?: string
}

/**
 * language picker: one row of options with the active one marked, so the
 * state is readable without pressing anything. the label names the language,
 * never a flag emoji.
 */
export function languagePickerScreen(params: LanguagePickerParams): ScreenMessage {
  const { t, e, current, options, back } = params

  const row = options.map((option) =>
    toggleButton({
      label: option.label,
      on: option.code === current,
      callbackData: option.callbackData,
    }),
  )
  const rows = [row]
  if (back !== undefined) rows.push(backRow(back, t.t("common.back")))

  return {
    text: joinLines(
      `${dotFor(e, "neutral")} ${bold(t.t("lang.pickTitle"))}`,
      "",
      t.t("lang.pickBody"),
    ),
    markup: inlineKeyboard(rows),
  }
}
