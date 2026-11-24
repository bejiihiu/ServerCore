import type { Context } from "grammy"
import type { ConversationFlavor } from "@grammyjs/conversations"
import type { AccountService } from "../modules/account/account-service.js"
import type { AccountDeletionService } from "../modules/account/deletion.js"
import type { CollectionStatsSource } from "../modules/retention/collection-stats.js"
import type { Language } from "./i18n.js"

/** bot context with the conversations flavor installed */
export type BotContext = ConversationFlavor<Context>

/** everything bot routes need; built once in app.ts */
export type BotDeps = {
  accounts: AccountService
  deletion: AccountDeletionService
  defaultLanguage: Language
  /** chat ids allowed to call admin commands */
  adminChatIds: readonly string[]
  /** real collection sizes for /stats; null while mongo is down or absent */
  collectionStats: CollectionStatsSource | null
}
