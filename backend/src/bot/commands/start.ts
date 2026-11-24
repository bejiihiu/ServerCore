import { parseStartPayload } from "./start-payload.js"
import type { Context, Bot } from "grammy"
import { mainMenuScreen, MENU_CALLBACK, statusScreen, type MenuAction } from "../ui/screens/index.js"
import { sendScreen, editOrSendScreen } from "../ui/sending.js"
import type { Translator } from "../ui/text/index.js"
import type { EmojiRenderer } from "../../i18n/emoji.js"

/**
 * deps for the start/status/menu flows. locale comes from the user record
 * later (github locales); the default provider is fine today.
 */
export type BotUiDeps = {
  t: Translator
  e: EmojiRenderer
}

/** actions that are actually wired; the rest stay off the keyboard */
export function menuActions(): MenuAction[] {
  return [
    { key: "status", enabled: true, callbackData: MENU_CALLBACK.status },
    { key: "account", enabled: false, callbackData: "account:open" },
    { key: "help", enabled: false, callbackData: "menu:help" },
  ]
}

/**
 * /start — entry point of the whole flow.
 * with a valid deep-link token: confirms the token format and tells the user
 * the linking flow will continue. without: the main menu (account not linked
 * until the account module lands).
 * /start never creates an account by itself.
 */
export async function handleStartCommand(ctx: Context, ui: BotUiDeps): Promise<void> {
  const chat = ctx.chat
  if (chat === undefined) return
  const raw = ctx.match
  const match = typeof raw === "string" ? raw : ""
  const result = parseStartPayload(match)

  if (!result.ok) {
    const screen = mainMenuScreen({
      t: ui.t,
      e: ui.e,
      account: { linked: false },
      actions: menuActions(),
    })
    await sendScreen(ctx.api, { chatId: chat.id, screen })
    return
  }

  // real linking (token redemption against the store) arrives with the
  // account module; format is already verified here
  await ctx.reply(
    [
      `<b>${ui.t.t("start.tokenAccepted")}</b>`,
      "",
      ui.t.t("start.tokenChecking"),
    ].join("\n"),
    { parse_mode: "HTML", link_preview_options: { is_disabled: true } },
  )
}

/** /status — server + account status on one screen */
export async function handleStatusCommand(ctx: Context, ui: BotUiDeps): Promise<void> {
  const chat = ctx.chat
  if (chat === undefined) return
  const screen = statusScreen({
    t: ui.t,
    e: ui.e,
    server: { online: false, players: 0 },
    account: { linked: false },
  })
  await sendScreen(ctx.api, { chatId: chat.id, screen })
}

/** menu callbacks: status opens the status screen, back returns to the menu */
export function registerMenuCallbacks<C extends Context>(bot: Bot<C>, ui: BotUiDeps): void {
  bot.callbackQuery(MENU_CALLBACK.status, async (ctx) => {
    await ctx.answerCallbackQuery()
    const msg = ctx.msg
    const chat = ctx.chat
    if (chat === undefined || msg === undefined) return
    const screen = statusScreen({
      t: ui.t,
      e: ui.e,
      server: { online: false, players: 0 },
      account: { linked: false },
    })
    await editOrSendScreen(ctx.api, { chatId: chat.id, messageId: msg.message_id, screen })
  })

  bot.callbackQuery(MENU_CALLBACK.back, async (ctx) => {
    await ctx.answerCallbackQuery()
    const msg = ctx.msg
    const chat = ctx.chat
    if (chat === undefined || msg === undefined) return
    const screen = mainMenuScreen({
      t: ui.t,
      e: ui.e,
      account: { linked: false },
      actions: menuActions(),
    })
    await editOrSendScreen(ctx.api, { chatId: chat.id, messageId: msg.message_id, screen })
  })
}

export function registerStartCommand<C extends Context>(bot: Bot<C>, ui: BotUiDeps): void {
  bot.command("start", (ctx) => handleStartCommand(ctx, ui))
  bot.command("status", (ctx) => handleStatusCommand(ctx, ui))
  registerMenuCallbacks(bot, ui)
}
