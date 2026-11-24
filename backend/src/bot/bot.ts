import { Bot } from "grammy"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"
import type { BotDeps } from "./context.js"
import type { BotContext } from "./context.js"
import { registerBotRoutes } from "./routes.js"

/**
 * builds the telegram bot with all routes registered. returns null when no
 * token is configured — the backend then runs without the bot instead of
 * crashing. the caller owns start/stop lifecycle.
 */
export function createBot(config: AppConfig, logger: Logger, deps: BotDeps): Bot<BotContext> | null {
  if (!config.botToken) {
    logger.warn("no bot token configured, telegram bot disabled")
    return null
  }

  const bot: Bot<BotContext> = new Bot<BotContext>(config.botToken)
  bot.catch((error) => {
    logger.error({ err: error.error }, "telegram bot error")
  })

  registerBotRoutes(bot, deps)

  return bot
}
