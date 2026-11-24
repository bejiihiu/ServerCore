import { Bot } from "grammy"
import { createConversation } from "@grammyjs/conversations"
import type { BotContext } from "./context.js"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"
import type { AdmissionRepositories } from "../database/repos.js"
import { systemClock } from "../services/clock.js"
import { RateLimiter } from "../utils/rate-limiter.js"
import { ApplicationService } from "../services/application-service.js"
import { VotingService } from "../services/voting-service.js"
import { GroupService } from "../services/group-service.js"
import type { NotificationService } from "../services/notification-service.js"
import type { PluginPushPort } from "../services/ports.js"
import { AdmissionNotifier } from "../services/admission-notifier.js"
import { GrammYTelegramApi } from "./telegram-api.js"
import { MS_PER_DAY } from "../utils/time.js"
import { createApplicationConversation } from "./conversations/application-questionnaire.js"
import { registerVoteCallbacks } from "./handlers/vote-callbacks.js"
import { registerChatMemberHandler } from "./handlers/chat-member.js"
import { registerAdmissionCommands } from "./commands/admission.js"

export type AdmissionServices = {
  applications: ApplicationService
  voting: VotingService
  group: GroupService
}

export const APPLICATION_CONVERSATION_ID = "application"

/**
 * registers the admission flow (questionnaire conversation, group voting,
 * invites, join tracking) on the SHARED bot instance. creates no bot of its
 * own: two bot instances would silently discard each other's handlers, so
 * everything must ride the one bot built in createBot.
 *
 * the conversations plugin is also already installed on the shared bot (mongo
 * storage); the application conversation registers into that shared index.
 */
export function registerAdmissionFlows(
  bot: Bot<BotContext>,
  config: AppConfig,
  logger: Logger,
  repos: AdmissionRepositories,
  deps: {
    notifications: NotificationService
    /** gateway push to the plugin state cache (application lifecycle) */
    plugin: PluginPushPort
  },
): AdmissionServices {
  const telegram = new GrammYTelegramApi(bot, {
    groupChatId: config.groupChatId,
  })

  // admin chat ids for key-event duplicates come from the shared ops config
  const adminChatIds = config.adminTelegramIds.map(String)
  const notifier = new AdmissionNotifier({
    notifications: deps.notifications,
    adminChatIds,
    locale: config.defaultLanguage,
    logger,
  })

  const applications = new ApplicationService({
    users: repos.users,
    applications: repos.applications,
    drafts: repos.drafts,
    audit: repos.audit,
    telegram,
    notifier,
    rateLimiter: new RateLimiter({
      limit: config.applicationStartLimit,
      windowMs: config.applicationStartWindowMs,
    }),
    clock: systemClock,
    cooldownMs: 90 * MS_PER_DAY, // three months, per the re-application rule
    votingDurationMs: config.votingDurationMs,
    questions: config.questions,
    defaultLanguage: config.defaultLanguage,
    plugin: deps.plugin,
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
    plugin: deps.plugin,
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

  // the application conversation rides the shared conversations plugin
  // installed in createBot (mongo-backed); only the registration happens here
  bot.use(createConversation(
    createApplicationConversation({ applications, voting }),
    { id: APPLICATION_CONVERSATION_ID },
  ))

  registerAdmissionCommands(bot, {
    applications,
    users: repos.users,
    defaultLanguage: config.defaultLanguage,
    conversationId: APPLICATION_CONVERSATION_ID,
  })
  registerVoteCallbacks(bot, { voting, defaultLanguage: config.defaultLanguage })
  registerChatMemberHandler(bot, { group, logger })

  return { applications, voting, group }
}
