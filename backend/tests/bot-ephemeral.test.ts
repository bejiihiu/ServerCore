import { describe, expect, test } from "bun:test"
import type { Api, Bot } from "grammy"
import {
  deleteEphemeralScreen,
  editEphemeralScreen,
  sendEphemeralScreen,
} from "../src/bot/ui/sending.js"
import type { ScreenMessage } from "../src/bot/ui/screens/types.js"
import { GrammYTelegramApi } from "../src/bot/telegram-api.js"
import type { BotContext } from "../src/bot/context.js"

const premiumError = { error_code: 400, description: "Bad Request: PREMIUM_ACCOUNT_REQUIRED" }

const screen: ScreenMessage = {
  text: '<tg-emoji emoji-id="1">🟢</tg-emoji> <b>Голос принят</b>',
  markup: { inline_keyboard: [[{ text: "Ок", callback_data: "vote:1:ACCEPT", icon_custom_emoji_id: "9" }]] },
}

type Call = { method: string; args: unknown[] }

/** records positional args, so the bot api 10.2 signatures are pinned */
function fakeApi(behaviour: { failFirst?: unknown } = {}) {
  const calls: Call[] = []
  let first = true
  const api = {
    async sendMessage(...args: unknown[]) {
      calls.push({ method: "sendMessage", args })
      if (first && behaviour.failFirst !== undefined) {
        first = false
        throw behaviour.failFirst
      }
      first = false
      return { chat: { id: 1 }, message_id: 41 }
    },
    async editEphemeralMessageText(...args: unknown[]) {
      calls.push({ method: "editEphemeralMessageText", args })
      return true
    },
    async deleteEphemeralMessage(...args: unknown[]) {
      calls.push({ method: "deleteEphemeralMessage", args })
      return true
    },
  }
  return { api: api as unknown as Api, calls }
}

describe("sendEphemeralScreen", () => {
  test("a callback reply carries the callback query id and the markup", async () => {
    const fake = fakeApi()

    const message = await sendEphemeralScreen(fake.api, {
      chatId: 100,
      screen,
      callbackQueryId: "cb-1",
      replaceCallbackMessage: true,
    })

    expect(message.message_id).toBe(41)
    const [chatId, text, other] = fake.calls[0]!.args as [number, string, Record<string, unknown>]
    expect(chatId).toBe(100)
    expect(text).toBe(screen.text)
    expect(other.parse_mode).toBe("HTML")
    expect(other.ephemeral_message_parameters).toEqual({
      callback_query_id: "cb-1",
      replace_callback_query_message: true,
    })
    expect(other.reply_markup).toEqual(screen.markup)
  })

  test("a direct ephemeral message addresses its receiver", async () => {
    const fake = fakeApi()

    await sendEphemeralScreen(fake.api, { chatId: 100, screen, receiverUserId: 55 })

    const other = fake.calls[0]!.args[2] as Record<string, unknown>
    expect(other.ephemeral_message_parameters).toEqual({ receiver_user_id: 55 })
  })

  test("the premium gate degrades the screen and keeps the ephemeral params", async () => {
    const fake = fakeApi({ failFirst: premiumError })

    await sendEphemeralScreen(fake.api, { chatId: 100, screen, callbackQueryId: "cb-1" })

    expect(fake.calls).toHaveLength(2)
    const retryText = fake.calls[1]!.args[1] as string
    const retryOther = fake.calls[1]!.args[2] as Record<string, unknown>
    expect(retryText).not.toContain("tg-emoji")
    expect(JSON.stringify(retryOther)).not.toContain("icon_custom_emoji_id")
    expect(retryOther.ephemeral_message_parameters).toEqual({ callback_query_id: "cb-1" })
  })

  test("an unrelated failure is not swallowed", async () => {
    const fake = fakeApi({ failFirst: new Error("chat not found") })

    await expect(
      sendEphemeralScreen(fake.api, { chatId: 100, screen, callbackQueryId: "cb-1" }),
    ).rejects.toThrow("chat not found")
  })
})

