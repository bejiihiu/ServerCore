import type { Conversation } from "@grammyjs/conversations"
import type { LinkFailureReason } from "../../modules/account/account-service.js"
import type { Locale } from "../../services/i18n.js"
import { CALLBACK } from "../keyboards.js"
import type { BotContext } from "../context.js"
import type { AccountService } from "../../modules/account/account-service.js"
import { emoji, tr } from "../ui/runtime.js"
import { confirmDialogScreen, hintScreen, loadingScreen, noticeScreen, type ScreenMessage } from "../ui/screens/index.js"
import { replyScreen } from "../ui/sending.js"

/** the linking conversation needs the account service and nothing else */
export type LinkingConversationDeps = {
  accounts: AccountService
  /** locale for users whose account does not exist yet (ops default) */
  fallbackLanguage: Locale
}

const FAILURE_KEYS: Record<LinkFailureReason, string> = {
  TOKEN_INVALID: "fail.token",
  ACCOUNT_FROZEN: "fail.frozen",
  ALREADY_LINKED: "fail.alreadyLinked",
  OWNERSHIP_NOT_PROVEN: "fail.ownership",
  SERVER_OFFLINE: "fail.offline",
  RATE_LIMITED: "fail.rateLimited",
  ACCOUNT_NOT_FOUND: "fail.notFound",
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

  // language preference lives on the account; ops default until it exists
  const locale: Locale = await conversation.external(async () => {
    const existing = await deps.accounts.getByTelegramId(String(from.id))
    return existing?.language ?? deps.fallbackLanguage
  })

  await replyScreen(ctx, promptScreen(locale, username))

  const answer = await conversation.waitForCallbackQuery([CALLBACK.linkConfirm, CALLBACK.linkCancel], {
    otherwise: (nonAnswer) => void replyScreen(nonAnswer, hintScreen(tr(locale).t("link.nudge"))),
  })
  await answer.answerCallbackQuery()

  if (answer.callbackQuery.data === CALLBACK.linkCancel) {
    await replyScreen(ctx, cancelledScreen(locale))
    return
  }

  await replyScreen(ctx, loadingScreen(tr(locale), tr(locale).t("link.progress")))

  // the whole business flow runs exactly once, outside the replay machine
  const outcome = await conversation.external(() =>
    deps.accounts.linkTelegram({
      telegramId: String(from.id),
      telegramUsername: from.name,
      token,
    }),
  )

  if (outcome.linked) {
    await replyScreen(ctx, successScreen(locale, outcome.username))
    return
  }
  await replyScreen(ctx, failureScreen(locale, outcome.reason))
}

function promptScreen(locale: Locale, username: string): ScreenMessage {
  const t = tr(locale)
  return confirmDialogScreen({
    t,
    e: emoji,
    tone: "pending",
    title: t.t("link.confirmTitle"),
    body: t.t("link.confirmFact", { username }),
    aside: t.t("link.confirmAside"),
    keyboard: {
      cancel: { label: t.t("common.cancel"), callbackData: CALLBACK.linkCancel },
      confirm: { label: t.t("common.confirm"), callbackData: CALLBACK.linkConfirm },
    },
  })
}

function cancelledScreen(locale: Locale): ScreenMessage {
  const t = tr(locale)
  return noticeScreen({
    t,
    e: emoji,
    tone: "neutral",
    title: t.t("link.cancelledTitle"),
    body: t.t("link.cancelledBody"),
  })
}

function successScreen(locale: Locale, username: string): ScreenMessage {
  const t = tr(locale)
  return noticeScreen({
    t,
    e: emoji,
    tone: "active",
    title: t.t("link.successTitle"),
    body: t.t("link.successFact", { username }),
    aside: t.t("link.successAside"),
  })
}

function failureScreen(locale: Locale, reason: LinkFailureReason): ScreenMessage {
  const t = tr(locale)
  return noticeScreen({
    t,
    e: emoji,
    tone: "attention",
    title: t.t("link.failTitle"),
    body: t.t(`link.${FAILURE_KEYS[reason]}`),
  })
}
