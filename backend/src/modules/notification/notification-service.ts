import type { Logger } from "../../logging/logger.js"

/**
 * transport-level delivery port. the telegram port is the real transport;
 * tests swap their own implementation of the same contract.
 */
export interface NotificationPort {
  /** delivers a plain text message to a telegram chat; rejects on failure */
  sendToTelegram(chatId: string, text: string): Promise<void>
}

/** real transport on the bot api (grammY Api forwards sendMessage) */
export class TelegramNotificationPort implements NotificationPort {
  private readonly api: { sendMessage(chatId: string | number, text: string): Promise<unknown> }

  constructor(api: { sendMessage(chatId: string | number, text: string): Promise<unknown> }) {
    this.api = api
  }

  async sendToTelegram(chatId: string, text: string): Promise<void> {
    await this.api.sendMessage(chatId, text)
  }
}

/**
 * when the bot is disabled (no token configured) nothing can be delivered, so
 * messages land in the log instead. account flows keep working — losing a
 * notification must never break the operation that caused it.
 */
export class LogOnlyNotificationPort implements NotificationPort {
  private readonly logger: Logger

  constructor(logger: Logger) {
    this.logger = logger
  }

  async sendToTelegram(chatId: string, text: string): Promise<void> {
    this.logger.warn({ chatId, text }, "notification not delivered: telegram bot disabled")
  }
}

/** what account modules consume: personal + admin fan-out */
export interface NotificationFanout {
  notifyUser(chatId: string, text: string): Promise<void>
  notifyAdmins(text: string): Promise<void>
}

/**
 * notification fan-out: personal messages to users plus a duplicate of key
 * events into the configurable admin chat(s). admin copy is best-effort —
 * one dead admin chat must not block the others or the user notification.
 */
export class NotificationService implements NotificationFanout {
  private readonly port: NotificationPort
  private readonly adminChatIds: readonly string[]
  private readonly logger: Logger

  constructor(port: NotificationPort, adminChatIds: readonly string[], logger: Logger) {
    this.port = port
    this.adminChatIds = adminChatIds
    this.logger = logger
  }

  /** personal delivery; swallows failures (log only) — callers proceed regardless */
  async notifyUser(chatId: string, text: string): Promise<void> {
    if (chatId === "") return
    try {
      await this.port.sendToTelegram(chatId, text)
    } catch (err: unknown) {
      this.logger.warn({ err, chatId }, "user notification delivery failed")
    }
  }

  /** same text to every configured admin chat; per-chat failures are isolated */
  async notifyAdmins(text: string): Promise<void> {
    for (const chatId of this.adminChatIds) {
      try {
        await this.port.sendToTelegram(chatId, text)
      } catch (err: unknown) {
        this.logger.warn({ err, chatId }, "admin notification delivery failed")
      }
    }
  }
}
