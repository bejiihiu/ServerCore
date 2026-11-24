import type { Api, Bot } from "grammy"
import type { BotContext } from "./context.js"
import type {
  ChatMembership,
  InlineButton,
  InlineKeyboard,
  InviteLinkRequest,
  TelegramApi,
  VotingMessageInfo,
} from "../services/ports.js"

/** ports.InlineKeyboard -> telegram inline_keyboard json */
export function toGrammYMarkup(keyboard?: InlineKeyboard):
  | { inline_keyboard: ReturnType<typeof toGrammYButton>[][] }
  | undefined {
  if (!keyboard) return undefined
  return {
    inline_keyboard: keyboard.map((row) =>
      row.map((button) => toGrammYButton(button)),
    ),
  }
}

/** bot api 9.4 button style values, matching grammY's InlineKeyboardButton.style */
type ButtonStyle = "success" | "primary" | "danger"

function toGrammYButton(button: InlineButton): {
  text: string
  callback_data: string
  style?: ButtonStyle
  icon_custom_emoji_id?: string
} {
  const out: { text: string; callback_data: string; style?: ButtonStyle; icon_custom_emoji_id?: string } = {
    text: button.text,
    callback_data: button.data,
  }
  if (button.style) out.style = button.style
  if (button.iconCustomEmojiId) out.icon_custom_emoji_id = button.iconCustomEmojiId
  return out
}

/** deep-clones a keyboard without the icon fields — the premium fallback path */
function stripIcons(keyboard: InlineKeyboard): InlineKeyboard {
  return keyboard.map((row) =>
    row.map((button) => {
      const plain: InlineButton = { text: button.text, data: button.data, style: button.style }
      return plain
    }),
  )
}

function hasIcons(keyboard: InlineKeyboard): boolean {
  return keyboard.some((row) => row.some((b) => b.iconCustomEmojiId !== undefined))
}

function toMembership(status: string): ChatMembership {
  switch (status) {
    case "member":
      return "member"
    case "administrator":
      return "administrator"
    case "creator":
      return "creator"
    case "restricted":
      return "restricted"
    case "kicked":
      return "kicked"
    default:
      return "left"
  }
}

/**
 * grammY-backed implementation of the admission TelegramApi port. every
 * method swallows api errors and degrades to null/false — telegram is a
 * side channel, a failed notification must never break a db transition.
 *
 * button icons require premium on the bot owner: when a request with icons
 * fails, it is retried once without them (graceful fallback).
 */
export class GrammYTelegramApi implements TelegramApi {
  private readonly api: Api
  private readonly groupChatId: number | null
  private readonly adminChatId: number | null

  constructor(
    bot: Bot<BotContext>,
    opts: {
      groupChatId: number | null
      adminChatId: number | null
    },
  ) {
    this.api = bot.api
    this.groupChatId = opts.groupChatId
    this.adminChatId = opts.adminChatId
  }

  async sendUserMessage(
    telegramId: string,
    text: string,
    keyboard?: InlineKeyboard,
    opts?: { effectId?: string },
  ): Promise<VotingMessageInfo | null> {
    try {
      const message = await this.api.sendMessage(Number(telegramId), text, {
        reply_markup: toGrammYMarkup(keyboard),
        ...(opts?.effectId ? { message_effect_id: opts.effectId } : {}),
      })
      return {
        applicationId: "",
        telegramId,
        chatId: message.chat.id,
        messageId: message.message_id,
        kind: "group",
      }
    } catch {
      // user blocked the bot or the id is stale — nothing to deliver to
      return null
    }
  }

  async sendGroupMessage(text: string, keyboard?: InlineKeyboard): Promise<VotingMessageInfo | null> {
    if (this.groupChatId === null) return null
    try {
      const message = await this.api.sendMessage(this.groupChatId, text, {
        reply_markup: toGrammYMarkup(keyboard),
      })
      return {
        applicationId: "",
        telegramId: "",
        chatId: message.chat.id,
        messageId: message.message_id,
        kind: "group",
      }
    } catch {
      // premium may be gone — retry once without the icon decorations
      if (keyboard && hasIcons(keyboard)) {
        try {
          const message = await this.api.sendMessage(this.groupChatId, text, {
            reply_markup: toGrammYMarkup(stripIcons(keyboard)),
          })
          return {
            applicationId: "",
            telegramId: "",
            chatId: message.chat.id,
            messageId: message.message_id,
            kind: "group",
          }
        } catch {
          return null
        }
      }
      return null
    }
  }

