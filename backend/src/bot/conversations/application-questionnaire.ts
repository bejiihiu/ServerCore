import type { Context } from "grammy"
import type { Conversation, ConversationFlavor } from "@grammyjs/conversations"
import type { Lang } from "../../services/ports.js"
import type { ApplicationService } from "../../services/application-service.js"
import type { VotingService } from "../../services/voting-service.js"
import { ANSWER_MAX_LENGTH } from "../../services/questionnaire.js"
import { CALLBACK } from "../keyboards.js"
import { emoji, tr } from "../ui/runtime.js"
import { applicationCardScreen, hintScreen, noticeScreen, type ScreenMessage } from "../ui/screens/index.js"
import type { ActionButton } from "../ui/keyboards/index.js"
import { replyScreen } from "../ui/sending.js"

/** the slice of services the questionnaire conversation needs */
export type QuestionnaireDeps = {
  applications: ApplicationService
  voting: VotingService
  /** a step left unanswered for this long ends the flow, the draft survives */
  idleTimeoutMs: number
}

type BotContext = Context & ConversationFlavor<Context>
type Convo = Conversation<BotContext, BotContext>

const CANCEL_DATA = CALLBACK.appCancel
const SUBMIT_DATA = CALLBACK.appSubmit

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

/**
 * the summary wait only accepts a real decision. plain text must NOT satisfy
 * it, otherwise the conversation swallows the message and walks away without
 * telling the user anything: classified as OTHER_TEXT here, it falls into the
 * nudge branch and the wait keeps going.
 */
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

function submitButton(label: string): ActionButton {
  return { label, callbackData: SUBMIT_DATA, style: "primary" }
}

function cancelButton(label: string): ActionButton {
  return { label, callbackData: CANCEL_DATA }
}

/** one questionnaire step: section title, the question, nothing else */
function questionScreen(lang: Lang, index: number, total: number, question: string): ScreenMessage {
  const t = tr(lang)
  return noticeScreen({
    t,
    e: emoji,
    tone: "pending",
    title: t.t("app.questionTitle", { n: index + 1, total }),
    body: question,
    actions: [cancelButton(t.t("app.cancelButton"))],
  })
}

function summaryScreen(lang: Lang, nickname: string, answers: ReadonlyArray<string>): ScreenMessage {
  const t = tr(lang)
  return applicationCardScreen({
    t,
    e: emoji,
    tone: "pending",
    title: t.t("app.summaryTitle"),
    nickname,
    answers,
    aside: t.t("app.summaryAside"),
    actions: [cancelButton(t.t("app.cancelButton")), submitButton(t.t("app.submitButton"))],
  })
}

function cancelledScreen(lang: Lang): ScreenMessage {
  const t = tr(lang)
  return noticeScreen({
    t,
    e: emoji,
    tone: "neutral",
    title: t.t("app.cancelledTitle"),
    body: t.t("app.cancelledBody"),
  })
}

function submittedScreen(lang: Lang): ScreenMessage {
  const t = tr(lang)
  return noticeScreen({
    t,
    e: emoji,
    tone: "active",
    title: t.t("app.submittedTitle"),
    body: t.t("app.submittedBody"),
  })
}

/**
 * closed because the user walked away. the draft is deliberately untouched:
 * the answers already saved stay in mongo and /application picks the flow up
 * at the same step.
 */
function idleScreen(lang: Lang): ScreenMessage {
  const t = tr(lang)
  return noticeScreen({
    t,
    e: emoji,
    tone: "neutral",
    title: t.t("app.idleTitle"),
    body: t.t("app.idleBody"),
  })
}

/** validation failure: same question again, only the reason changes */
function answerErrorScreen(lang: Lang, reason: "EMPTY" | "TOO_LONG"): ScreenMessage {
  const t = tr(lang)
  return reason === "EMPTY"
    ? hintScreen(t.t("app.answerEmpty"))
    : hintScreen(t.t("app.answerTooLong", { max: ANSWER_MAX_LENGTH }))
}

/**
 * one wait with an idle check around it. returns null when the update arrived
 * later than the window, so the caller closes the flow instead of treating a
 * two-hour-old answer as fresh.
 *
 * the timestamps come from `conversation.now()`, which is replayed from the
 * conversation log — a restart in the middle of the form keeps the same
 * elapsed-time verdict it would have had without one.
 */
