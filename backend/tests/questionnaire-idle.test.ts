import { describe, expect, test } from "bun:test"
import type { ApplicationService } from "../src/services/application-service.js"
import type { VotingService } from "../src/services/voting-service.js"
import {
  createApplicationConversation,
  type QuestionnaireDeps,
} from "../src/bot/conversations/application-questionnaire.js"
import { CALLBACK } from "../src/bot/keyboards.js"
import { tr } from "../src/bot/ui/runtime.js"
import { scriptedConversation, type ConversationStep } from "./helpers/conversation-harness.js"

/**
 * the questionnaire is the one conversation a user can leave half-finished,
 * so the two promises worth testing are: an answer given inside the window is
 * saved exactly once, and an answer given after the window closes the flow
 * without touching the draft or the voting pipeline.
 */

const QUESTIONS = ["Как давно играешь?", "Почему хочешь играть?"]
const IDLE_MS = 30 * 60_000

type Draft = {
  telegramId: string
  minecraftUuid: string
  username: string
  step: number
  answers: string[]
  language: "ru" | "en"
}

function fakeApplications(draft: Draft | null) {
  const state = {
    draft: draft === null ? null : { ...draft },
    saves: [] as Array<{ step: number; text: string }>,
    cancelled: 0,
    submitted: 0,
  }
  const applications = {
    questionCount: QUESTIONS.length,
    questionsRu: QUESTIONS,
    questionsEn: QUESTIONS,
    async findDraftForConversation() {
      return state.draft
    },
    questionText(_lang: string, index: number) {
      return QUESTIONS[index] ?? null
    },
    async saveDraftStep(_telegramId: string, step: number, text: string) {
      state.saves.push({ step, text })
      if (state.draft === null) return { ok: false, reason: "NO_DRAFT" }
      const answers = [...state.draft.answers]
      answers[step] = text
      state.draft = { ...state.draft, step: step + 1, answers }
      return { ok: true }
    },
    async cancelDraft() {
      state.cancelled++
      state.draft = null
      return true
    },
    async submit() {
      state.submitted++
      return {
        ok: true,
        application: { minecraftUuid: "u1", telegramId: "777000111" },
      }
    },
    blockScreen() {
      return { titleKey: "app.blockedStatusTitle", bodyKey: "app.blockedStatusBody" }
    },
  }
  return { applications: applications as unknown as ApplicationService, state }
}

function build(applications: ApplicationService, idleTimeoutMs = IDLE_MS) {
  const opened: string[] = []
  const voting = {
    async openVoting(application: { minecraftUuid: string }) {
      opened.push(application.minecraftUuid)
    },
  }
  const deps: QuestionnaireDeps = {
    applications,
    voting: voting as unknown as VotingService,
    idleTimeoutMs,
  }
  return { conversation: createApplicationConversation(deps), opened }
}

function draftAt(step: number, answers: string[] = []): Draft {
  return {
    telegramId: "777000111",
    minecraftUuid: "u1",
    username: "Steve",
    step,
    answers,
    language: "ru",
  }
}

async function run(conversation: ReturnType<typeof build>["conversation"], steps: ConversationStep[]) {
  const harness = scriptedConversation({ steps })
  await conversation(harness.conversation, harness.ctx)
  return harness
}

function lastText(sent: Array<{ text: string }>): string {
  return sent.at(-1)?.text ?? ""
}

