import { describe, expect, test } from "bun:test"
import {
  classifyQuestionInput,
  classifySummaryInput,
} from "../src/bot/conversations/application-questionnaire.js"

describe("questionnaire input classification", () => {
  test("text messages become answers", () => {
    expect(classifyQuestionInput({ message: { text: "лет пять" } })).toEqual({
      kind: "ANSWER",
      text: "лет пять",
    })
  })

  test("the cancel button wins over any text", () => {
    expect(classifyQuestionInput({ callbackQuery: { data: "app:cancel" } })).toEqual({ kind: "CANCEL_BUTTON" })
  })

  test("/cancel cancels without being stored as an answer", () => {
    expect(classifyQuestionInput({ message: { text: "/cancel" } })).toEqual({ kind: "CANCEL_COMMAND" })
    expect(classifyQuestionInput({ message: { text: " /cancel " } })).toEqual({ kind: "CANCEL_COMMAND" })
  })

  test("other commands are reported back, not stored as answers", () => {
    expect(classifyQuestionInput({ message: { text: "/status" } })).toEqual({ kind: "OTHER_COMMAND" })
    expect(classifyQuestionInput({ message: { text: "/application" } })).toEqual({ kind: "OTHER_COMMAND" })
  })

  test("non-text updates are dropped (wait keeps waiting)", () => {
    expect(classifyQuestionInput({ message: {} })).toBeNull()
    expect(classifyQuestionInput({})).toBeNull()
    expect(classifyQuestionInput({ callbackQuery: { data: "app:submit" } })).toBeNull()
  })
})

describe("summary input classification", () => {
  test("submit and cancel buttons are recognized", () => {
    expect(classifySummaryInput({ callbackQuery: { data: "app:submit" } })).toEqual({ kind: "SUBMIT_BUTTON" })
    expect(classifySummaryInput({ callbackQuery: { data: "app:cancel" } })).toEqual({ kind: "CANCEL_BUTTON" })
  })

  test("/cancel cancels from the summary too", () => {
    expect(classifySummaryInput({ message: { text: "/cancel" } })).toEqual({ kind: "CANCEL_COMMAND" })
  })

  test("free text at the summary stage is a nudge case, not a submit", () => {
    expect(classifySummaryInput({ message: { text: "готово" } })).toEqual({ kind: "OTHER_TEXT" })
  })

  test("stale vote buttons at the summary stage are dropped", () => {
    expect(classifySummaryInput({ callbackQuery: { data: "vote:507f1f77bcf86cd799439011:ACCEPT" } })).toBeNull()
  })
})