  async editMessageReplyMarkup(
    chatId: number,
    messageId: number,
    keyboard: InlineKeyboard,
  ): Promise<boolean> {
    try {
      await this.api.editMessageReplyMarkup(chatId, messageId, {
        reply_markup: toGrammYMarkup(keyboard),
      })
      return true
    } catch {
      if (hasIcons(keyboard)) {
        try {
          await this.api.editMessageReplyMarkup(chatId, messageId, {
            reply_markup: toGrammYMarkup(stripIcons(keyboard)),
          })
          return true
        } catch {
          return false
        }
      }
      return false
    }
  }

  async editMessageText(
    chatId: number,
    messageId: number,
    text: string,
    keyboard?: InlineKeyboard,
  ): Promise<boolean> {
    try {
      await this.api.editMessageText(chatId, messageId, text, {
        reply_markup: toGrammYMarkup(keyboard),
      })
      return true
    } catch {
      if (keyboard && hasIcons(keyboard)) {
        try {
          await this.api.editMessageText(chatId, messageId, text, {
            reply_markup: toGrammYMarkup(stripIcons(keyboard)),
          })
          return true
        } catch {
          return false
        }
      }
      return false
    }
  }

  async sendEphemeralReply(
    callbackQueryId: string,
    chatId: number,
    text: string,
  ): Promise<number | null> {
    try {
      // bot api 10.2: ephemeral reply to a callback query, visible only to
      // the user who pressed the button; answerCallbackQuery still required
      const message = await this.api.sendMessage(chatId, text, {
        ephemeral_message_parameters: { callback_query_id: callbackQueryId },
      })
      return message.message_id
    } catch {
      return null
    }
  }

  async editEphemeralMessageText(
    chatId: number,
    receiverUserId: number,
    ephemeralMessageId: number,
    text: string,
  ): Promise<boolean> {
    try {
      // grammY generates positional args: chat_id, receiver_user_id,
      // ephemeral_message_id, text
      await this.api.editEphemeralMessageText(chatId, receiverUserId, ephemeralMessageId, text)
      return true
    } catch {
      return false
    }
  }

  async deleteEphemeralMessage(
    chatId: number,
    receiverUserId: number,
    ephemeralMessageId: number,
  ): Promise<boolean> {
    try {
      await this.api.deleteEphemeralMessage(chatId, receiverUserId, ephemeralMessageId)
      return true
    } catch {
      return false
    }
  }

  async createChatInviteLink(
    chatId: number,
    opts: InviteLinkRequest,
  ): Promise<{ url: string; name: string }> {
    const link = await this.api.createChatInviteLink(chatId, {
      name: opts.name,
      member_limit: opts.memberLimit,
      expire_date: opts.expireDate,
    })
    return { url: link.invite_link, name: link.name ?? opts.name }
  }

  async revokeChatInviteLink(chatId: number, link: string): Promise<void> {
    await this.api.revokeChatInviteLink(chatId, link)
  }

  async banChatMember(chatId: number, userId: string): Promise<void> {
    await this.api.banChatMember(chatId, Number(userId))
  }

  async unbanChatMember(chatId: number, userId: string): Promise<void> {
    await this.api.unbanChatMember(chatId, Number(userId), { only_if_banned: true })
  }

  async getChatMember(chatId: number, userId: string): Promise<ChatMembership> {
    const member = await this.api.getChatMember(chatId, Number(userId))
    return toMembership(member.status)
  }

  async sendAdminNotification(text: string): Promise<void> {
    if (this.adminChatId === null) return
    try {
      await this.api.sendMessage(this.adminChatId, text)
    } catch {
      // admin chat unreachable — notifications are best-effort by design
    }
  }
}
