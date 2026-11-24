import type { Bot } from "grammy"
import { createConversation } from "@grammyjs/conversations"
import { parseStartPayload } from "./commands/start-payload.js"
import { statusLabel, type Locale } from "../services/i18n.js"
import { CALLBACK, accountActions } from "./keyboards.js"
import type { AccountView } from "../modules/account/account-service.js"
import type { BotContext, BotDeps } from "./context.js"
import { linkingConversation } from "./conversations/linking.js"
import { deleteAccountConversation } from "./conversations/delete-account.js"
import { emoji, tr } from "./ui/runtime.js"
import {
  ACCOUNT_CALLBACK,
  MENU_CALLBACK,
  accountCardScreen,
  languagePickerScreen,
  mainMenuScreen,
  noticeScreen,
  statusScreen,
  type ScreenMessage,
} from "./ui/screens/index.js"
import { editOrSendScreen, replyScreen } from "./ui/sending.js"
import { kv, section } from "./ui/text/index.js"
import { summarizeServer } from "./status-summary.js"

/**
 * wires every bot route. user-facing command set is deliberately minimal:
 * /start (deep-link entry + menu), /status (server + account), /help.
 * /stats is admin-only (sender id must be in ADMIN_TELEGRAM_IDS) and reads
 * real numbers off live state + the mongo deployment.
 *
 * every reply goes through a screen builder: one semantic dot, bold labels,
 * no bare template strings.
 *
 * conversation builders receive their arguments straight from
 * ctx.conversation.enter(name, ...args) — v2 serializes and replays them.
 */
export function registerBotRoutes(bot: Bot<BotContext>, deps: BotDeps): void {
  // the conversations plugin itself is installed exactly once in createBot
  // (mongo-backed); these two conversations register into that shared index
  bot.use(
    createConversation<BotContext, BotContext>(
      (conversation, ctx, token?: string, username?: string) => {
        if (token === undefined || username === undefined) return Promise.resolve()
        return linkingConversation(
          conversation,
          ctx,
          { accounts: deps.accounts, fallbackLanguage: deps.defaultLanguage },
          token,
          username,
        )
      },
      { id: "linking" },
    ),
  )

  bot.use(
    createConversation<BotContext, BotContext>(
      (conversation, ctx, account?: AccountView) => {
        if (account === undefined) return Promise.resolve()
        return deleteAccountConversation(conversation, ctx, { deletion: deps.deletion }, account)
      },
      { id: "delete-account" },
    ),
  )

  bot.command("start", async (ctx) => handleStartCommand(ctx, deps))
  bot.command("status", async (ctx) => showStatus(ctx, deps, await localeOf(ctx, deps)))
  bot.command("help", async (ctx) => showHelp(ctx, await localeOf(ctx, deps)))
  bot.command("stats", async (ctx) => handleStatsCommand(ctx, deps))
  bot.command("language", async (ctx) => handleLanguageCommand(ctx, deps))

  // main menu navigation: every button answers by rewriting the message it
  // sits on, so double presses land on the same screen instead of stacking
  bot.callbackQuery(MENU_CALLBACK.back, async (ctx) => {
    await ctx.answerCallbackQuery()
    await showMenu(ctx, deps, await localeOf(ctx, deps), "edit")
  })
  bot.callbackQuery(MENU_CALLBACK.status, async (ctx) => {
    await ctx.answerCallbackQuery()
    await showStatus(ctx, deps, await localeOf(ctx, deps), "edit")
  })
  bot.callbackQuery(MENU_CALLBACK.account, async (ctx) => {
    await ctx.answerCallbackQuery()
    await showAccount(ctx, deps, await localeOf(ctx, deps), "edit")
  })
  bot.callbackQuery(MENU_CALLBACK.help, async (ctx) => {
    await ctx.answerCallbackQuery()
    await showHelp(ctx, await localeOf(ctx, deps), "edit")
  })

  // account screen refresh (the data behind it is live, so a press re-reads)
  bot.callbackQuery(ACCOUNT_CALLBACK.refresh, async (ctx) => {
    await ctx.answerCallbackQuery()
    await showAccount(ctx, deps, await localeOf(ctx, deps), "edit")
  })

  // language toggle on the account screen: stores the other language, then
  // re-renders the same message in it
  bot.callbackQuery(CALLBACK.languageToggle, async (ctx) => {
    const account = await accountOf(ctx, deps)
    await ctx.answerCallbackQuery()
    if (account === null || !account.telegramLinked) return
    const next: Locale = account.language === "ru" ? "en" : "ru"
    await deps.accounts.setLanguage(account.minecraftUuid, next)
    await showAccount(ctx, deps, next, "edit")
  })

  // delete button opens the confirmation conversation
  bot.callbackQuery(CALLBACK.delete, async (ctx) => {
    const account = await accountOf(ctx, deps)
    await ctx.answerCallbackQuery()
    if (account === null || !account.telegramLinked) return
    try {
      await ctx.conversation.enter("delete-account", account)
    } catch {
      // another conversation already owns this chat — entering throws
      await replyScreen(ctx, busyScreen(account.language))
    }
  })

  // buttons of a conversation that already finished: answering keeps the
  // spinner from hanging forever on a stale press
  bot.callbackQuery(/^(link|del|app):/, async (ctx) => {
    await ctx.answerCallbackQuery({ text: tr(await localeOf(ctx, deps)).t("common.staleButton") })
  })

  registerLanguageCallbacks(bot, deps)
}

