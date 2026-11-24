import type { Bot } from "grammy"
import type { BotContext } from "../context.js"
import type { GroupService } from "../../services/group-service.js"
import type { Logger } from "../../logging/logger.js"

/**
 * chat_member updates for the managed group: a user arriving via a one-time
 * invite completes the group link and, when every condition is met,
 * activates the account.
 */
export function registerChatMemberHandler(
  bot: Bot<BotContext>,
  deps: { group: GroupService; logger: Logger },
): void {
  bot.on("chat_member", async (ctx) => {
    const update = ctx.update.chat_member
    const newMember = update.new_chat_member
    const oldStatus = update.old_chat_member.status
    const newStatus = newMember.status

    // only fresh arrivals, not status shuffles of existing members
    const arrived =
      (newStatus === "member" || newStatus === "restricted" || newStatus === "administrator") &&
      (oldStatus === "left" || oldStatus === "kicked")
    if (!arrived) return

    const outcome = await deps.group.handleJoin({
      chatId: update.chat.id,
      telegramId: String(newMember.user.id),
      // the invite the user actually used, straight from the api payload
      inviteLinkName: update.invite_link?.name ?? null,
    })
    if (!outcome.ok) {
      // foreign chats and anomalies are audited inside the service; log the
      // reason so misconfigurations surface in the backend logs too
      deps.logger.info({ chatId: update.chat.id, reason: outcome.reason }, "group join not processed")
    }
  })
}
