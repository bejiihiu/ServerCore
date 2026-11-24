import type { Conversation } from "@grammyjs/conversations"
import { t } from "../i18n.js"
import { CALLBACK, deleteConfirmKeyboard } from "../keyboards.js"
import type { BotContext } from "../context.js"
import type { AccountView } from "../../modules/account/account-service.js"
import type { AccountDeletionService } from "../../modules/account/deletion.js"

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
  const lang = account.language

  await ctx.reply(t(lang, "deleteConfirm"), { reply_markup: deleteConfirmKeyboard(lang) })

  const answer = await conversation.waitForCallbackQuery([CALLBACK.linkConfirm, CALLBACK.linkCancel], {
    otherwise: (nonAnswer) => nonAnswer.reply(t(lang, "deleteConfirm")),
  })
  await answer.answerCallbackQuery()

  if (answer.callbackQuery.data === CALLBACK.linkCancel) {
    await ctx.reply(t(lang, "linkCancelled"))
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

  await ctx.reply(t(lang, succeeded ? "deleteDone" : "deleteFail", { username: account.username }))
}
