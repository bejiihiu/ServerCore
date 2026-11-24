import type { Context } from "grammy"
import type { Bot } from "grammy"
import type { Logger } from "../../logging/logger.js"
import type { Locale } from "../../services/i18n.js"
import { t } from "../../services/i18n.js"
import type { AuthenticationService, CallbackOutcome } from "./auth-service.js"
import { parseAuthCallbackData, TelegramAuthUi, type AuthOutcomeKey } from "./telegram-prompt.js"

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
    defaultLocale: Locale
  },
): void {
  bot.callbackQuery(/^(a|r):/, async (ctx) => {
    const parsed = parseAuthCallbackData(ctx.callbackQuery?.data)
    if (parsed === null) {
      await answer(ctx, "auth.cb.unknown", deps.defaultLocale)
      return
    }

    const telegramId = String(ctx.callbackQuery?.from?.id ?? "")
    if (telegramId === "") {
      await answer(ctx, "auth.cb.unknown", deps.defaultLocale)
      return
    }

    const now = Date.now()
    const outcome =
      parsed.kind === "APPROVE"
        ? await deps.auth.approve(parsed.token, telegramId, now)
        : await deps.auth.reject(parsed.token, telegramId, now)

    await answer(ctx, outcomeKey(outcome), localeFor(ctx, deps.defaultLocale))

    const located = await deps.auth.locate(parsed.token, telegramId)
    if (located !== null) {
      const player = (await deps.auth.usernameForToken(parsed.token)) ?? "player"
      await deps.ui.conclude(located.id, outcomeKey(outcome), player)
    }
  })
}

/** maps a service outcome to the user-facing message key */
function outcomeKey(outcome: CallbackOutcome): AuthOutcomeKey {
  switch (outcome.kind) {
    case "APPROVED":
      return "auth.cb.approved"
    case "REJECTED":
      return "auth.cb.rejected"
    case "ALREADY_PROCESSED":
      return "auth.cb.already_processed"
    case "EXPIRED":
      return "auth.cb.expired"
    case "NOT_YOURS":
      return "auth.cb.not_yours"
    case "NO_ACTIVE_SESSION":
      return "auth.cb.no_active_session"
    case "STALE_SESSION":
      return "auth.cb.stale_session"
    case "RATE_LIMITED":
      return "auth.cb.rate_limited"
    case "UNKNOWN":
      return "auth.cb.unknown"
  }
}

function localeFor(ctx: Context, fallback: Locale): Locale {
  const code = ctx.callbackQuery?.from?.language_code
  return code !== undefined && code.toLowerCase().startsWith("ru") ? "ru" : fallback
}

async function answer(ctx: Context, key: AuthOutcomeKey, locale: Locale): Promise<void> {
  try {
    await ctx.answerCallbackQuery({ text: t(locale, key) })
  } catch {
    // stale or already answered queries are noise, the message edit carries
    // the real outcome
  }
}