describe("application questionnaire idle timeout", () => {
  test("an answer that arrives inside the window is saved and the flow moves on", async () => {
    const { applications, state } = fakeApplications(draftAt(0))
    const { conversation } = build(applications)

    // second question is shown, then the script runs out: abandoned, not idle
    await expect(
      run(conversation, [{ kind: "text", text: "лет пять", advanceMs: IDLE_MS - 1_000 }]),
    ).rejects.toThrow("conversation abandoned")

    expect(state.saves).toEqual([{ step: 0, text: "лет пять" }])
    expect(state.draft?.step).toBe(1)
  })

  test("a late answer closes the flow with the idle screen and never reaches the draft", async () => {
    const { applications, state } = fakeApplications(draftAt(0))
    const { conversation, opened } = build(applications)

    const harness = await run(conversation, [{ kind: "text", text: "лет пять", advanceMs: IDLE_MS }])

    expect(lastText(harness.sent)).toContain(tr("ru").t("app.idleTitle"))
    expect(lastText(harness.sent)).toContain(tr("ru").t("app.idleBody"))
    // the draft is exactly as the user left it
    expect(state.saves).toEqual([])
    expect(state.cancelled).toBe(0)
    expect(state.submitted).toBe(0)
    expect(state.draft).toEqual(draftAt(0))
    expect(opened).toEqual([])
  })

  test("a nudge inside the window keeps the flow alive", async () => {
    const { applications, state } = fakeApplications(draftAt(0))
    const { conversation } = build(applications)

    // a sticker, then a real answer, both well inside the window
    const harness = await run(conversation, [
      { kind: "other", advanceMs: 1_000 },
      { kind: "text", text: "лет пять", advanceMs: 2_000 },
    ]).catch((error: unknown) => error)

    // the script ran out at the second question, which is expected here
    expect(harness).toBeInstanceOf(Error)
    expect(state.saves).toEqual([{ step: 0, text: "лет пять" }])
  })

  test("an update that itself arrives after the window is not even nudged", async () => {
    const { applications, state } = fakeApplications(draftAt(0))
    const { conversation } = build(applications)

    const harness = await run(conversation, [{ kind: "other", advanceMs: IDLE_MS + 1 }])

    expect(harness.sent.some((screen) => screen.text.includes(tr("ru").t("app.answerNonText")))).toBe(false)
    expect(lastText(harness.sent)).toContain(tr("ru").t("app.idleTitle"))
    expect(state.saves).toEqual([])
  })

  test("the summary screen times out too, so an abandoned form never submits itself", async () => {
    const { applications, state } = fakeApplications(draftAt(2, ["answer-1", "answer-2"]))
    const { conversation, opened } = build(applications)

    const harness = await run(conversation, [
      { kind: "callback", data: CALLBACK.appSubmit, advanceMs: IDLE_MS + 1 },
    ])

    expect(lastText(harness.sent)).toContain(tr("ru").t("app.idleTitle"))
    expect(state.submitted).toBe(0)
    expect(opened).toEqual([])
    // the stale button press is not acknowledged: it did nothing
    expect(harness.answered).toEqual([])
  })

  test("a timed-out flow leaves the draft resumable at the same step", async () => {
    const { applications, state } = fakeApplications(draftAt(1, ["первый ответ"]))
    const { conversation } = build(applications)

    // first attempt: the user walks away at question two
    await run(conversation, [{ kind: "text", text: "забыл ответить", advanceMs: IDLE_MS + 60_000 }])
    expect(state.draft?.step).toBe(1)
    expect(state.draft?.answers).toEqual(["первый ответ"])

    // second attempt: /application again, the flow picks up at the same step
    await expect(
      run(conversation, [{ kind: "text", text: "второй ответ" }]),
    ).rejects.toThrow("conversation abandoned")

    expect(state.saves).toEqual([{ step: 1, text: "второй ответ" }])
    expect(state.draft?.step).toBe(2)
  })

  test("cancel still answers the button and drops the draft", async () => {
    const { applications, state } = fakeApplications(draftAt(0))
    const { conversation } = build(applications)

    const harness = await run(conversation, [{ kind: "callback", data: CALLBACK.appCancel }])

    expect(harness.answered).toEqual([CALLBACK.appCancel])
    expect(state.cancelled).toBe(1)
    expect(lastText(harness.sent)).toContain(tr("ru").t("app.cancelledTitle"))
  })

  test("submitting after a short think opens the vote exactly once", async () => {
    const { applications, state } = fakeApplications(draftAt(2, ["answer-1", "answer-2"]))
    const { conversation, opened } = build(applications)

    const harness = await run(conversation, [
      { kind: "callback", data: CALLBACK.appSubmit, advanceMs: 5 * 60_000 },
    ])

    expect(harness.answered).toEqual([CALLBACK.appSubmit])
    expect(state.submitted).toBe(1)
    expect(opened).toEqual(["u1"])
    expect(lastText(harness.sent)).toContain(tr("ru").t("app.submittedTitle"))
  })
})
