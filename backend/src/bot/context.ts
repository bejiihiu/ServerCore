import type { Context } from "grammy"
import type { ConversationFlavor } from "@grammyjs/conversations"
import type { AccountService } from "../modules/account/account-service.js"
import type { AccountDeletionService } from "../modules/account/deletion.js"
import type { CollectionStatsSource } from "../modules/retention/collection-stats.js"
import type { StatsService } from "../services/runtime-stats.js"
import type { Locale } from "../services/i18n.js"

/** bot context with the conversations flavor installed */
export type BotContext = ConversationFlavor<Context>

/** everything bot routes need; built once in app.ts */
export type BotDeps = {
  accounts: AccountService
  deletion: AccountDeletionService
  /** default locale for users without a stored preference (from OPS_LOCALE) */
  defaultLanguage: Locale
  /** telegram user ids allowed to call admin commands */
  adminTelegramIds: readonly number[]
  /** live ops snapshot for /stats; null when the gateway isn't wired yet */
  stats: StatsService | null
  /** real collection sizes for /stats; null while mongo is down or absent */
  collectionStats: CollectionStatsSource | null
}