async function waitForInput(
  conversation: Convo,
  idleTimeoutMs: number,
): Promise<BotContext | null> {
  const askedAt = await conversation.now()
  const update = await conversation.wait()
  const answeredAt = await conversation.now()
  return answeredAt - askedAt >= idleTimeoutMs ? null : update
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

      await replyScreen(ctx, questionScreen(lang, index, total, question))

      // commands must never be swallowed as an answer text: /cancel cancels,
      // every other command gets a hint and drops through to its handler.
      // anything that is not an answer re-enters the wait, and each pass
      // checks how long the user was away
      let answered = false
      while (!answered) {
        const update = await waitForInput(conversation, deps.idleTimeoutMs)
        if (update === null) {
          await replyScreen(ctx, idleScreen(lang))
          return
        }
        const input = classifyQuestionInput(update)
        if (input === null) {
          await replyScreen(update, hintScreen(tr(lang).t("app.answerNonText")))
          continue
        }

        if (input.kind === "CANCEL_BUTTON") {
          await update.answerCallbackQuery()
          await conversation.external(() => deps.applications.cancelDraft(telegramId))
          await replyScreen(ctx, cancelledScreen(lang))
          return
        }
        if (input.kind === "CANCEL_COMMAND") {
          await conversation.external(() => deps.applications.cancelDraft(telegramId))
          await replyScreen(ctx, cancelledScreen(lang))
          return
        }
        if (input.kind === "OTHER_COMMAND") {
          await replyScreen(update, hintScreen(tr(lang).t("app.commandHint")))
          continue
        }

        const saved = await conversation.external(() => deps.applications.saveDraftStep(telegramId, index, input.text))
        if (!saved.ok) {
          if (saved.reason === "NO_DRAFT") return // draft vanished mid-flow
          await replyScreen(ctx, answerErrorScreen(lang, saved.reason))
          continue // same question again, step not advanced
        }
        draft.step = index + 1
        answered = true
      }
    }

    // summary + submit/cancel, straight from the spec layout
    const questions = lang === "en" ? deps.applications.questionsEn : deps.applications.questionsRu
    const rendered = draft.answers.map((answer, i) => `${i + 1}. ${questions[i] ?? ""}\n${answer}`)
    await replyScreen(ctx, summaryScreen(lang, draft.username, rendered))

    // summary wait: buttons or /cancel; anything else (free text, stickers,
    // stale keyboards) gets a nudge and the wait continues instead of the
    // conversation silently eating the update
    let choice: SummaryInput | null = null
    while (choice === null) {
      const update = await waitForInput(conversation, deps.idleTimeoutMs)
      if (update === null) {
        await replyScreen(ctx, idleScreen(lang))
        return
      }
      const input = classifySummaryInput(update)
      if (input === null || input.kind === "OTHER_TEXT") {
        await replyScreen(update, hintScreen(tr(lang).t("app.buttonHint")))
        continue
      }
      // a decision is a button (stop its spinner) or the /cancel command
      if (input.kind !== "CANCEL_COMMAND") await update.answerCallbackQuery()
      choice = input
    }
    if (choice.kind === "CANCEL_BUTTON" || choice.kind === "CANCEL_COMMAND") {
      await conversation.external(() => deps.applications.cancelDraft(telegramId))
      await replyScreen(ctx, cancelledScreen(lang))
      return
    }
    // the wait only lets a submit through; anything else means the classifier
    // grew a new kind — never submit an application on an unknown input
    if (choice.kind !== "SUBMIT_BUTTON") return

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
      await replyScreen(ctx, submittedScreen(lang))
      return
    }

    const t = tr(lang)
    const block = deps.applications.blockScreen(outcome.reason)
    await replyScreen(ctx, noticeScreen({
      t,
      e: emoji,
      tone: "attention",
      title: t.t(block.titleKey),
      body: block.date === undefined
        ? t.t(block.bodyKey)
        : t.t(block.bodyKey, { date: block.date, tz: t.t("common.tzShort") }),
    }))
  }
}
