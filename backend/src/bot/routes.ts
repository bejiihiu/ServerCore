import { conversations, createConversation } from "@grammyjs/conversations"
import type { Bot } from "grammy"
import { parseStartPayload } from "./commands/start-payload.js"
import { t, type Language } from "./i18n.js"
import { accountKeyboard, CALLBACK } from "./keyboards.js"
import { statusLabel, type AccountView } from "../modules/account/account-service.js"
import type { BotContext, BotDeps } from "./context.js"
import { linkingConversation } from "./conversations/linking.js"
import { deleteAccountConversation } from "./conversations/delete-account.js"

/**
 * wires every bot route. user-facing command set is deliberately minimal:
 * /start (deep-link entry), /status (account screen), /help.
 * /stats is admin-only (sender chat id must be in ADMIN_CHAT_IDS) and reads
 * real numbers off the live mongo deployment.
 *
 * conversation builders receive their arguments straight from
 * ctx.conversation.enter(name, ...args) — v2 serializes and replays them.
 */
export function registerBotRoutes(bot: Bot<BotContext>, deps: BotDeps): void {
  // conversations plugin with in-memory storage; multi-step flows replay
  // correctly, a process restart simply drops active conversations
  bot.use(conversations<BotContext, BotContext>())

  bot.use(
    createConversation<BotContext, BotContext>(
      (conversation, ctx, token?: string, username?: string) => {
        if (token === undefined || username === undefined) return Promise.resolve()
        return linkingConversation(conversation, ctx, deps, token, username)
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
  bot.command("help", async (ctx) => ctx.reply(t(deps.defaultLanguage, "help")))
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
    const next: Language = account.language === "ru" ? "en" : "ru"
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
    await ctx.conversation.enter("delete-account", account)
  })
}

/** /start — deep-link entry. never creates an account by itself */
export async function handleStartCommand(ctx: BotContext, deps: BotDeps): Promise<void> {
  const raw = ctx.match
  const match = typeof raw === "string" ? raw : ""
  const lang = deps.defaultLanguage
  const result = parseStartPayload(match)

  if (!result.ok) {
    await ctx.reply(result.reason === "MISSING" ? t(lang, "startNoToken") : t(lang, "startBadToken"))
    return
  }

  // preview the token without burning it: the one-time redeem happens only
  // after the user confirms, so an abandoned /start wastes nothing
  const preview = await deps.accounts.previewLinkToken(result.token)
  if (!preview.ok) {
    await ctx.reply(t(lang, "startBadToken"))
    return
  }

  await ctx.conversation.enter("linking", result.token, preview.username)
}

/** /status — the account screen */
export async function handleStatusCommand(ctx: BotContext, deps: BotDeps): Promise<void> {
  await renderAccountScreen(ctx, deps, deps.defaultLanguage)
}

/** /stats — admin-only: real collection sizes off the live mongo */
export async function handleStatsCommand(ctx: BotContext, deps: BotDeps): Promise<void> {
  const telegramId = ctx.from?.id
  if (telegramId === undefined || !deps.adminChatIds.includes(String(telegramId))) {
    // silent for strangers: don't even confirm the command exists
    return
  }
  if (deps.collectionStats === null) {
    await ctx.reply("Статистика недоступна: mongo не подключён.")
    return
  }
  const report = await deps.collectionStats.collect()
  const lines = report.collections.map((collection) => {
    const size =
      collection.storageBytes !== null ? ` (${formatBytes(collection.storageBytes)})` : ""
    return `${collection.name}: ${collection.documents} док.${size}`
  })
  const total =
    report.totalStorageBytes !== null ? `\nВсего: ${formatBytes(report.totalStorageBytes)}` : ""
  await ctx.reply(["📊 Размеры коллекций:", "", ...lines, total].filter((line) => line !== "").join("\n"))
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} КБ`
  return `${(bytes / (1024 * 1024)).toFixed(1)} МБ`
}

async function renderAccountScreen(ctx: BotContext, deps: BotDeps, lang: Language): Promise<void> {
  const telegramId = ctx.from?.id
  if (telegramId === undefined) {
    await ctx.reply(t(lang, "statusNoAccount"))
    return
  }
  const account = await deps.accounts.getByTelegramId(String(telegramId))
  if (account === null) {
    await ctx.reply(t(lang, "statusNoAccount"))
    return
  }
  await ctx.reply(
    t(lang, "accountScreen", {
      username: account.username,
      status: statusLabel(account.status),
      telegram: t(account.language, account.telegramLinked ? "statusTelegramLinked" : "statusTelegramNotLinked"),
    }),
    { reply_markup: accountKeyboard(account.language) },
  )
}
