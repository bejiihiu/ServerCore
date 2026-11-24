import type { Conversation } from "@grammyjs/conversations"
import type { LinkFailureReason } from "../../modules/account/account-service.js"
import type { MessageKey } from "../i18n.js"
import { t } from "../i18n.js"
import { CALLBACK, linkingKeyboard } from "../keyboards.js"
import type { BotContext, BotDeps } from "../context.js"

const FAILURE_KEYS: Record<LinkFailureReason, MessageKey> = {
  TOKEN_INVALID: "linkFailTokenInvalid",
  ACCOUNT_FROZEN: "linkFailAccountFrozen",
  ALREADY_LINKED: "linkFailAlreadyLinked",
  OWNERSHIP_NOT_PROVEN: "linkFailOwnership",
  SERVER_OFFLINE: "linkFailServerOffline",
  RATE_LIMITED: "linkFailRateLimited",
  ACCOUNT_NOT_FOUND: "linkFailAccountNotFound",
}

/**
 * the linking conversation, started from /start <token> after the token has
 * been preview-verified: shows who is being linked, waits for the explicit
 * confirmation, then runs the real link (ownership probe + one-time redeem).
 */
export async function linkingConversation(
  conversation: Conversation<BotContext, BotContext>,
  ctx: BotContext,
  deps: BotDeps,
  token: string,
  username: string,
): Promise<void> {
  const lang = deps.defaultLanguage

  const from = await conversation.external((outer) => {
    const id = outer.from?.id
    const name = outer.from?.username ?? null
    return { id, name }
  })
  if (from.id === undefined) return

  await ctx.reply(t(lang, "linkPrompt", { username }), { reply_markup: linkingKeyboard(lang) })

  const answer = await conversation.waitForCallbackQuery([CALLBACK.linkConfirm, CALLBACK.linkCancel], {
    otherwise: (nonAnswer) => nonAnswer.reply(t(lang, "linkConfirmed")),
  })
  await answer.answerCallbackQuery()

  if (answer.callbackQuery.data === CALLBACK.linkCancel) {
    await ctx.reply(t(lang, "linkCancelled"))
    return
  }

  await ctx.reply(t(lang, "linkConfirmed"))

  // the whole business flow runs exactly once, outside the replay machine
  const outcome = await conversation.external(() =>
    deps.accounts.linkTelegram({
      telegramId: String(from.id),
      telegramUsername: from.name,
      token,
    }),
  )

  if (outcome.linked) {
    await ctx.reply(t(lang, "linkSuccess", { username: outcome.username }))
    return
  }
  await ctx.reply(t(lang, FAILURE_KEYS[outcome.reason]))
}
