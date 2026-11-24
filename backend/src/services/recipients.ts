import { TelegramLinkModel } from "../database/models/telegram-link.js"
import type { RecipientSource } from "./notification-service.js"

/**
 * mongo-backed recipient list: every telegram account currently LINKED to a
 * minecraft account gets ops notifications in dm (product decision: all
 * events, dm + admin chat copy).
 */
export class LinkedTelegramRecipients implements RecipientSource {
  async listRecipients(): Promise<string[]> {
    const ids = await TelegramLinkModel.find({ status: "LINKED" })
      .select("telegramId")
      .lean()
      .exec()
    // telegram ids are stored as strings; filter out anything malformed so a
    // dirty record cannot kill the whole broadcast
    return ids
      .map((doc) => doc.telegramId)
      .filter((id): id is string => typeof id === "string" && id.length > 0)
  }
}

/** in-memory recipient source for tests and dry-run environments */
export class StaticRecipients implements RecipientSource {
  constructor(private readonly ids: readonly string[]) {}

  async listRecipients(): Promise<string[]> {
    return [...this.ids]
  }
}
