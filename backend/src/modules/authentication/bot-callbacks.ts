import type { Context } from "grammy"
import type { Bot } from "grammy"
import type { Logger } from "../../logging/logger.js"
import { i18n } from "../../i18n/instance.js"
import type { AuthenticationService, CallbackOutcome } from "./auth-service.js"
import type { TelegramAuthUi, AuthOutcomeKey } from "./telegram-prompt.js"
import { parseAuthCallbackData } from "./telegram-prompt.js"

/** outcome key per service verdict, resolved against the bundled ru locale */
function outcomeKey(outcome: CallbackOutcome): AuthOutcomeKey {
  switch (outcome.kind) {
    case "APPROVED":
      return "approved"
    case "REJECTED":
      return "rejected"
    case "ALREADY_PROCESSED":
      return "alreadyProcessed"
    case "EXPIRED":
      return "expired"
    case "NOT_YOURS":
      return "notYours"
    case "NO_ACTIVE_SESSION":
      return "noActiveSession"
    case "STALE_SESSION":
      return "staleSession"
    case "RATE_LIMITED":
      return "rateLimited"
    case "UNKNOWN":
      return "unknown"
  }
}

async function answer(ctx: Context, key: AuthOutcomeKey, player: string): Promise<void> {
  try {
    await ctx.answerCallbackQuery({ text: i18n.t(`auth.${key}`, { player }) })
  } catch {
    // stale or already answered queries are noise, the message edit carries
    // the real outcome
  }
}

/**
 * telegram callback wiring: approve/reject presses run through the service's
 * full protection matrix and then the prompt message is rewritten with the
 * outcome, so stale buttons die on the spot.
 */
export function registerAuthCallbacks(
  bot: Bot,
  deps: {
    auth: AuthenticationService
    ui: TelegramAuthUi
    logger: Logger
  },
): void {
  bot.callbackQuery(/^auth:(approve|reject):/, async (ctx) => {
    const query = ctx.callbackQuery
    const parsed = parseAuthCallbackData(query?.data)
    if (parsed === null) {
      await answer(ctx, "unknown", "")
      return
    }

    const telegramId = String(query?.from?.id ?? "")
    if (telegramId === "") {
      await answer(ctx, "unknown", "")
      return
    }

    const now = Date.now()
    const outcome =
      parsed.kind === "APPROVE"
        ? await deps.auth.approve(parsed.token, telegramId, now)
        : await deps.auth.reject(parsed.token, telegramId, now)

    const player = (await deps.auth.usernameForToken(parsed.token)) ?? ""
    await answer(ctx, outcomeKey(outcome), player)

    const located = await deps.auth.locate(parsed.token, telegramId)
    if (located !== null) {
      await deps.ui.conclude(located.id, outcomeKey(outcome), player || "player")
    }
  })
}