describe("editEphemeralScreen", () => {
  test("the positional api order is chat, receiver, message, text, options", async () => {
    const fake = fakeApi()

    const result = await editEphemeralScreen(fake.api, {
      chatId: 100,
      receiverUserId: 55,
      ephemeralMessageId: 41,
      screen,
    })

    expect(result).toBe(true)
    const args = fake.calls[0]!.args
    expect(args[0]).toBe(100)
    expect(args[1]).toBe(55)
    expect(args[2]).toBe(41)
    expect(args[3]).toBe(screen.text)
    expect((args[4] as Record<string, unknown>).reply_markup).toEqual(screen.markup)
  })

  test("the premium gate retries with fallback rendering", async () => {
    const failOnce = {
      async editEphemeralMessageText(...args: unknown[]) {
        calls.push(args)
        if (calls.length === 1) throw premiumError
        return true
      },
      async deleteEphemeralMessage() {
        return true
      },
    }
    const calls: unknown[][] = []

    await editEphemeralScreen(failOnce as unknown as Api, {
      chatId: 100,
      receiverUserId: 55,
      ephemeralMessageId: 41,
      screen,
    })

    expect(calls).toHaveLength(2)
    expect(calls[1]![3]).not.toContain("tg-emoji")
    expect(JSON.stringify(calls[1]![4])).not.toContain("icon_custom_emoji_id")
  })

  test("an unrelated failure is not swallowed", async () => {
    const api = {
      async editEphemeralMessageText() {
        throw new Error("message is not ephemeral")
      },
    }

    await expect(
      editEphemeralScreen(api as unknown as Api, {
        chatId: 100,
        receiverUserId: 55,
        ephemeralMessageId: 41,
        screen,
      }),
    ).rejects.toThrow("message is not ephemeral")
  })
})

describe("deleteEphemeralScreen", () => {
  test("addresses the exact receiver and message", async () => {
    const fake = fakeApi()

    const result = await deleteEphemeralScreen(fake.api, {
      chatId: 100,
      receiverUserId: 55,
      ephemeralMessageId: 41,
    })

    expect(result).toBe(true)
    expect(fake.calls[0]).toEqual({ method: "deleteEphemeralMessage", args: [100, 55, 41] })
  })
})

describe("GrammYTelegramApi ephemeral port", () => {
  function adapter(api: unknown) {
    return new GrammYTelegramApi({ api } as unknown as Bot<BotContext>, { groupChatId: null })
  }

  test("an ephemeral reply returns the message id and passes the callback id", async () => {
    const calls: unknown[][] = []
    const telegram = adapter({
      async sendMessage(...args: unknown[]) {
        calls.push(args)
        return { chat: { id: 1 }, message_id: 77 }
      },
    })

    const id = await telegram.sendEphemeralReply("cb-1", 100, "только тебе")

    expect(id).toBe(77)
    const other = calls[0]![2] as Record<string, unknown>
    expect(other.ephemeral_message_parameters).toEqual({ callback_query_id: "cb-1" })
  })

  test("a failed ephemeral delivery is null, never a throw", async () => {
    const telegram = adapter({
      async sendMessage() {
        throw new Error("ephemeral not supported")
      },
    })

    expect(await telegram.sendEphemeralReply("cb-1", 100, "text")).toBeNull()
  })

  test("the ephemeral edit uses the positional signature", async () => {
    const calls: unknown[][] = []
    const telegram = adapter({
      async editEphemeralMessageText(...args: unknown[]) {
        calls.push(args)
      },
    })

    expect(await telegram.editEphemeralMessageText(100, 55, 41, "new text")).toBe(true)
    expect(calls[0]).toEqual([100, 55, 41, "new text"])
  })

  test("a failed ephemeral edit degrades to false", async () => {
    const telegram = adapter({
      async editEphemeralMessageText() {
        throw new Error("gone")
      },
    })

    expect(await telegram.editEphemeralMessageText(100, 55, 41, "new text")).toBe(false)
  })

  test("ephemeral deletion reports success and failure honestly", async () => {
    const ok = adapter({
      async deleteEphemeralMessage() {
        return true
      },
    })
    const failing = adapter({
      async deleteEphemeralMessage() {
        throw new Error("already gone")
      },
    })

    expect(await ok.deleteEphemeralMessage(100, 55, 41)).toBe(true)
    expect(await failing.deleteEphemeralMessage(100, 55, 41)).toBe(false)
  })
})
