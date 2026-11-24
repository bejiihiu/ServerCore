import type { Conversation } from "@grammyjs/conversations"
import type { LinkFailureReason } from "../../modules/account/account-service.js"
import { t, type Locale } from "../../services/i18n.js"
import { CALLBACK, linkingKeyboard } from "../keyboards.js"
import type { BotContext } from "../context.js"
import type { AccountService } from "../../modules/account/account-service.js"

/** the linking conversation needs the account service and nothing else */
export type LinkingConversationDeps = {
  accounts: AccountService
}

const FAILURE_KEYS: Record<LinkFailureReason, "bot.link.fail_token" | "bot.link.fail_frozen" | "bot.link.fail_already_linked" | "bot.link.fail_ownership" | "bot.link.fail_offline" | "bot.link.fail_rate_limited" | "bot.link.fail_not_found"> = {
  TOKEN_INVALID: "bot.link.fail_token",
  ACCOUNT_FROZEN: "bot.link.fail_frozen",
  ALREADY_LINKED: "bot.link.fail_already_linked",
  OWNERSHIP_NOT_PROVEN: "bot.link.fail_ownership",
  SERVER_OFFLINE: "bot.link.fail_offline",
  RATE_LIMITED: "bot.link.fail_rate_limited",
  ACCOUNT_NOT_FOUND: "bot.link.fail_not_found",
}

/**
 * the linking conversation, started from /start <token> after the token has
 * been preview-verified: shows who is being linked, waits for the explicit
 * confirmation, then runs the real link (ownership probe + one-time redeem).
 */
export async function linkingConversation(
  conversation: Conversation<BotContext, BotContext>,
  ctx: BotContext,
  deps: LinkingConversationDeps,
  token: string,
  username: string,
): Promise<void> {
  const from = await conversation.external((outer) => {
    const id = outer.from?.id
    const name = outer.from?.username ?? null
    return { id, name }
  })
  if (from.id === undefined) return

  // language preference lives on the account; default until it exists
  const locale: Locale = await conversation.external(async () => {
    const existing = await deps.accounts.getByTelegramId(String(from.id))
    return existing?.language ?? "ru"
  })

  await ctx.reply(t(locale, "bot.link.prompt", { username }), { reply_markup: linkingKeyboard((key) => t(locale, key)) })

  const answer = await conversation.waitForCallbackQuery([CALLBACK.linkConfirm, CALLBACK.linkCancel], {
    otherwise: (nonAnswer) => nonAnswer.reply(t(locale, "bot.link.confirming")),
  })
  await answer.answerCallbackQuery()

  if (answer.callbackQuery.data === CALLBACK.linkCancel) {
    await ctx.reply(t(locale, "bot.link.cancelled"))
    return
  }

  await ctx.reply(t(locale, "bot.link.confirming"))

  // the whole business flow runs exactly once, outside the replay machine
  const outcome = await conversation.external(() =>
    deps.accounts.linkTelegram({
      telegramId: String(from.id),
      telegramUsername: from.name,
      token,
    }),
  )

  if (outcome.linked) {
    await ctx.reply(t(locale, "bot.link.success", { username: outcome.username }))
    return
  }
  await ctx.reply(t(locale, FAILURE_KEYS[outcome.reason]))
}
