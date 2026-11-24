import type { Bot } from "grammy"
import type { BotContext } from "../context.js"
import type { ApplicationService } from "../../services/application-service.js"
import type { Lang, UserRepository } from "../../services/ports.js"
import { langOf, statusLabel } from "../../services/i18n.js"
import { emoji, tr } from "../ui/runtime.js"
import { applicationCardScreen, noticeScreen, type NoticeTone } from "../ui/screens/index.js"
import { replyScreen } from "../ui/sending.js"

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

/** verdict of a saved application, as the screen dot */
function toneForStatus(status: string): NoticeTone {
  if (status === "APPROVED" || status === "ACTIVE") return "active"
  if (status === "REJECTED" || status === "BANNED" || status === "SUSPENDED") return "attention"
  return "pending"
}

/**
 * bot commands: /application (questionnaire), /myapplication, /cancel.
 * /start /status /help /stats /language live in routes.ts — registering them
 * twice would leave one handler dead and the other unreachable, so the
 * account routes own them exclusively.
 */
export function registerAdmissionCommands(bot: Bot<BotContext>, deps: CommandDeps): void {
  bot.command("application", async (ctx) => {
    const telegramId = String(ctx.from?.id ?? "")
    if (telegramId.length === 0) return

    const lang = await userLanguage(deps, telegramId)
    const t = tr(lang)

    if (ctx.conversation.active(deps.conversationId) > 0) {
      await replyScreen(ctx, noticeScreen({
        t,
        e: emoji,
        tone: "neutral",
        title: t.t("app.inProgressTitle"),
        body: t.t("app.alreadyInProgress"),
      }))
      return
    }

    const outcome = await deps.applications.startDraft(telegramId, lang)
    if (!outcome.ok) {
      const block = deps.applications.blockScreen(outcome.reason, outcome.cooldownUntil)
      await replyScreen(ctx, noticeScreen({
        t,
        e: emoji,
        tone: "attention",
        title: t.t(block.titleKey),
        body: block.date === undefined
          ? t.t(block.bodyKey)
          : t.t(block.bodyKey, { date: block.date, tz: t.t("common.tzShort") }),
      }))
      return
    }
    try {
      await ctx.conversation.enter(deps.conversationId)
    } catch {
      // a concurrent update already entered the conversation
      await replyScreen(ctx, noticeScreen({
        t,
        e: emoji,
        tone: "neutral",
        title: t.t("app.inProgressTitle"),
        body: t.t("app.alreadyInProgress"),
      }))
    }
  })

  bot.command("myapplication", async (ctx) => {
    const telegramId = String(ctx.from?.id ?? "")
    if (telegramId.length === 0) return
    const lang = await userLanguage(deps, telegramId)
    const t = tr(lang)

    const outcome = await deps.applications.getSubmittedApplication(telegramId)
    if (!outcome.ok) {
      await replyScreen(ctx, noticeScreen({
        t,
        e: emoji,
        tone: "neutral",
        title: t.t("app.noneTitle"),
        body: t.t("app.noneBody"),
      }))
      return
    }

    // the nickname lives on the user, not the application: showing the uuid
    // here would leak an opaque id at the user instead of their own name
    const user = await deps.users.findByTelegramId(telegramId)
    const questions = lang === "en" ? deps.applications.questionsEn : deps.applications.questionsRu
    const answers = outcome.application.answers.map(
      (pair, index) => `${index + 1}. ${questions[index] ?? pair.question}\n${pair.answer}`,
    )

    await replyScreen(ctx, applicationCardScreen({
      t,
      e: emoji,
      tone: toneForStatus(outcome.application.status),
      title: t.t("app.viewTitle"),
      nickname: user?.username ?? outcome.application.minecraftUuid,
      answers,
      footer: t.t("app.viewResult", { verdict: statusLabel(lang, outcome.application.status) }),
    }))
  })

  bot.command("cancel", async (ctx) => {
    const telegramId = String(ctx.from?.id ?? "")
    if (telegramId.length === 0) return
    await ctx.conversation.exit(deps.conversationId)
    const cancelled = await deps.applications.cancelDraft(telegramId)
    if (!cancelled) return
    const t = tr(await userLanguage(deps, telegramId))
    await replyScreen(ctx, noticeScreen({
      t,
      e: emoji,
      tone: "neutral",
      title: t.t("app.cancelledTitle"),
      body: t.t("app.cancelledBody"),
    }))
  })
}
