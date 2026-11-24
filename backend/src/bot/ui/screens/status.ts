import type { EmojiRenderer, EmojiKey } from "../../../i18n/emoji.js"
import { bold, joinLines, kv, type Translator } from "../text/index.js"
import { actionRow, backButton, inlineKeyboard } from "../keyboards/index.js"
import { MENU_CALLBACK } from "./menu.js"
import type { ScreenMessage } from "./types.js"

export type StatusScreenParams = {
  t: Translator
  e: EmojiRenderer
  server: { online: boolean; players: number }
  account: { linked: boolean; nickname?: string }
}

/**
 * status screen — the whole message answers one question: is the server up
 * and can this user play. the opening dot carries the verdict.
 */
export function statusScreen(params: StatusScreenParams): ScreenMessage {
  const { t, e, server, account } = params

  const dotKey: EmojiKey = server.online ? "status.active" : "status.attention"
  const serverLine = server.online
    ? t.t("status.serverOnline")
    : t.t("status.unknown")

  const accountLine = account.linked && account.nickname !== undefined
    ? t.t("status.accountLinked", { nickname: account.nickname })
    : t.t("status.accountNotLinked")

  const text = joinLines(
    `${e.token(dotKey)} ${bold(t.t("status.title"))}`,
    "",
    kv(t.t("status.server"), serverLine),
    kv(t.t("status.playersLabel"), t.tPlural("status.players", server.players)),
    kv(t.t("status.account"), accountLine),
  )

  return {
    text,
    markup: inlineKeyboard([
      actionRow([{ label: t.t("common.refresh"), callbackData: MENU_CALLBACK.status }]),
      [backButton(MENU_CALLBACK.back, t.t("common.back"))],
    ]),
  }
}
