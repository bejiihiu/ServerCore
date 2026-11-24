import type { EmojiRenderer, EmojiKey } from "../../../i18n/emoji.js"
import { bold, joinLines, kv, type Translator } from "../text/index.js"
import { actionRow, backButton, inlineKeyboard } from "../keyboards/index.js"
import { MENU_CALLBACK } from "./menu.js"
import type { ScreenMessage } from "./types.js"

export type StatusScreenParams = {
  t: Translator
  e: EmojiRenderer
  /** online = the server answered; null = the backend has no live link to say */
  server: { online: boolean | null; players: number }
  account: { linked: boolean; nickname?: string }
  /** callback for the refresh button, menu:status by default */
  refreshData?: string
  back?: string
}

/**
 * status screen — the whole message answers one question: is the server up
 * and can this user play. the opening dot carries the verdict: green when the
 * server answers, red when it reports down, neutral when nothing is known.
 */
export function statusScreen(params: StatusScreenParams): ScreenMessage {
  const { t, e, server, account } = params

  const dotKey: EmojiKey =
    server.online === true ? "status.active" : server.online === false ? "status.attention" : "status.neutral"
  const serverLine =
    server.online === true
      ? t.t("status.serverOnline")
      : server.online === false
        ? t.t("status.serverDown")
        : t.t("status.unknown")

  const accountLine = account.linked && account.nickname !== undefined
    ? t.t("status.accountLinked", { nickname: account.nickname })
    : t.t("status.accountNotLinked")

  const text = joinLines(
    `${e.token(dotKey)} ${bold(t.t("status.title"))}`,
    "",
    kv(t.t("status.server"), serverLine),
    server.online === null ? false : kv(t.t("status.playersLabel"), t.tPlural("status.players", server.players)),
    kv(t.t("status.account"), accountLine),
  )

  return {
    text,
    markup: inlineKeyboard([
      actionRow([{ label: t.t("common.refresh"), callbackData: params.refreshData ?? MENU_CALLBACK.status }]),
      [backButton(params.back ?? MENU_CALLBACK.back, t.t("common.back"))],
    ]),
  }
}
