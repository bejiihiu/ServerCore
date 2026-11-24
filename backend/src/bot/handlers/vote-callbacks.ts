import type { Bot } from "grammy"
import type { BotContext } from "../context.js"
import type { Lang } from "../../services/ports.js"
import type { VotingService } from "../../services/voting-service.js"
import type { CastVoteReason } from "../../services/voting-service.js"
import { parseVoteCallbackData } from "../../services/voting-format.js"
import { t, type MessageKey } from "../../services/i18n.js"

const FEEDBACK_BY_REASON: Record<CastVoteReason, MessageKey> = {
  VOTER_NOT_FOUND: "vote.feedbackUnknown",
  NO_APPLICATION: "vote.feedbackUnknown",
  NOT_IN_VOTING: "vote.feedbackClosed",
  EXPIRED: "vote.feedbackClosed",
  NOT_ACTIVE: "vote.feedbackInactive",
  SELF_VOTE: "vote.feedbackSelf",
  NOT_IN_GROUP: "vote.feedbackNotInGroup",
  ALREADY_VOTED: "vote.feedbackAlready",
}

/**
 * vote buttons on the group voting message. the callback carries the
 * application id and the decision; the voter identity comes from the
 * telegram account, which maps to exactly one uuid.
 */
export function registerVoteCallbacks(
  bot: Bot<BotContext>,
  deps: { voting: VotingService; defaultLanguage: Lang },
): void {
  bot.callbackQuery(/^vote:/, async (ctx) => {
    const parsed = parseVoteCallbackData(ctx.callbackQuery.data)
    const telegramId = String(ctx.from.id)
    if (!parsed) {
      await ctx.answerCallbackQuery({ text: t(deps.defaultLanguage, "vote.feedbackUnknown") })
      return
    }

    const chatId = ctx.callbackQuery.message?.chat.id
    const outcome = await deps.voting.castVote({
      applicationId: parsed.applicationId,
      voterTelegramId: telegramId,
      decision: parsed.decision,
      // the ephemeral confirmation needs the callback id + group chat; when
      // the message reference is missing (inline mode), no ephemeral is sent
      callback:
        chatId === undefined
          ? undefined
          : { callbackQueryId: ctx.callbackQuery.id, chatId },
    })

    const feedback = outcome.ok
      ? t(deps.defaultLanguage, "vote.feedbackAccepted")
      : t(deps.defaultLanguage, FEEDBACK_BY_REASON[outcome.reason])
    await ctx.answerCallbackQuery({ text: feedback })
  })
}
