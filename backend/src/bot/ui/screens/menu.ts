import type { EmojiRenderer, EmojiKey } from "../../../i18n/emoji.js"
import { bold, expandableQuote, joinLines, type Translator } from "../text/index.js"
import { inlineKeyboard, menuGrid } from "../keyboards/index.js"
import type { ScreenMessage } from "./types.js"

/** callback namespaces owned by the main menu */
export const MENU_CALLBACK = {
  status: "menu:status",
  account: "menu:account",
  help: "menu:help",
  back: "menu:back",
} as const

export type MenuAction = {
  key: "status" | "account" | "help"
  enabled: boolean
  callbackData: string
}

export type MainMenuParams = {
  t: Translator
  e: EmojiRenderer
  /** account state drives the header dot, subtitle and the hint visibility */
  account: { linked: boolean; nickname?: string }
  /** progressive rollout: only enabled actions get a button */
  actions: ReadonlyArray<MenuAction>
}

/**
 * main menu — status-first: a dot for account state, the bot name as title.
 * the linking hint shows only to unlinked users; linked users get the menu
 * without the lecture. no greetings, no slogans.
 */
export function mainMenuScreen(params: MainMenuParams): ScreenMessage {
  const { t, e, account, actions } = params

  const dotKey: EmojiKey = account.linked ? "status.active" : "status.neutral"
  const subtitle = account.linked && account.nickname !== undefined
    ? t.t("menu.statusLinked", { nickname: account.nickname })
    : t.t("menu.statusNotLinked")

  const text = joinLines(
    `${e.token(dotKey)} ${bold(t.t("menu.title"))} — ${t.t("menu.subtitle")}`,
    subtitle,
    account.linked ? false : "",
    account.linked ? false : expandableQuote(t.t("menu.hint")),
  )

  const labelKeys: Record<MenuAction["key"], string> = {
    status: "menu.action.status",
    account: "menu.action.account",
    help: "menu.action.help",
  }

  const rows = menuGrid(
    actions
      .filter((action) => action.enabled)
      .map((action) => ({
        label: t.t(labelKeys[action.key]),
        callbackData: action.callbackData,
      })),
    2,
  )

  return {
    text,
    markup: rows.length > 0 ? inlineKeyboard(rows) : undefined,
  }
}
