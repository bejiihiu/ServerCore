import { describe, expect, test } from "bun:test"
import { GrammYTelegramApi } from "../src/bot/telegram-api.js"
import type { Bot } from "grammy"
import type { BotContext } from "../src/bot/context.js"
import type { InlineKeyboard } from "../src/services/ports.js"

/** fake api: records calls, can fail on effects or on button icons */
function fakeApi(opts: { failOnEffect?: boolean; failOnIcons?: boolean } = {}) {
  const calls: { text: string; other: Record<string, unknown> }[] = []
  const api = {
    async sendMessage(_chatId: number, text: string, other?: Record<string, unknown>) {
      const markup = other?.reply_markup as { inline_keyboard: { icon_custom_emoji_id?: string }[][] } | undefined
      if (opts.failOnEffect && other?.message_effect_id !== undefined) throw new Error("Bad Request: effect rejected")
      if (opts.failOnIcons && markup?.inline_keyboard.flat().some((b) => b.icon_custom_emoji_id !== undefined)) {
        throw new Error("Bad Request: PREMIUM_ACCOUNT_REQUIRED")
      }
      calls.push({ text, other: other ?? {} })
      return { chat: { id: _chatId }, message_id: calls.length }
    },
    async editMessageReplyMarkup() {
      return true
    },
    async editMessageText(_chatId: number, _messageId: number, text: string, other?: Record<string, unknown>) {
      calls.push({ text, other: other ?? {} })
      return true
    },
  }
  return { api, calls }
}

function botWith(api: unknown): Bot<BotContext> {
  return { api } as unknown as Bot<BotContext>
}

const keyboardWithIcon: InlineKeyboard = [
  [{ text: "Да", data: "vote:x:ACCEPT", style: "success", iconCustomEmojiId: "42" }],
]

describe("GrammYTelegramApi effect guard", () => {
  test("a rejected message effect does not lose the notification", async () => {
    const fake = fakeApi({ failOnEffect: true })
    const telegram = new GrammYTelegramApi(botWith(fake.api), { groupChatId: null })
    const sent = await telegram.sendUserMessage("100", "заявка одобрена", undefined, { effectId: "bad-effect" })
    expect(sent).not.toBeNull()
    expect(fake.calls).toHaveLength(1)
    expect(fake.calls[0]?.other.message_effect_id).toBeUndefined()
  })

  test("the effect rides along when accepted", async () => {
    const fake = fakeApi()
    const telegram = new GrammYTelegramApi(botWith(fake.api), { groupChatId: null })
    await telegram.sendUserMessage("100", "ok", undefined, { effectId: "effect-1" })
    expect(fake.calls[0]?.other.message_effect_id).toBe("effect-1")
  })

  test("a hard failure without an effect still returns null", async () => {
    const api = {
      async sendMessage(_chatId: number, _text: string, _other?: Record<string, unknown>) {
        throw new Error("blocked bot")
      },
    }
    const telegram = new GrammYTelegramApi(botWith(api), { groupChatId: null })
    expect(await telegram.sendUserMessage("100", "hi")).toBeNull()
  })
})

describe("GrammYTelegramApi premium icon fallback", () => {
  test("group message retries without button icons", async () => {
    const fake = fakeApi({ failOnIcons: true })
    const telegram = new GrammYTelegramApi(botWith(fake.api), { groupChatId: -100_500 })
    const sent = await telegram.sendGroupMessage("announcement", keyboardWithIcon)
    expect(sent).not.toBeNull()
    expect(fake.calls).toHaveLength(1)
    const markup = fake.calls[0]?.other.reply_markup as { inline_keyboard: Record<string, unknown>[][] }
    expect(JSON.stringify(markup)).not.toContain("icon_custom_emoji_id")
    expect(markup.inline_keyboard[0]?.[0]?.text).toBe("Да")
  })

  test("icons survive when premium is there", async () => {
    const fake = fakeApi()
    const telegram = new GrammYTelegramApi(botWith(fake.api), { groupChatId: -100_500 })
    await telegram.sendGroupMessage("announcement", keyboardWithIcon)
    const markup = fake.calls[0]?.other.reply_markup as { inline_keyboard: { icon_custom_emoji_id?: string }[][] }
    expect(markup.inline_keyboard[0]?.[0]?.icon_custom_emoji_id).toBe("42")
  })
})
