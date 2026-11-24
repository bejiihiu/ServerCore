import { Bot } from "grammy"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"
import type { StatsService } from "../services/runtime-stats.js"
import { registerStartCommand } from "./commands/start.js"
import { registerStatsCommand } from "./commands/stats.js"

export type BotHandle = {
  bot: Bot
}

export type BotOpsDeps = {
  stats: StatsService
}

/**
 * builds the telegram bot with all command handlers registered. returns null
 * when no token is configured - the backend then runs without the bot instead
 * of crashing. the caller owns start/stop lifecycle.
 */
export function createBot(config: AppConfig, logger: Logger, ops?: BotOpsDeps): Bot | null {
  if (!config.botToken) {
    logger.warn("no bot token configured, telegram bot disabled")
    return null
  }

  const bot = new Bot(config.botToken)
  bot.catch((error) => {
    logger.error({ err: error.error }, "telegram bot error")
  })

  registerStartCommand(bot)
  if (ops) {
    registerStatsCommand(bot, { stats: ops.stats, adminIds: config.adminTelegramIds, locale: config.opsLocale })
  }

  return bot
}
