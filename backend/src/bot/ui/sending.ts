import type { Api } from "grammy"
import type { Message } from "@grammyjs/types/message.js"
import type { ScreenMessage } from "./screens/types.js"
import { composeText, stripCustomEmoji } from "./text/index.js"
import type { InlineButton, InlineKeyboardMarkup } from "./keyboards/index.js"

/**
 * send/edit helpers shared by every screen. the one network concern baked in:
 * custom emoji entities require telegram premium on the bot owner (bot api
 * 9.4). when telegram answers 400 PREMIUM_ACCOUNT_REQUIRED the message is
 * resent once with tg-emoji tags stripped and button icons removed — the user
 * sees unicode fallbacks instead of a dead screen.
 */

export type SendTextArgs = {
  chatId: number | string
  screen: ScreenMessage
  replyToMessageId?: number
  effectId?: string
}

/** true for the known premium-gate rejection */
export function isPremiumRequiredError(err: unknown): boolean {
  const code = (err as { error_code?: number } | null)?.error_code
  const description = (err as { description?: string } | null)?.description
  return code === 400 && typeof description === "string" && description.includes("PREMIUM_ACCOUNT_REQUIRED")
}

/** text with tg-emoji tags replaced by fallback chars */
export function withoutCustomEmojiText(text: string): string {
  return stripCustomEmoji(text)
}

/** markup with icon_custom_emoji_id removed from every button */
export function withoutCustomEmojiMarkup(markup: InlineKeyboardMarkup): InlineKeyboardMarkup {
  const rows = markup.inline_keyboard.map((row) =>
    row.map((button) => {
      if (!("icon_custom_emoji_id" in button)) return button
      const { icon_custom_emoji_id: _icon, ...rest } = button
      return rest as InlineButton
    }),
  )
  return { ...markup, inline_keyboard: rows }
}

/** screen degraded to a custom-emoji-free variant */
export function withoutCustomEmoji(screen: ScreenMessage): ScreenMessage {
  return {
    text: withoutCustomEmojiText(screen.text),
    markup: screen.markup === undefined ? undefined : withoutCustomEmojiMarkup(screen.markup),
  }
}

type SendMessageOther = Parameters<Api["sendMessage"]>[2]

function sendMessageOther(args: SendTextArgs, screen: ScreenMessage): SendMessageOther {
  const other: SendMessageOther = { ...composeText() }
  if (screen.markup !== undefined) other.reply_markup = screen.markup
  if (args.replyToMessageId !== undefined) other.reply_parameters = { message_id: args.replyToMessageId }
  if (args.effectId !== undefined) other.message_effect_id = args.effectId
  return other
}

/** sends a screen, degrading to fallback rendering on the premium gate */
export async function sendScreen(api: Api, args: SendTextArgs): Promise<Message> {
  try {
    return await api.sendMessage(args.chatId, args.screen.text, sendMessageOther(args, args.screen))
  } catch (err) {
    if (!isPremiumRequiredError(err)) throw err
    const fallback = withoutCustomEmoji(args.screen)
    return await api.sendMessage(args.chatId, fallback.text, sendMessageOther(args, fallback))
  }
}

export type EditScreenArgs = {
  chatId: number | string
  messageId: number
  screen: ScreenMessage
}

type EditMessageOther = Parameters<Api["editMessageText"]>[3]

function editMessageOther(screen: ScreenMessage): EditMessageOther {
  const other: EditMessageOther = { ...composeText() }
  if (screen.markup !== undefined) other.reply_markup = screen.markup
  return other
}

/** edits a message into a screen, same premium fallback */
export async function editScreen(api: Api, args: EditScreenArgs): Promise<Message | true> {
  try {
    return await api.editMessageText(args.chatId, args.messageId, args.screen.text, editMessageOther(args.screen))
  } catch (err) {
    if (!isPremiumRequiredError(err)) throw err
    const fallback = withoutCustomEmoji(args.screen)
    return await api.editMessageText(args.chatId, args.messageId, fallback.text, editMessageOther(fallback))
  }
}

/**
 * bridge to the broadcast path: NotificationService sends plain text (no
 * parse mode, no markup, rate-limited). renders a screen as the plain text a
 * broadcast would carry — tags stripped, blockquotes kept as lines, emoji
 * fallbacks in place of tg-emoji tags.
 */
export function renderPlainText(screen: ScreenMessage): string {
  return screen.text
    .replace(/<tg-emoji emoji-id="[^"]*">(.*?)<\/tg-emoji>/gs, "$1")
    .replace(/<blockquote expandable>|<blockquote>/g, "")
    .replace(/<\/blockquote>/g, "")
    .replace(/<tg-spoiler>|<\/tg-spoiler>/g, "")
    .replace(/<[^>]+>/g, "")
}