/** where a screen goes: a fresh message, or the message its button lives on */
type RenderMode = "new" | "edit"

async function show(ctx: BotContext, screen: ScreenMessage, mode: RenderMode): Promise<void> {
  if (mode === "edit") {
    const messageId = ctx.callbackQuery?.message?.message_id
    const chatId = ctx.chat?.id
    if (messageId !== undefined && chatId !== undefined) {
      await editOrSendScreen(ctx.api, { chatId, messageId, screen })
      return
    }
  }
  await replyScreen(ctx, screen)
}

async function accountOf(ctx: BotContext, deps: BotDeps): Promise<AccountView | null> {
  const telegramId = ctx.from?.id
  return telegramId === undefined ? null : deps.accounts.getByTelegramId(String(telegramId))
}

/** the language the user's account speaks, ops default until it exists */
async function localeOf(ctx: BotContext, deps: BotDeps): Promise<Locale> {
  const account = await accountOf(ctx, deps)
  return account?.language ?? deps.defaultLanguage
}

async function showMenu(ctx: BotContext, deps: BotDeps, locale: Locale, mode: RenderMode = "new"): Promise<void> {
  const account = await accountOf(ctx, deps)
  const linked = account !== null && account.telegramLinked
  const screen = mainMenuScreen({
    t: tr(locale),
    e: emoji,
    account: linked && account !== null ? { linked: true, nickname: account.username } : { linked: false },
    actions: [
      { key: "status", enabled: true, callbackData: MENU_CALLBACK.status },
      { key: "account", enabled: true, callbackData: MENU_CALLBACK.account },
      { key: "help", enabled: true, callbackData: MENU_CALLBACK.help },
    ],
  })
  await show(ctx, screen, mode)
}

async function showStatus(ctx: BotContext, deps: BotDeps, locale: Locale, mode: RenderMode = "new"): Promise<void> {
  const account = await accountOf(ctx, deps)
  // a stats read must never take the screen down: no number reads as unknown
  const snapshot = deps.stats === null ? null : await deps.stats.snapshot().catch(() => null)
  const screen = statusScreen({
    t: tr(locale),
    e: emoji,
    server: summarizeServer(snapshot),
    account: account?.telegramLinked === true
      ? { linked: true, nickname: account.username }
      : { linked: false },
  })
  await show(ctx, screen, mode)
}

async function showAccount(ctx: BotContext, deps: BotDeps, locale: Locale, mode: RenderMode = "new"): Promise<void> {
  const account = await accountOf(ctx, deps)
  const linked = account !== null && account.telegramLinked
  const current = account?.language ?? locale
  const t = tr(current)
  const nextLanguage = t.t(current === "ru" ? "lang.nameEn" : "lang.nameRu")

  const screen = accountCardScreen({
    t,
    e: emoji,
    account: linked && account !== null
      ? { linked: true, nickname: account.username, status: statusLabel(current, account.status) }
      : { linked: false },
    actions: accountActions({
      labels: {
        refresh: t.t("account.action.refresh"),
        language: t.t("account.action.language", { next: nextLanguage }),
        delete: t.t("account.action.delete"),
      },
      canDelete: linked,
    }),
  })
  await show(ctx, screen, mode)
}

