import type { Bot } from "grammy"
import type { BotContext } from "../context.js"
import type { ApplicationService } from "../../services/application-service.js"
import type { Lang, UserRepository } from "../../services/ports.js"
import { formatApplicationView } from "../../services/voting-format.js"
import { langOf, statusLabel, t } from "../../services/i18n.js"

export type CommandDeps = {
  applications: ApplicationService
  users: UserRepository
  defaultLanguage: Lang
  conversationId: string
}

async function userLanguage(deps: CommandDeps, telegramId: string): Promise<Lang> {
  const user = await deps.users.findByTelegramId(telegramId)
  return langOf(user?.language, deps.defaultLanguage)
}

/**
 * bot commands: /application (questionnaire), /myapplication, /status,
 * /cancel, /language, /help. /start lives in commands/start.ts — the
 * account linking flow.
 */
export function registerAdmissionCommands(bot: Bot<BotContext>, deps: CommandDeps): void {
  bot.command("application", async (ctx) => {
    const telegramId = String(ctx.from?.id ?? "")
    if (telegramId.length === 0) return

    if (ctx.conversation.active(deps.conversationId) > 0) {
      await ctx.reply(t(deps.defaultLanguage, "app.alreadyInProgress"))
      return
    }

    const lang = await userLanguage(deps, telegramId)
    const outcome = await deps.applications.startDraft(telegramId, lang)
    if (!outcome.ok) {
      await ctx.reply(deps.applications.blockText(outcome.reason, lang, outcome.cooldownUntil))
      return
    }
    try {
      await ctx.conversation.enter(deps.conversationId)
    } catch {
      // a concurrent update already entered the conversation
      await ctx.reply(t(lang, "app.alreadyInProgress"))
    }
  })

  bot.command("myapplication", async (ctx) => {
    const telegramId = String(ctx.from?.id ?? "")
    if (telegramId.length === 0) return
    const lang = await userLanguage(deps, telegramId)
    const outcome = await deps.applications.getSubmittedApplication(telegramId)
    if (!outcome.ok) {
      await ctx.reply(t(lang, "app.viewNone"))
      return
    }
    const questions = lang === "en" ? deps.applications.questionsEn : deps.applications.questionsRu
    await ctx.reply(formatApplicationView(outcome.application, lang, questions))
  })

  bot.command("status", async (ctx) => {
    const telegramId = String(ctx.from?.id ?? "")
    if (telegramId.length === 0) return
    const user = await deps.users.findByTelegramId(telegramId)
    if (!user) {
      await ctx.reply(t(deps.defaultLanguage, "status.notFound"))
      return
    }
    const lang = langOf(user.language, deps.defaultLanguage)
    if (user.telegramId.length === 0) {
      await ctx.reply(t(lang, "status.notLinked"))
      return
    }
    await ctx.reply(
      t(lang, "status.reply", {
        username: user.username,
        status: statusLabel(lang, user.status),
      }),
    )
  })

  bot.command("cancel", async (ctx) => {
    const telegramId = String(ctx.from?.id ?? "")
    if (telegramId.length === 0) return
    const lang = await userLanguage(deps, telegramId)
    await ctx.conversation.exit(deps.conversationId)
    const cancelled = await deps.applications.cancelDraft(telegramId)
    if (cancelled) await ctx.reply(t(lang, "app.cancelled"))
  })

  bot.command("language", async (ctx) => {
    await ctx.reply(t(deps.defaultLanguage, "lang.choose"), {
      reply_markup: {
        inline_keyboard: [
          [
            { text: "Русский", callback_data: "lang:ru" },
            { text: "English", callback_data: "lang:en" },
          ],
        ],
      },
    })
  })

  bot.callbackQuery(/^lang:/, async (ctx) => {
    const telegramId = String(ctx.from.id)
    const chosen = ctx.callbackQuery.data === "lang:en" ? "en" : "ru"
    const user = await deps.users.findByTelegramId(telegramId)
    if (user) {
      await deps.users.setLanguage(user.uuid, chosen)
      await ctx.answerCallbackQuery({ text: t(chosen, "lang.changed") })
      return
    }
    await ctx.answerCallbackQuery({ text: t(deps.defaultLanguage, "status.notFound") })
  })

  bot.command("help", (ctx) => {
    void userLanguage(deps, String(ctx.from?.id ?? "")).then((lang) => ctx.reply(t(lang, "help.text")))
  })
}
