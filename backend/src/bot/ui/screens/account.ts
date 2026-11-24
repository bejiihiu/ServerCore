import type { EmojiRenderer } from "../../../i18n/emoji.js"
import { bold, expandableQuote, joinLines, kv, type Translator } from "../text/index.js"
import { actionRow, backButton, inlineKeyboard } from "../keyboards/index.js"
import type { ScreenMessage } from "./types.js"

/** callback namespace for the account card; the flow itself belongs to the account module */
export const ACCOUNT_CALLBACK = {
  open: "account:open",
  back: "menu:back",
} as const

export type AccountLinked = {
  linked: true
  nickname: string
  role?: string
  linkedAt?: string
}

export type AccountCardParams = {
  t: Translator
  e: EmojiRenderer
  account: AccountLinked | { linked: false }
}

/**
 * account card — linked: field table with a green dot; unlinked: dot plus the
 * one instruction that gets the user unblocked. nothing else.
 */
export function accountCardScreen(params: AccountCardParams): ScreenMessage {
  const { t, e, account } = params

  if (!account.linked) {
    const text = joinLines(
      `${e.token("status.neutral")} ${bold(t.t("account.notLinkedTitle"))}`,
      "",
      expandableQuote(t.t("account.notLinkedHint")),
    )
    return { text, markup: inlineKeyboard([[backButton(ACCOUNT_CALLBACK.back)]]) }
  }

  const text = joinLines(
    `${e.token("status.active")} ${bold(t.t("account.title"))}`,
    "",
    kv(t.t("account.fieldNickname"), account.nickname),
    account.role === undefined ? false : kv(t.t("account.fieldRole"), account.role),
    account.linkedAt === undefined ? false : kv(t.t("account.fieldLinked"), account.linkedAt),
  )

  return {
    text,
    markup: inlineKeyboard([
      actionRow([{ label: t.t("account.action.refresh"), callbackData: ACCOUNT_CALLBACK.open }]),
      [backButton(ACCOUNT_CALLBACK.back)],
    ]),
  }
}
