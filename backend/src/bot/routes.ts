import type { Bot } from "grammy"
import { createConversation } from "@grammyjs/conversations"
import { parseStartPayload } from "./commands/start-payload.js"
import { t, statusLabel, type Locale } from "../services/i18n.js"
import { accountKeyboard, CALLBACK } from "./keyboards.js"
import type { AccountView } from "../modules/account/account-service.js"
import type { BotContext, BotDeps } from "./context.js"
import { linkingConversation } from "./conversations/linking.js"
import { deleteAccountConversation } from "./conversations/delete-account.js"

/**
 * wires every bot route. user-facing command set is deliberately minimal:
 * /start (deep-link entry), /status (account screen), /help.
 * /stats is admin-only (sender id must be in ADMIN_TELEGRAM_IDS) and reads
 * real numbers off live state + the mongo deployment.
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
  bot.command("status", async (ctx) => handleStatusCommand(ctx, deps))
  // /help answers in the user's language when the account already exists
  bot.command("help", async (ctx) => {
    const telegramId = ctx.from?.id
    const account = telegramId === undefined ? null : await deps.accounts.getByTelegramId(String(telegramId))
    await ctx.reply(t(account?.language ?? deps.defaultLanguage, "bot.help"))
  })
  bot.command("stats", async (ctx) => handleStatsCommand(ctx, deps))

  // language toggle button on the account screen
  bot.callbackQuery(CALLBACK.languageToggle, async (ctx) => {
    const telegramId = ctx.from?.id
    if (telegramId === undefined) {
      await ctx.answerCallbackQuery()
      return
    }
    const account = await deps.accounts.getByTelegramId(String(telegramId))
    if (account === null) {
      await ctx.answerCallbackQuery()
      return
    }
    const next: Locale = account.language === "ru" ? "en" : "ru"
    await deps.accounts.setLanguage(account.minecraftUuid, next)
    await ctx.answerCallbackQuery()
    // re-render the screen in the new language
    await renderAccountScreen(ctx, deps, next)
  })

  // delete button opens the confirmation conversation
  bot.callbackQuery(CALLBACK.accountDelete, async (ctx) => {
    const telegramId = ctx.from?.id
    if (telegramId === undefined) {
      await ctx.answerCallbackQuery()
      return
    }
    const account = await deps.accounts.getByTelegramId(String(telegramId))
    await ctx.answerCallbackQuery()
    if (account === null || !account.telegramLinked) return
    try {
      await ctx.conversation.enter("delete-account", account)
    } catch {
      // another conversation already owns this chat — entering throws
      await ctx.reply(t(deps.defaultLanguage, "bot.conversation.busy"))
    }
  })
}

/** /start — deep-link entry. never creates an account by itself */
export async function handleStartCommand(ctx: BotContext, deps: BotDeps): Promise<void> {
  const raw = ctx.match
  const match = typeof raw === "string" ? raw : ""
  const locale = deps.defaultLanguage
  const result = parseStartPayload(match)

  if (!result.ok) {
    await ctx.reply(result.reason === "MISSING" ? t(locale, "bot.start.no_token") : t(locale, "bot.start.bad_token"))
    return
  }

  // preview the token without burning it: the one-time redeem happens only
  // after the user confirms, so an abandoned /start wastes nothing
  const preview = await deps.accounts.previewLinkToken(result.token)
  if (!preview.ok) {
    await ctx.reply(t(locale, "bot.start.bad_token"))
    return
  }

  try {
    await ctx.conversation.enter("linking", result.token, preview.username)
  } catch {
    // another conversation already owns this chat — entering throws
    await ctx.reply(t(locale, "bot.conversation.busy"))
  }
}

/** /status — the account screen */
export async function handleStatusCommand(ctx: BotContext, deps: BotDeps): Promise<void> {
  await renderAccountScreen(ctx, deps, deps.defaultLanguage)
}

