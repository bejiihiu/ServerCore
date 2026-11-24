import { Bot } from "grammy"
import { conversations } from "@grammyjs/conversations"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"
import type { BotDeps, BotContext } from "./context.js"
import { MongoConversationStorage } from "../database/conversation-storage.js"
import { registerBotRoutes } from "./routes.js"
import { EmojiRegistry } from "../i18n/emoji.js"

/**
 * builds the one and only telegram bot instance and registers every flow on
 * it: account routes, admission (questionnaire/voting/invites) and the auth
 * login callbacks ride this same instance — separate bots would silently
 * discard each other's handlers.
 *
 * the conversations plugin is installed exactly once, here, with the
 * mongo-backed storage: the plugin throws on a second install, and every
 * conversation (linking, delete-account, application) must register into the
 * same index anyway.
 *
 * returns null when no token is configured — the backend then runs without
 * the bot instead of crashing. the caller owns start/stop lifecycle.
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

  // chat_member updates must be requested explicitly or telegram never sends
  // them; the allowed_updates list goes to bot.start() in app.ts
  bot.use(
    conversations({
      storage: {
        type: "key",
        version: 0, // bump when the conversation code changes
        adapter: new MongoConversationStorage(),
      },
    }),
  )

  if (config.botCustomEmoji) {
    const registry = new EmojiRegistry(config.botCustomEmoji)
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

  // /start /status /help /stats and the account conversations all live here;
  // admission and auth register onto this same instance from app.ts
  registerBotRoutes(bot, deps)

  return bot
}