async function showHelp(ctx: BotContext, locale: Locale, mode: RenderMode = "new"): Promise<void> {
  const t = tr(locale)
  const screen = noticeScreen({
    t,
    e: emoji,
    title: t.t("help.title"),
    details: t.t("help.commands"),
    aside: t.t("help.aside"),
    back: MENU_CALLBACK.back,
  })
  await show(ctx, screen, mode)
}

async function handleLanguageCommand(ctx: BotContext, deps: BotDeps): Promise<void> {
  const account = await accountOf(ctx, deps)
  if (account === null || !account.telegramLinked) {
    const t = tr(await localeOf(ctx, deps))
    await replyScreen(ctx, noticeScreen({
      t,
      e: emoji,
      tone: "neutral",
      title: t.t("lang.pickTitle"),
      body: t.t("lang.noAccount"),
      back: MENU_CALLBACK.back,
    }))
    return
  }
  await replyScreen(ctx, pickerScreen(account.language))
}

function pickerScreen(current: Locale): ScreenMessage {
  const t = tr(current)
  return languagePickerScreen({
    t,
    e: emoji,
    current,
    options: [
      { code: "ru", label: t.t("lang.nameRu"), callbackData: CALLBACK.langRu },
      { code: "en", label: t.t("lang.nameEn"), callbackData: CALLBACK.langEn },
    ],
    back: MENU_CALLBACK.back,
  })
}

function registerLanguageCallbacks(bot: Bot<BotContext>, deps: BotDeps): void {
  const handler = async (ctx: BotContext, chosen: Locale): Promise<void> => {
    const account = await accountOf(ctx, deps)
    if (account === null || !account.telegramLinked) {
      await ctx.answerCallbackQuery({ text: tr(chosen).t("lang.noAccount") })
      return
    }
    await ctx.answerCallbackQuery()
    await deps.accounts.setLanguage(account.minecraftUuid, chosen)
    // the picker turns into the confirmation in the language just picked:
    // a second press shows the same state, not a stale list
    const t = tr(chosen)
    await show(ctx, noticeScreen({
      t,
      e: emoji,
      tone: "active",
      title: t.t("lang.savedTitle"),
      body: t.t("lang.savedBody"),
      back: MENU_CALLBACK.back,
    }), "edit")
  }

  bot.callbackQuery(CALLBACK.langRu, async (ctx) => handler(ctx, "ru"))
  bot.callbackQuery(CALLBACK.langEn, async (ctx) => handler(ctx, "en"))
}

/** the deep-link token is dead: what happened plus the one step that fixes it */
function badTokenScreen(locale: Locale): ScreenMessage {
  const t = tr(locale)
  return noticeScreen({
    t,
    e: emoji,
    tone: "attention",
    title: t.t("start.badTokenTitle"),
    body: t.t("start.badTokenBody"),
    aside: t.t("start.badTokenHint"),
    back: MENU_CALLBACK.back,
  })
}

/** another conversation owns the chat: nothing broke, the user has to finish it */
function busyScreen(locale: Locale): ScreenMessage {
  const t = tr(locale)
  return noticeScreen({
    t,
    e: emoji,
    tone: "attention",
    title: t.t("common.errorTitle"),
    body: t.t("common.busy"),
  })
}

/** /start — deep-link entry. never creates an account by itself */
export async function handleStartCommand(ctx: BotContext, deps: BotDeps): Promise<void> {
  const raw = ctx.match
  const match = typeof raw === "string" ? raw : ""
  const result = parseStartPayload(match)

  if (!result.ok) {
    // no payload: show the menu (payload-less /start is a plain "open the bot")
    if (result.reason === "MISSING") {
      await showMenu(ctx, deps, await localeOf(ctx, deps))
      return
    }
    await replyScreen(ctx, badTokenScreen(await localeOf(ctx, deps)))
    return
  }

  // preview the token without burning it: the one-time redeem happens only
  // after the user confirms, so an abandoned /start wastes nothing
  const preview = await deps.accounts.previewLinkToken(result.token)
  if (!preview.ok) {
    await replyScreen(ctx, badTokenScreen(await localeOf(ctx, deps)))
    return
  }

  try {
    await ctx.conversation.enter("linking", result.token, preview.username)
  } catch {
    // another conversation already owns this chat — entering throws
    await replyScreen(ctx, busyScreen(await localeOf(ctx, deps)))
  }
}

