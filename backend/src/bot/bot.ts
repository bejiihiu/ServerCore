import { Bot } from "grammy"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"
import type { StatsService } from "../services/runtime-stats.js"
import { registerStartCommand } from "./commands/start.js"
import { registerStatsCommand } from "./commands/stats.js"
import { i18n } from "../i18n/instance.js"
import { EmojiRegistry, createEmojiRenderer } from "../i18n/emoji.js"
import { applyEmojiPack } from "../i18n/emoji-pack.js"
import { createTranslator } from "./ui/text/index.js"
import type { BotUiDeps } from "./commands/start.js"

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
 *
 * custom emoji (pack tgiosicons) is a feature: enabled via config, ids loaded
 * from the pack at startup, any failure drops to unicode fallbacks silently —
 * the ui must never depend on custom emoji being renderable.
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

  const registry = new EmojiRegistry(config.botCustomEmoji)
  const renderer = createEmojiRenderer(registry)
  const ui: BotUiDeps = { t: createTranslator(i18n), e: renderer }

  if (config.botCustomEmoji) {
    void applyEmojiPack(registry, bot.api, "tgiosicons", ["status.active", "status.pending", "status.attention", "status.neutral"])
      .then((applied) => logger.info({ applied }, "custom emoji pack loaded"))
      .catch((err: unknown) => logger.warn({ err }, "custom emoji pack unavailable, using unicode fallbacks"))
  }

  registerStartCommand(bot, ui)
  if (ops) {
    registerStatsCommand(bot, { stats: ops.stats, adminIds: config.adminTelegramIds, locale: config.opsLocale })
  }

  return bot
}
