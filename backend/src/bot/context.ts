import type { Context } from "grammy"
import type { ConversationFlavor } from "@grammyjs/conversations"

/** bot-wide context: conversations plugin flavor is all we need for now */
export type BotContext = Context & ConversationFlavor<Context>
