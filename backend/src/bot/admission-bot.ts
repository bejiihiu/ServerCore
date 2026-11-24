import { Bot } from "grammy"
import { conversations, createConversation } from "@grammyjs/conversations"
import type { BotContext } from "./context.js"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"
import type { AdmissionRepositories } from "../database/repos.js"
import { MongoConversationStorage } from "../database/conversation-storage.js"
import { systemClock } from "../services/clock.js"
import { RateLimiter } from "../services/rate-limiter.js"
import { ApplicationService } from "../services/application-service.js"
import { VotingService } from "../services/voting-service.js"
import { GroupService } from "../services/group-service.js"
import type { NotificationService } from "../services/notification-service.js"
import { AdmissionNotifier } from "../services/admission-notifier.js"
import type { StatsService } from "../services/runtime-stats.js"
import { GrammYTelegramApi } from "./telegram-api.js"
import { createApplicationConversation } from "./conversations/application-questionnaire.js"
import { registerVoteCallbacks } from "./handlers/vote-callbacks.js"
import { registerChatMemberHandler } from "./handlers/chat-member.js"
import { registerAdmissionCommands } from "./commands/admission.js"
import { registerStartCommand } from "./commands/start.js"
import { registerStatsCommand } from "./commands/stats.js"

export type AdmissionBot = {
  bot: Bot<BotContext>
  applications: ApplicationService
  voting: VotingService
  group: GroupService
}

export const APPLICATION_CONVERSATION_ID = "application"

/**
 * builds the telegram bot with the full admission flow plus the ops /stats
 * command: questionnaire conversation, group voting, invites, join tracking.
 * returns null when no token is configured — the backend then runs without
 * the bot instead of crashing. the caller owns start/stop lifecycle.
 */
export function createAdmissionBot(
  config: AppConfig,
  logger: Logger,
  repos: AdmissionRepositories,
  deps: {
    notifications: NotificationService
    stats: StatsService
  },
): AdmissionBot | null {
  if (!config.botToken) {
    logger.warn("no bot token configured, telegram bot disabled")
    return null
  }

  const bot = new Bot<BotContext>(config.botToken)
  bot.catch((error) => {
    logger.error({ err: error.error }, "telegram bot error")
  })

  const telegram = new GrammYTelegramApi(bot, {
    groupChatId: config.groupChatId,
  })

  // admin chat ids for key-event duplicates come from the shared ops config
  const adminChatIds = config.adminTelegramIds.map(String)
  const notifier = new AdmissionNotifier({
    notifications: deps.notifications,
    adminChatIds,
    locale: config.defaultLanguage,
  })

  const applications = new ApplicationService({
    users: repos.users,
    applications: repos.applications,
    drafts: repos.drafts,
    audit: repos.audit,
    telegram,
    notifier,
    rateLimiter: new RateLimiter(
      config.applicationStartLimit,
      config.applicationStartWindowMs,
      systemClock,
    ),
    clock: systemClock,
    cooldownMs: 90 * 24 * 3_600_000, // three months, per the re-application rule
    votingDurationMs: config.votingDurationMs,
    questions: config.questions,
    defaultLanguage: config.defaultLanguage,
  })

  const voting = new VotingService({
    users: repos.users,
    applications: repos.applications,
    votes: repos.votes,
    messages: repos.votingMessages,
    audit: repos.audit,
    telegram,
    notifier,
    clock: systemClock,
    groupChatId: config.groupChatId,
    defaultLanguage: config.defaultLanguage,
    voteButtonIcons: {
      acceptId: config.voteButtonIcons.enabled ? config.voteButtonIcons.acceptId : null,
      rejectId: config.voteButtonIcons.enabled ? config.voteButtonIcons.rejectId : null,
    },
    approvalEffectId: config.approvalEffectId,
  })

  const group = new GroupService({
    users: repos.users,
    invites: repos.invites,
    telegram,
    notifier,
    audit: repos.audit,
    clock: systemClock,
    groupChatId: config.groupChatId,
    inviteTtlMs: config.groupInviteTtlMs,
    defaultLanguage: config.defaultLanguage,
  })

  // chat_member updates must be requested explicitly or telegram never
  // sends them; that list goes to bot.start() in app.ts
  bot.use(conversations({
    storage: {
      type: "key",
      version: 0, // bump when the conversation code changes
      adapter: new MongoConversationStorage(),
    },
  }))
  bot.use(createConversation(
    createApplicationConversation({ applications, voting }),
    { id: APPLICATION_CONVERSATION_ID },
  ))

  registerStartCommand(bot)
  registerAdmissionCommands(bot, {
    applications,
    users: repos.users,
    defaultLanguage: config.defaultLanguage,
    conversationId: APPLICATION_CONVERSATION_ID,
  })
  registerVoteCallbacks(bot, { voting, defaultLanguage: config.defaultLanguage })
  registerChatMemberHandler(bot, { group, logger })
  registerStatsCommand(bot, {
    stats: deps.stats,
    adminIds: config.adminTelegramIds,
    locale: config.opsLocale,
  })

  return { bot, applications, voting, group }
}
