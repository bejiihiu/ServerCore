import { Bot } from "grammy"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"
import type { BotDeps, BotContext } from "./context.js"
import type { StatsService } from "../services/runtime-stats.js"
import { registerBotRoutes } from "./routes.js"
import { registerStartCommand } from "./commands/start.js"
import { registerStatsCommand } from "./commands/stats.js"
import { i18n as i18nInstance } from "../i18n/instance.js"
import { EmojiRegistry, createEmojiRenderer } from "../i18n/emoji.js"
import { createTranslator, type Translator } from "./ui/text/index.js"
import type { EmojiRenderer } from "../i18n/emoji.js"

export type BotOpsDeps = {
  stats: StatsService
}

/** optional telegram handlers contributed by feature modules */
export type BotModuleDeps = {
  /** login confirmation buttons, wired by the authentication module */
  registerCallbacks?: (bot: Bot<BotContext>) => void
}

/**
 * builds the telegram bot with all routes registered. returns null when no
 * token is configured — the backend then runs without the bot instead of
 * crashing. the caller owns start/stop lifecycle.
 */
export function createBot(config: AppConfig, logger: Logger, deps: BotDeps, ops?: BotOpsDeps, modules?: BotModuleDeps): Bot<BotContext> | null {
  if (!config.botToken) {
    logger.warn("no bot token configured, telegram bot disabled")
    return null
  }

  const bot: Bot<BotContext> = new Bot<BotContext>(config.botToken)
  bot.catch((error) => {
    logger.error({ err: error.error }, "telegram bot error")
  })

  const i18n = i18nInstance
  const registry = new EmojiRegistry(config.botCustomEmoji)
  const renderer = createEmojiRenderer(registry)
  const ui: { t: Translator; e: EmojiRenderer } = { t: createTranslator(i18n), e: renderer }

  if (config.botCustomEmoji) {
    void import("../i18n/emoji-pack.js")
      .then(({ applyEmojiPack }) =>
        applyEmojiPack(registry, bot.api, "tgiosicons", [
          "status.active",
          "status.pending",
          "status.attention",
          "status.neutral",
        ]))
      .then((applied) => logger.info({ applied }, "custom emoji pack loaded"))
      .catch((err: unknown) => logger.warn({ err }, "custom emoji pack unavailable, using unicode fallbacks"))
  }

  registerBotRoutes(bot, deps)
  registerStartCommand(bot, ui)
  if (ops) {
    registerStatsCommand(bot, { stats: ops.stats, adminIds: config.adminTelegramIds, locale: config.opsLocale })
  }
  modules?.registerCallbacks?.(bot)

  return bot
}
