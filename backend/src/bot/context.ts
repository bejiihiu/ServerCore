import type { Context } from "grammy"
import type { ConversationFlavor } from "@grammyjs/conversations"
import type { AccountService } from "../modules/account/account-service.js"
import type { AccountDeletionService } from "../modules/account/deletion.js"
import type { Language } from "./i18n.js"

/** bot context with the conversations flavor installed */
export type BotContext = ConversationFlavor<Context>

/** everything bot routes need; built once in app.ts */
export type BotDeps = {
  accounts: AccountService
  deletion: AccountDeletionService
  defaultLanguage: Language
}
