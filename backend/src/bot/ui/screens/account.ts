import type { EmojiRenderer } from "../../../i18n/emoji.js"
import { bold, expandableQuote, joinLines, kv, type Translator } from "../text/index.js"
import { backRow, inlineKeyboard, menuGrid, type ActionButton } from "../keyboards/index.js"
import { dotFor } from "./notice.js"
import { MENU_CALLBACK } from "./menu.js"
import type { ScreenMessage } from "./types.js"

/** callback namespace for the account card; the flow itself belongs to the account module */
export const ACCOUNT_CALLBACK = {
  refresh: "account:refresh",
  delete: "account:delete",
} as const

export type AccountLinked = {
  linked: true
  nickname: string
  /** already localized label, the screen only places it */
  status?: string
  role?: string
  linkedAt?: string
}

export type AccountCardParams = {
  t: Translator
  e: EmojiRenderer
  account: AccountLinked | { linked: false }
  /** replaces the default refresh button; the back row is added on top */
  actions?: ReadonlyArray<ActionButton>
  back?: string
}

/**
 * account card — linked: field table with a green dot; unlinked: dot plus the
 * one instruction that gets the user unblocked. nothing else.
 */
export function accountCardScreen(params: AccountCardParams): ScreenMessage {
  const { t, e, account, back = MENU_CALLBACK.back } = params
  const actions = params.actions ?? [{ label: t.t("account.action.refresh"), callbackData: ACCOUNT_CALLBACK.refresh }]
  const rows = actions.length > 0 ? menuGrid(actions, 2) : []
  rows.push(backRow(back, t.t("common.back")))

  if (!account.linked) {
    const text = joinLines(
      `${dotFor(e, "neutral")} ${bold(t.t("account.notLinkedTitle"))}`,
      "",
      expandableQuote(t.t("account.notLinkedHint")),
    )
    return { text, markup: inlineKeyboard(rows) }
  }

  const text = joinLines(
    `${dotFor(e, "active")} ${bold(t.t("account.title"))}`,
    "",
    kv(t.t("account.fieldNickname"), account.nickname),
    account.status === undefined ? false : kv(t.t("account.fieldStatus"), account.status),
    account.role === undefined ? false : kv(t.t("account.fieldRole"), account.role),
    account.linkedAt === undefined ? false : kv(t.t("account.fieldLinked"), account.linkedAt),
  )

  return { text, markup: inlineKeyboard(rows) }
}
