import type { Api } from "grammy"
import type { MessageSender } from "./notification-service.js"

/**
 * grammy adapter for the notification fan-out. sendMessage failures surface
 * as rejected promises; the NotificationService handles retry/summary.
 */
export class GrammySender implements MessageSender {
  private readonly api: Api

  constructor(api: Api) {
    this.api = api
  }

  async send(chatId: string, text: string): Promise<void> {
    await this.api.sendMessage(chatId, text)
  }
}
