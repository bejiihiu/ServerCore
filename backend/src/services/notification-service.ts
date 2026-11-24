import type { Logger } from "../logging/logger.js"

/** how many messages telegram allows per second before throttling kicks in */
export const TELEGRAM_MESSAGES_PER_SECOND = 30

export type BroadcastResult = {
  /** unique recipients the broadcast attempted */
  recipients: number
  sent: number
  failed: number
}

/** anything that can push a text message to a chat: grammy api in production */
export interface MessageSender {
  send(chatId: string, text: string): Promise<void>
}

/** where the list of recipients comes from: the telegram_links store in prod */
export interface RecipientSource {
  listRecipients(): Promise<string[]>
}

export type RateLimitClock = {
  now(): number
  sleep(ms: number): Promise<void>
}

/** real clock: monotonic enough for 1s windows */
export const systemClock: RateLimitClock = {
  now: () => Date.now(),
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
}

export type NotificationOptions = {
  /** max messages per second, telegram hard-limits around 30 */
  ratePerSecond?: number
  clock?: RateLimitClock
}

/**
 * fan-out of one text to many chats, rate-limited to ~30 msg/s (telegram
 * global bot limit). error-resilient: a failing recipient never aborts the
 * batch, failures get exactly one retry pass at the end, and a summary is
 * always logged.
 */
export class NotificationService {
  private sender: MessageSender | null
  private readonly recipients: RecipientSource
  private readonly logger: Logger
  private readonly ratePerSecond: number
  private readonly clock: RateLimitClock

  constructor(deps: {
    /** null = bot not configured, broadcasts degrade to a debug log line */
    sender: MessageSender | null
    recipients: RecipientSource
    logger: Logger
    options?: NotificationOptions
  }) {
    this.sender = deps.sender
    this.recipients = deps.recipients
    this.logger = deps.logger
    this.ratePerSecond = deps.options?.ratePerSecond ?? TELEGRAM_MESSAGES_PER_SECOND
    this.clock = deps.options?.clock ?? systemClock
  }

  /** swaps the sender after construction (bot is built late in app.ts) */
  attachSender(sender: MessageSender | null): void {
    this.sender = sender
  }

  /**
   * sends the text to every linked user plus the admin chat ids. returns the
   * aggregate result; admin sends count towards the same rate window.
   */
  async broadcast(text: string, adminChatIds: readonly string[] = []): Promise<BroadcastResult> {
    const users = await this.recipients.listRecipients()
    const unique = [...new Set([...users, ...adminChatIds])]
    if (unique.length === 0) return { recipients: 0, sent: 0, failed: 0 }

    if (this.sender === null) {
      this.logger.debug({ wouldReceive: unique.length }, "no sender configured, broadcast skipped")
      return { recipients: unique.length, sent: 0, failed: 0 }
    }

    const result = await this.sendToAll(unique, text)
    // one retry pass for the stragglers - transient 5xx/network hiccups only
    if (result.failed.length > 0) {
      const retry = await this.sendToAll(result.failed, text)
      result.sent += retry.sent
      result.failed = retry.failed
    }

    this.logger.info(
      { recipients: result.recipients, sent: result.sent, failed: result.failed.length },
      "broadcast finished",
    )
    return { recipients: result.recipients, sent: result.sent, failed: result.failed.length }
  }

  /**
   * sends the text to the admin chat ids only, no user fan-out. same
   * rate-limited loop, same resilience as broadcast.
   */
  async notifyAdmins(text: string, adminChatIds: readonly string[]): Promise<void> {
    if (adminChatIds.length === 0) return
    if (this.sender === null) {
      this.logger.debug({ wouldReceive: adminChatIds.length }, "no sender configured, admin notify skipped")
      return
    }
    const result = await this.sendToAll(adminChatIds, text)
    if (result.failed.length > 0) {
      const retry = await this.sendToAll(result.failed, text)
      result.sent += retry.sent
      result.failed = retry.failed
    }
    if (result.failed.length > 0) {
      this.logger.warn({ failed: result.failed.length }, "admin notify finished with failures")
    }
  }

  /** rate-limited loop; returns per-chat outcome split into sent/failed */
  private async sendToAll(chatIds: readonly string[], text: string): Promise<{
    recipients: number
    sent: number
    failed: string[]
  }> {
    const sender = this.sender
    if (!sender) return { recipients: chatIds.length, sent: 0, failed: [...chatIds] }

    let sent = 0
    const failed: string[] = []
    let windowStart = this.clock.now()
    let sentInWindow = 0

    for (const chatId of chatIds) {
      if (sentInWindow >= this.ratePerSecond) {
        const elapsed = this.clock.now() - windowStart
        if (elapsed < 1000) await this.clock.sleep(1000 - elapsed)
        windowStart = this.clock.now()
        sentInWindow = 0
      }

      try {
        await sender.send(chatId, text)
        sent += 1
      } catch (error) {
        this.logger.warn({ err: error, chatId }, "failed to deliver notification")
        failed.push(chatId)
      }
      sentInWindow += 1
    }

    return { recipients: chatIds.length, sent, failed }
  }
}
