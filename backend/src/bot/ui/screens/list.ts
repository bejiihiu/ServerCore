import type { Translator } from "../text/index.js"
import { bold, joinLines } from "../text/index.js"
import { backButton, inlineKeyboard, paginationRow } from "../keyboards/index.js"
import type { ScreenMessage } from "./types.js"

export type PaginatedListParams = {
  t: Translator
  title: string
  /** pre-rendered item rows (the caller owns item formatting) */
  items: readonly string[]
  page: number
  totalPages: number
  /** callback prefix for pagination, rendered as <prefix>:page:<n> */
  paginationPrefix: string
  backTarget?: string
  /** optional footer line under the list */
  footer?: string
}

/**
 * paginated list — bullet rows, a counter row, optional back. items are
 * rendered by the caller; this builder owns layout and paging.
 */
export function paginatedListScreen(params: PaginatedListParams): ScreenMessage {
  const { t, title, items, page, totalPages, paginationPrefix, backTarget, footer } = params

  const text = joinLines(
    bold(title),
    "",
    ...items,
    footer === undefined ? false : "",
    footer === undefined ? false : footer,
  )

  const rows = [paginationRow({ page, totalPages, callbackPrefix: paginationPrefix })]
  if (backTarget !== undefined) rows.push([backButton(backTarget, t.t("common.back"))])

  if (items.length === 0) {
    return { text: t.t("common.empty"), markup: inlineKeyboard(rows) }
  }

  return {
    text,
    markup: inlineKeyboard(rows),
  }
}
