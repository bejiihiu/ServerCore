import type { Context } from "grammy"
import type { Conversation, ConversationFlavor } from "@grammyjs/conversations"
import type { InlineKeyboard, Lang } from "../../services/ports.js"
import type { ApplicationService } from "../../services/application-service.js"
import type { VotingService } from "../../services/voting-service.js"
import { t } from "../../services/i18n.js"
import { answerValidationErrorText } from "../../services/questionnaire.js"
import { toGrammYMarkup } from "../telegram-api.js"

/** the slice of services the questionnaire conversation needs */
export type QuestionnaireDeps = {
  applications: ApplicationService
  voting: VotingService
}

type BotContext = Context & ConversationFlavor<Context>
type Convo = Conversation<BotContext, BotContext>

const CANCEL_DATA = "app:cancel"
const SUBMIT_DATA = "app:submit"

function cancelKeyboard(lang: Lang): InlineKeyboard {
  return [[{ text: t(lang, "app.cancelButton"), data: CANCEL_DATA }]]
}

function submitKeyboard(lang: Lang): InlineKeyboard {
  return [
    [{ text: t(lang, "app.submitButton"), data: SUBMIT_DATA }],
    [{ text: t(lang, "app.cancelButton"), data: CANCEL_DATA }],
  ]
}

/** minimal update shape the classifiers look at */
type RawUpdate = {
  callbackQuery?: { data?: string }
  message?: { text?: string }
}

/**
 * how an update waiting for a questionnaire answer is treated. commands get
 * their own kinds: without this, the conversation would swallow /cancel (and
 * every other command) and store it as the answer text.
 */
export type QuestionInput =
  | { kind: "CANCEL_BUTTON" }
  | { kind: "CANCEL_COMMAND" }
  | { kind: "OTHER_COMMAND" }
  | { kind: "ANSWER"; text: string }

export function classifyQuestionInput(update: RawUpdate): QuestionInput | null {
  if (update.callbackQuery?.data === CANCEL_DATA) return { kind: "CANCEL_BUTTON" }
  const text = update.message?.text
  if (typeof text !== "string") return null // stickers, photos, other buttons
  const trimmed = text.trim()
  if (trimmed === "/cancel") return { kind: "CANCEL_COMMAND" }
  if (trimmed.startsWith("/")) return { kind: "OTHER_COMMAND" }
  return { kind: "ANSWER", text }
}

/** same for the summary screen: submit/cancel buttons or a /cancel command */
export type SummaryInput =
  | { kind: "SUBMIT_BUTTON" }
  | { kind: "CANCEL_BUTTON" }
  | { kind: "CANCEL_COMMAND" }
  | { kind: "OTHER_TEXT" }

export function classifySummaryInput(update: RawUpdate): SummaryInput | null {
  if (update.callbackQuery?.data === SUBMIT_DATA) return { kind: "SUBMIT_BUTTON" }
  if (update.callbackQuery?.data === CANCEL_DATA) return { kind: "CANCEL_BUTTON" }
  if (typeof update.message?.text !== "string") return null
  if (update.message.text.trim() === "/cancel") return { kind: "CANCEL_COMMAND" }
  return { kind: "OTHER_TEXT" }
}

/**
 * builds the questionnaire conversation builder, closing over the services
 * (conversation args must stay json-serializable, so services travel via
 * closure, not via enter() arguments).
 *
 * one question = one message, per the spec. the draft lives in mongo and
 * the conversations plugin replays from its persisted state, so a restart
 * mid-form resumes exactly where the user stopped.
 */
export function createApplicationConversation(deps: QuestionnaireDeps) {
  return async function applicationConversation(conversation: Convo, ctx: BotContext): Promise<void> {
    const telegramId = String(ctx.from?.id ?? "")
    if (telegramId.length === 0) return

    // db reads/writes always go through external: run once, replayed from log
    const draft = await conversation.external(() => deps.applications.findDraftForConversation(telegramId))
    if (!draft) return // draft was cancelled while we were not looking

    const lang = draft.language
    const total = deps.applications.questionCount

    // answer loop: one question per message
    while (draft.step < total) {
      const index = draft.step
      const question = deps.applications.questionText(lang, index)
      if (question === null) return

      await ctx.reply(t(lang, "app.question", { n: index + 1, total, question }), {
        reply_markup: toGrammYMarkup(cancelKeyboard(lang)),
      })

      // commands must never be swallowed as an answer text: /cancel cancels,
      // every other command gets a hint and drops through to its handler
      const update = await conversation.waitUntil(
        (c) => classifyQuestionInput(c) !== null,
        { otherwise: (nonAnswer) => void nonAnswer.reply(t(lang, "app.answerNonText")) },
      )
      const input = classifyQuestionInput(update)
      if (input === null) continue

      if (input.kind === "CANCEL_BUTTON") {
        await ctx.answerCallbackQuery()
        await conversation.external(() => deps.applications.cancelDraft(telegramId))
        await ctx.reply(t(lang, "app.cancelled"))
        return
      }
      if (input.kind === "CANCEL_COMMAND") {
        await conversation.external(() => deps.applications.cancelDraft(telegramId))
        await ctx.reply(t(lang, "app.cancelled"))
        return
      }
      if (input.kind === "OTHER_COMMAND") {
        await ctx.reply(t(lang, "app.commandHint"))
        continue
      }

      const saved = await conversation.external(() => deps.applications.saveDraftStep(telegramId, index, input.text))
      if (!saved.ok) {
        if (saved.reason === "NO_DRAFT") return // draft vanished mid-flow
        await ctx.reply(answerValidationErrorText(saved.reason, lang))
        continue // same question again, step not advanced
      }
      draft.step = index + 1
    }

    // summary + submit/cancel, straight from the spec layout
    const questions = lang === "en" ? deps.applications.questionsEn : deps.applications.questionsRu
    const rendered = draft.answers
      .map((answer, i) => `${i + 1}. ${questions[i] ?? ""}\n${answer}`)
      .join("\n\n")
    await ctx.reply(t(lang, "app.summary", { username: draft.username, answers: rendered }), {
      reply_markup: toGrammYMarkup(submitKeyboard(lang)),
    })

    // summary wait: buttons or /cancel; anything else gets a nudge instead
    // of vanishing into a silent swallow
    const choiceUpdate = await conversation.waitUntil(
      (c) => classifySummaryInput(c) !== null,
      { otherwise: (nonAnswer) => void nonAnswer.reply(t(lang, "app.buttonHint")) },
    )
    const choice = classifySummaryInput(choiceUpdate)
    if (choice === null) return
    if (choice.kind === "SUBMIT_BUTTON" || choice.kind === "CANCEL_BUTTON") {
      await ctx.answerCallbackQuery()
    }
    if (choice.kind === "CANCEL_BUTTON" || choice.kind === "CANCEL_COMMAND") {
      await conversation.external(() => deps.applications.cancelDraft(telegramId))
      await ctx.reply(t(lang, "app.cancelled"))
      return
    }
    if (choice.kind === "OTHER_TEXT") return

    // submit + voting publication in one side-effecting step: the group
    // message and the notifications must not re-run on replays
    const outcome = await conversation.external(async () => {
      const submitResult = await deps.applications.submit(telegramId)
      if (submitResult.ok) {
        await deps.voting.openVoting(submitResult.application)
      }
      return submitResult
    })

    if (outcome.ok) {
      await ctx.reply(t(lang, "app.submitted"))
      return
    }
    await ctx.reply(deps.applications.blockText(outcome.reason, lang))
  }
}