async function renderAccountScreen(ctx: BotContext, deps: BotDeps, locale: Locale): Promise<void> {
  const telegramId = ctx.from?.id
  if (telegramId === undefined) {
    await ctx.reply(t(locale, "bot.account.no_account"))
    return
  }
  const account = await deps.accounts.getByTelegramId(String(telegramId))
  if (account === null) {
    await ctx.reply(t(locale, "bot.account.no_account"))
    return
  }
  await ctx.reply(
    t(account.language, "bot.account.screen", {
      username: account.username,
      status: statusLabel(account.language, account.status),
      telegram: t(
        account.language,
        account.telegramLinked ? "bot.account.telegram_linked" : "bot.account.telegram_not_linked",
      ),
    }),
    { reply_markup: accountKeyboard((key) => t(account.language, key), account.language) },
  )
}

/** /stats — admin-only: live ops snapshot plus real mongo collection sizes */
export async function handleStatsCommand(ctx: BotContext, deps: BotDeps): Promise<void> {
  const telegramId = ctx.from?.id
  if (telegramId === undefined || !deps.adminTelegramIds.includes(telegramId)) {
    // silent for strangers: don't even confirm the command exists
    return
  }

  const locale = deps.defaultLanguage
  const lines: string[] = []

  if (deps.stats !== null) {
    const snap = await deps.stats.snapshot()
    lines.push(
      t(locale, "ops.stats.uptime", { uptime: formatDuration(snap.uptimeSeconds) }),
      t(locale, "ops.stats.ws", {
        state: snap.wsRunning ? t(locale, "ops.stats.ws_up") : t(locale, "ops.stats.ws_down"),
        connections: snap.wsConnections,
      }),
      t(locale, "ops.stats.sessions", {
        active: snap.counters.activeSessions,
        guests: snap.counters.guestSessions,
        pending: snap.counters.pendingAuths,
      }),
      // live spec counter comes from the plugin's own registry via heartbeats
      t(locale, "ops.stats.spec", {
        count: snap.servers.reduce((sum, server) => sum + (server.activeSpecSessions ?? 0), 0),
      }),
    )
    // domain counters arrive when mongo is reachable; a failed count drops
    // its line instead of faking a number
    if (snap.admission !== null) {
      lines.push(t(locale, "ops.stats.admission", { count: snap.admission.openVotings }))
    }
    if (snap.accounts !== null) {
      lines.push(t(locale, "ops.stats.accounts", { count: snap.accounts.activeUsers }))
    }
    if (snap.servers.length === 0) {
      lines.push(t(locale, "ops.stats.no_servers"))
    } else {
      for (const server of snap.servers) {
        lines.push(
          t(locale, "ops.stats.server", {
            server: server.serverId,
            state: server.state ?? t(locale, "ops.stats.state_unknown"),
            players: `${server.onlinePlayers}/${server.maxPlayers}`,
            seen: server.secondsSinceLastSeen,
          }),
        )
      }
    }
  }

  if (deps.collectionStats !== null) {
    try {
      const report = await deps.collectionStats.collect()
      lines.push(t(locale, "bot.stats.db_header"))
      for (const collection of report.collections) {
        lines.push(
          t(locale, "bot.stats.db_line", {
            collection: collection.name,
            documents: collection.documents,
            size: collection.storageBytes !== null ? `(${formatBytes(collection.storageBytes)})` : "",
          }),
        )
      }
      if (report.totalStorageBytes !== null) {
        lines.push(t(locale, "bot.stats.db_total", { size: formatBytes(report.totalStorageBytes) }))
      }
    } catch {
      // mongo blip mid-command: drop the db section instead of failing /stats
    }
  }

  // both sources absent (no gateway wire and no mongo): say so instead of
  // sending an empty message, which telegram rejects
  if (lines.length === 0) {
    await ctx.reply(t(locale, "bot.stats.no_mongo"))
    return
  }
  await ctx.reply(lines.join("\n"))
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
