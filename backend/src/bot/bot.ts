import { Bot } from "grammy"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"
import { registerStartCommand } from "./commands/start.js"

export type BotHandle = {
  bot: Bot
  stop: () => Promise<void>
}

/**
 * boots the telegram bot in long-polling mode. returns null when no token is
 * configured — the backend then runs without the bot instead of crashing.
 */
export async function startBot(config: AppConfig, logger: Logger): Promise<BotHandle | null> {
  if (!config.botToken) {
    logger.warn("no bot token configured, telegram bot disabled")
    return null
  }

  const bot = new Bot(config.botToken)
  bot.catch((error) => {
    logger.error({ err: error.error }, "telegram bot error")
  })

  registerStartCommand(bot)

  // long polling resolves when stop() is called, so run it detached
  void bot.start({
    onStart: (me) => {
      logger.info({ username: me.username }, "telegram bot started")
    },
  }).catch((error: unknown) => {
    logger.error({ err: error }, "telegram bot polling crashed")
  })

  return {
    bot,
    stop: async () => {
      await bot.stop()
      logger.info("telegram bot stopped")
    },
  }
}
