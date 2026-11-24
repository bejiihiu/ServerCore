import type { Conversation } from "@grammyjs/conversations"
import { CALLBACK } from "../keyboards.js"
import type { BotContext } from "../context.js"
import type { AccountView } from "../../modules/account/account-service.js"
import type { AccountDeletionService } from "../../modules/account/deletion.js"
import { emoji, tr } from "../ui/runtime.js"
import { confirmDialogScreen, hintScreen, noticeScreen, type ScreenMessage } from "../ui/screens/index.js"
import { replyScreen } from "../ui/sending.js"

/** the deletion conversation needs the deletion service and nothing else */
export type DeleteConversationDeps = {
  deletion: AccountDeletionService
}

/**
 * the deletion conversation: explicit two-step confirmation, then the real
 * pipeline. failure keeps the account in DELETION_PENDING — the user is told
 * exactly that and can retry later.
 */
export async function deleteAccountConversation(
  conversation: Conversation<BotContext, BotContext>,
  ctx: BotContext,
  deps: DeleteConversationDeps,
  account: AccountView,
): Promise<void> {
  const locale = account.language

  await replyScreen(ctx, confirmScreen(locale))

  const answer = await conversation.waitForCallbackQuery([CALLBACK.deleteConfirm, CALLBACK.deleteCancel], {
    otherwise: (nonAnswer) => void replyScreen(nonAnswer, hintScreen(tr(locale).t("deletion.nudge"))),
  })
  await answer.answerCallbackQuery()

  if (answer.callbackQuery.data === CALLBACK.deleteCancel) {
    await replyScreen(ctx, cancelledScreen(locale))
    return
  }

  const succeeded = await conversation.external(async () => {
    try {
      await deps.deletion.deleteAccount(account.minecraftUuid, account.telegramId)
      return true
    } catch {
      // pipeline failures leave the account in DELETION_PENDING; the user
      // gets the retry message below
      return false
    }
  })

  await replyScreen(ctx, succeeded ? doneScreen(locale, account.username) : failureScreen(locale))
}

function confirmScreen(locale: AccountView["language"]): ScreenMessage {
  const t = tr(locale)
  return confirmDialogScreen({
    t,
    e: emoji,
    tone: "attention",
    title: t.t("deletion.confirmTitle"),
    body: t.t("deletion.confirmBody"),
    keyboard: {
      cancel: { label: t.t("common.cancel"), callbackData: CALLBACK.deleteCancel },
      confirm: { label: t.t("deletion.confirmButton"), callbackData: CALLBACK.deleteConfirm },
    },
    buttonTone: "danger",
  })
}

function cancelledScreen(locale: AccountView["language"]): ScreenMessage {
  const t = tr(locale)
  return noticeScreen({
    t,
    e: emoji,
    tone: "neutral",
    title: t.t("deletion.cancelledTitle"),
    body: t.t("deletion.cancelledBody"),
  })
}

function doneScreen(locale: AccountView["language"], username: string): ScreenMessage {
  const t = tr(locale)
  return noticeScreen({
    t,
    e: emoji,
    tone: "active",
    title: t.t("deletion.doneTitle"),
    body: t.t("deletion.doneFact", { username }),
    aside: t.t("deletion.doneAside"),
  })
}

function failureScreen(locale: AccountView["language"]): ScreenMessage {
  const t = tr(locale)
  return noticeScreen({
    t,
    e: emoji,
    tone: "attention",
    title: t.t("deletion.failTitle"),
    body: t.t("deletion.failBody"),
  })
}