/** /stats — admin-only: live ops snapshot plus real mongo collection sizes */
export async function handleStatsCommand(ctx: BotContext, deps: BotDeps): Promise<void> {
  const telegramId = ctx.from?.id
  if (telegramId === undefined || !deps.adminTelegramIds.includes(telegramId)) {
    // silent for strangers: don't even confirm the command exists
    return
  }

  const locale = deps.defaultLanguage
  const t = tr(locale)
  const blocks: string[] = []

  if (deps.stats !== null) {
    const snap = await deps.stats.snapshot()
    const lines: string[] = [
      kv(t.t("stats.uptime"), formatDuration(snap.uptimeSeconds)),
      kv(t.t("stats.ws"), t.t("stats.wsValue", {
        state: snap.wsRunning ? t.t("stats.wsUp") : t.t("stats.wsDown"),
        connections: snap.wsConnections,
      })),
      kv(t.t("stats.sessions"), t.t("stats.sessionsValue", {
        active: snap.counters.activeSessions,
        guests: snap.counters.guestSessions,
        pending: snap.counters.pendingAuths,
      })),
    ]
    // domain counters arrive when mongo is reachable; a failed count drops
    // its line instead of faking a number
    if (snap.admission !== null) lines.push(kv(t.t("stats.admission"), t.t("stats.admissionValue", { count: snap.admission.openVotings })))
    if (snap.accounts !== null) lines.push(kv(t.t("stats.accounts"), t.t("stats.accountsValue", { count: snap.accounts.activeUsers })))
    // live spec counter comes from the plugin's own registry via heartbeats
    lines.push(kv(t.t("stats.spec"), t.t("stats.specValue", {
      count: snap.servers.reduce((sum, server) => sum + (server.activeSpecSessions ?? 0), 0),
    })))

    const servers: string[] = [section(t.t("stats.serversSection"))]
    if (snap.servers.length === 0) {
      servers.push(t.t("stats.noServers"))
    } else {
      for (const server of snap.servers) {
        servers.push(kv(server.serverId, t.t("stats.serverLine", {
          state: server.state ?? t.t("stats.serverState"),
          players: `${server.onlinePlayers}/${server.maxPlayers}`,
          seen: server.secondsSinceLastSeen,
        })))
      }
    }
    blocks.push(lines.join("\n"), servers.join("\n"))
  }

  if (deps.collectionStats !== null) {
    try {
      const report = await deps.collectionStats.collect()
      const rows: string[] = [section(t.t("stats.dbSection"))]
      for (const collection of report.collections) {
        rows.push(kv(collection.name, t.t("stats.dbLine", {
          documents: collection.documents,
          size: collection.storageBytes !== null ? `(${formatBytes(collection.storageBytes)})` : "",
        })))
      }
      if (report.totalStorageBytes !== null) {
        rows.push(t.t("stats.dbTotal", { size: formatBytes(report.totalStorageBytes) }))
      }
      blocks.push(rows.join("\n"))
    } catch {
      // mongo blip mid-command: drop the db section instead of failing /stats
    }
  }

  await replyScreen(ctx, noticeScreen({
    t,
    e: emoji,
    tone: blocks.length === 0 ? "attention" : "neutral",
    title: t.t("stats.title"),
    // both sources absent (no gateway wire and no mongo): say so instead of
    // sending an empty message, which telegram rejects
    body: blocks.length === 0 ? t.t("stats.unavailable") : blocks.join("\n\n"),
  }))
}

function formatDuration(totalSeconds: number): string {
  const days = Math.floor(totalSeconds / 86_400)
  const hours = Math.floor((totalSeconds % 86_400) / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const parts: string[] = []
  if (days > 0) parts.push(`${days}d`)
  if (hours > 0) parts.push(`${hours}h`)
  parts.push(`${minutes}m`)
  return parts.join(" ")
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
