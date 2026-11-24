import { describe, expect, test } from "bun:test"
import {
  isPremiumRequiredError,
  renderPlainText,
  withoutCustomEmoji,
  withoutCustomEmojiMarkup,
  withoutCustomEmojiText,
} from "../src/bot/ui/sending.js"
import type { ScreenMessage } from "../src/bot/ui/screens/types.js"

const premiumError = { error_code: 400, description: "Bad Request: PREMIUM_ACCOUNT_REQUIRED" }
const otherError = { error_code: 400, description: "Bad Request: chat not found" }

describe("premium gate detection", () => {
  test("recognizes the 400 PREMIUM_ACCOUNT_REQUIRED shape", () => {
    expect(isPremiumRequiredError(premiumError)).toBe(true)
  })

  test("other errors are not premium-gated", () => {
    expect(isPremiumRequiredError(otherError)).toBe(false)
    expect(isPremiumRequiredError(new Error("boom"))).toBe(false)
    expect(isPremiumRequiredError(null)).toBe(false)
    expect(isPremiumRequiredError(undefined)).toBe(false)
    expect(isPremiumRequiredError({ error_code: 429, description: "PREMIUM_ACCOUNT_REQUIRED" })).toBe(false)
  })
})

describe("custom emoji degradation of a whole screen", () => {
  const screen: ScreenMessage = {
    text: '<tg-emoji emoji-id="1">🟢</tg-emoji> <b>Статус</b>',
    markup: {
      inline_keyboard: [
        [
          { text: "Рестарт", callback_data: "srv:r", style: "danger", icon_custom_emoji_id: "42" },
          { text: "Ждём", disabled: {}, icon_custom_emoji_id: "43" },
        ],
      ],
    },
  }

  test("text keeps the fallback char", () => {
    expect(withoutCustomEmojiText(screen.text)).toBe("🟢 <b>Статус</b>")
  })

  test("markup loses icon ids, keeps styles and disabled state", () => {
    const markup = withoutCustomEmojiMarkup(screen.markup as NonNullable<ScreenMessage["markup"]>)
    expect(markup.inline_keyboard[0]?.[0]).toEqual({ text: "Рестарт", callback_data: "srv:r", style: "danger" })
    expect(markup.inline_keyboard[0]?.[1]).toEqual({ text: "Ждём", disabled: {} })
  })

  test("markup without icons is returned as-is (same rows content)", () => {
    const plain = { inline_keyboard: [[{ text: "a", callback_data: "a:b" }]] }
    const result = withoutCustomEmojiMarkup(plain)
    expect(result).toEqual(plain)
  })

  test("whole screen degrades", () => {
    const degraded = withoutCustomEmoji(screen)
    expect(degraded.text).toBe("🟢 <b>Статус</b>")
    expect(JSON.stringify(degraded.markup)).not.toContain("icon_custom_emoji_id")
  })

  test("screen without markup survives degradation", () => {
    expect(withoutCustomEmoji({ text: "<tg-emoji emoji-id=\"1\">🟡</tg-emoji> ждём" })).toEqual({ text: "🟡 ждём" })
  })
})

describe("plain text bridge (broadcast path)", () => {
  test("strips all markup, keeps readable text", () => {
    const screen: ScreenMessage = {
      text: '<tg-emoji emoji-id="1">🟢</tg-emoji> <b>Статус</b>\n\n<b>Сервер</b> онлайн\n<blockquote>заметка</blockquote>',
    }
    expect(renderPlainText(screen)).toBe("🟢 Статус\n\nСервер онлайн\nзаметка")
  })

  test("expandable blockquotes and spoilers flatten", () => {
    const screen: ScreenMessage = {
      text: "заголовок\n<blockquote expandable>длинный блок\nсо второй строкой</blockquote>\n<tg-spoiler>секрет</tg-spoiler>",
    }
    expect(renderPlainText(screen)).toBe("заголовок\nдлинный блок\nсо второй строкой\nсекрет")
  })
})
