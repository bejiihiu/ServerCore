import type { Api } from "grammy"
import { loadConfig, ConfigError as EnvConfigError, type AppConfig } from "./config/env.js"
import { createLogger } from "./logging/logger.js"
import type { Logger } from "./logging/logger.js"
import { connectDatabase, databaseReady, disconnectDatabase } from "./database/connection.js"
import { buildHttpServer } from "./http/server.js"
import { broadcastToAll, createGateway, type Gateway } from "./websocket/gateway.js"
import { HandlerRegistry } from "./websocket/handlers/registry.js"
import { registerAllHandlers } from "./websocket/handlers/wiring.js"
import { ServerDirectory } from "./websocket/handlers/server-lifecycle.js"
import { AccountJoinFlow } from "./websocket/handlers/account-handlers.js"
import { SpecAuditService } from "./modules/audit/spec-audit-service.js"
import { InMemoryTokenStore, TokenService } from "./services/token-service.js"
import {
  MongoAuditRepository,
  MongoAuthRequestRepository,
  MongoSessionRepository,
  MongoTelegramLinkRepository,
  MongoUserRepository,
} from "./modules/account/repository-mongo.js"
import { AccountService } from "./modules/account/account-service.js"
import { AccountDeletionService, BotApiTelegramGroupPort, GatewaySessionRevoker } from "./modules/account/deletion.js"
import { GatewayOwnershipVerifier } from "./modules/account/session-probe.js"
import type { NotificationFanout } from "./modules/notification/notification-port.js"
import { registerAdmissionFlows, type AdmissionServices } from "./bot/admission-bot.js"
import { createAdmissionRepositories, syncAdmissionIndexes } from "./database/repos.js"
import { LinkedTelegramRecipients } from "./services/recipients.js"
import { NotificationService } from "./services/notification-service.js"
import { GrammySender } from "./services/telegram-sender.js"
import { OpsNotifier, eventForState } from "./services/ops-notifier.js"
import { applicationDecisionMessageType, type PluginPushPort } from "./services/ports.js"
import { MongoStatsCounts } from "./services/stats-counts.js"
import { OfflineWatcher } from "./services/offline-watcher.js"
import { SessionCounters, StatsService } from "./services/runtime-stats.js"
import { NetworkSnapshotBroadcaster } from "./services/network-snapshot.js"
import { VotingScheduler } from "./services/voting-scheduler.js"
import { RetentionService } from "./modules/retention/retention-service.js"
import {
  MongoApplicationPurge,
  MongoAuditLogPurge,
  MongoGroupInvitePurge,
  MongoGuestSnapshotPurge,
  MongoServerEventPurge,
  MongoSessionPurge,
  MongoVotePurge,
  MongoVotingMessagePurge,
} from "./modules/retention/repository-mongo.js"
import { MongoCollectionStats } from "./modules/retention/collection-stats.js"
import { createBot } from "./bot/bot.js"
import { AuthenticationService } from "./modules/authentication/auth-service.js"
import { MongoAuthenticationStore } from "./modules/authentication/mongo-store.js"
import { TelegramAuthUi } from "./modules/authentication/telegram-prompt.js"
import { registerAuthCallbacks } from "./modules/authentication/bot-callbacks.js"
import { LocaleService } from "./services/locale-service.js"
import { MS_PER_MINUTE, MS_PER_SECOND } from "./utils/time.js"

// chat_member events must be requested explicitly, telegram does not send
// them by default
const ALLOWED_UPDATES = ["message", "callback_query", "chat_member", "my_chat_member"] as const

/** adapts the shared broadcast engine onto the account modules' fan-out port */
class FanoutAdapter implements NotificationFanout {
  private readonly notifications: NotificationService
  private readonly adminChatIds: readonly string[]

  constructor(notifications: NotificationService, adminChatIds: readonly number[]) {
    this.notifications = notifications
    this.adminChatIds = adminChatIds.map(String)
  }

  async notifyUser(chatId: string, text: string): Promise<void> {
    await this.notifications.sendToOne(chatId, text)
  }

  async notifyAdmins(text: string): Promise<void> {
    await this.notifications.sendToChats(this.adminChatIds, text)
  }
}

async function main(): Promise<void> {
  let config: AppConfig
  try {
    config = loadConfig()
  } catch (err) {
    if (err instanceof EnvConfigError) {
      console.error(`[servercore] ${err.message}`)
      process.exit(1)
    }
    throw err
  }

  const logger: Logger = createLogger(config)
  const startedAt = Date.now()

  // canonical locale bundle: shipped in the image, loaded once. a missing or
  // oversized file is a broken build — fail boot instead of silently serving
  // plugins an undefined locale forever
  const locales = new LocaleService({ logger })
  logger.info(
    {
      locale: locales.current.locale,
      revision: locales.current.revision,
      bytes: Buffer.byteLength(locales.current.content, "utf8"),
    },
    "canonical locale bundle loaded",
  )

  // a fatal error during boot must exit: the graceful path below dereferences
  // bindings that do not exist yet, so before this flips there is nothing to
  // close and the only correct move is a clean nonzero exit
  let booted = false

  process.on("unhandledRejection", (reason) => {
    logger.error({ err: reason }, "unhandled rejection")
  })
  process.on("uncaughtException", (err) => {
    logger.fatal({ err }, "uncaught exception")
    if (!booted) process.exit(1)
    void shutdown("uncaughtException", 1)
  })

  await connectDatabase(config, logger)

  const handlers = new HandlerRegistry(logger)
  const directory = new ServerDirectory()
  // assigned before the first status frame can possibly arrive; hooks only
  // dereference it at runtime
  let opsNotifier: OpsNotifier

  // authentication module: session lifecycle + telegram login flow.
  // the gateway push fails closed: a dead game-server link parks the message
  // in the service outbox and the sweeper retries it. the gateway binding
  // below is referenced lazily from this closure
  const authStore = new MongoAuthenticationStore()
  const authUi = new TelegramAuthUi({
    api: null,
    logger,
    serverName: config.serverName,
    ...(config.authMessageEffectId !== undefined && config.authMessageEffectId !== ""
      ? { messageEffectId: config.authMessageEffectId }
      : {}),
  })
  const authService = new AuthenticationService({
    store: authStore,
    config: {
      requestTtlSeconds: config.authRequestTtlSeconds,
      serverName: config.serverName,
      rateLimitWindowMs: config.authRateLimitWindowSeconds * MS_PER_SECOND,
      maxRequestsPerPlayer: config.authRateLimitRequestsPerPlayer,
      maxRequestsPerTelegram: config.authRateLimitRequestsPerTelegram,
      maxApprovalsPerTelegram: config.authRateLimitApprovalsPerTelegram,
    },
    logger,
    push: async (serverId, type, payload) => {
      try {
        await gateway.sendTo(serverId, type, payload, { awaitAck: true })
        return true
      } catch (error) {
        logger.debug({ err: error, serverId, type }, "auth push not delivered, staying in outbox")
        return false
      }
    },
    sendPrompt: (request) => authUi.send(request),
  })

  // ops plumbing: rate-limited broadcast -> dm users with linked telegram
  // accounts + admin chat copies. without a bot token everything degrades to
  // log lines instead of crashing. the admission flow and account modules
  // share this service for their own key-event fan-outs.
  const notifications = new NotificationService({
    sender: null,
    recipients: new LinkedTelegramRecipients(),
    logger,
    options: { ratePerSecond: config.notificationRatePerSecond },
  })
  opsNotifier = new OpsNotifier({
    notifications,
    adminChatIds: config.adminTelegramIds.map(String),
    locale: config.opsLocale,
    logger,
  })

  // guest snapshot janitor for the daily retention sweep. the session-end
  // purge rides the authentication store, this catches everything orphaned
  // by crashes and restarts
  const snapshotPurge = new MongoGuestSnapshotPurge()

  const gateway: Gateway = createGateway(config, logger, handlers)

  // account module wiring: token service on a process-local store for now
  // (single instance), repositories on mongo
  const tokenStore = new InMemoryTokenStore()
  const tokens = new TokenService(tokenStore, config.authRequestTtlSeconds)
  const verifier = new GatewayOwnershipVerifier(gateway, logger)
  const accounts = new AccountService({
    users: new MongoUserRepository(),
    links: new MongoTelegramLinkRepository(),
    audit: new MongoAuditRepository(),
    tokens,
    ownership: verifier,
    notifier: new FanoutAdapter(notifications, config.adminTelegramIds),
    logger,
    defaultLanguage: config.opsLocale,
  })

  // the deletion pipeline kicks through the shared bot api. the bot instance
  // is built later; the port dereferences it at call time, so wiring order
  // never matters
  const sharedApi: { current: Api | null } = { current: null }
  const deletion = new AccountDeletionService({
    users: new MongoUserRepository(),
    links: new MongoTelegramLinkRepository(),
    sessions: new MongoSessionRepository(),
    authRequests: new MongoAuthRequestRepository(),
    audit: new MongoAuditRepository(),
    group: new BotApiTelegramGroupPort(
      {
        banChatMember: (chatId, userId) => kickApi(sharedApi).banChatMember(chatId, userId),
        unbanChatMember: (chatId, userId, options) => kickApi(sharedApi).unbanChatMember(chatId, userId, options),
        revokeChatInviteLink: (chatId, link) => kickApi(sharedApi).revokeChatInviteLink(chatId, link),
      },
      config.telegramGroupChatId,
    ),
    sessionRevoker: new GatewaySessionRevoker(gateway),
    notifier: new FanoutAdapter(notifications, config.adminTelegramIds),
    logger,
  })

  // spec audit trail: every observation start/end the plugin reports lands in
  // audit_logs (kind "spec"). the collection has no ttl index — the retention
  // job below purges it together with the rest of the audit rows at 90d
  const specAudit = new SpecAuditService({ sink: new MongoAuditRepository(), logger })

  // one join = one pipeline: the account row first (the auth flow only ever
  // answers ACTIVE+linked accounts and looks the row up by uuid), then the
  // personal deep-link push for unlinked players, then the auth flow
  const accountJoin = new AccountJoinFlow({ accounts, gateway, logger })
  const joinPipeline = async (event: { minecraftUuid: string; username: string; serverId: string }) => {
    await accountJoin.onPlayerJoined(event)
    await authService.handlePlayerJoined(event, Date.now())
  }

  // network view: a heartbeat just refreshed the directory, so the aggregate
  // goes out to every game server. no timer, no polling, one extra frame per
  // heartbeat window at most
  const networkSnapshots = new NetworkSnapshotBroadcaster(gateway, directory, logger)

  // every websocket handler registers exactly once, here. this runs in the
  // same synchronous block as createGateway above, so the gateway cannot
  // dispatch a single frame before all registrations are done
  registerAllHandlers(handlers, {
    directory,
    logger,
    verifier,
    specAudit,
    locale: locales,
    hooks: {
      onServerRegistered: (serverId, connection) => {
        // fresh socket, registration status already acked: hand the plugin
        // the full canonical bundle. the ack comes back through LOCALE_ACK
        const correlationId = locales.pushTo(connection)
        logger.debug({ serverId, revision: locales.current.revision, correlationId }, "locale push sent")
      },
      onHeartbeat: (serverId) => {
        networkSnapshots.onHeartbeat(serverId)
      },
      onStateChange: (serverId, state, previous, options) => {
        // crash/status events flow to telegram: all events, dm + admin copy.
        // when the plugin reports flectonepulse's telegram module as active it
        // suppresses its own announcements - the bot stays quiet so nothing
        // lands in the chat twice
        const telegram = options?.telegramSuppressed === true ? false : true
        void opsNotifier.announce(serverId, eventForState(state), { telegram }).catch((error) => {
          logger.error({ err: error, serverId, state, previous }, "state-change notification failed")
        })
      },
      onPlayerJoined: (event) => {
        void joinPipeline(event).catch((error) => {
          logger.error({ err: error, uuid: event.minecraftUuid }, "player join handling failed")
        })
      },
      onPlayerLeft: (event) => {
        void authService.handlePlayerLeft(event.minecraftUuid, Date.now()).catch((error) => {
          logger.error({ err: error, uuid: event.minecraftUuid }, "player left handling failed")
        })
      },
      onGuestStarted: (event, serverId) => {
        void authService.handleGuestStarted(event, serverId, Date.now()).catch((error) => {
          logger.error({ err: error, uuid: event.minecraftUuid }, "guest start handling failed")
        })
      },
      onGuestEnded: (event) => {
        void authService.handleGuestEnded(event).catch((error) => {
          logger.error({ err: error, uuid: event.minecraftUuid }, "guest end handling failed")
        })
      },
    },
  })

  // admission state changes reach the plugin's placeholder cache over the
  // gateway; fire-and-forget, a disconnected server re-seeds on reconnect
  const pluginPush: PluginPushPort = {
    applicationSubmitted(application) {
      broadcastToAll(gateway, logger, "APPLICATION_SUBMITTED", {
        applicationId: application.id,
        minecraftUuid: application.minecraftUuid,
        telegramId: application.telegramId,
      })
    },
    applicationDecided(application, outcome) {
      broadcastToAll(
        gateway,
        logger,
        applicationDecisionMessageType(outcome),
        { applicationId: application.id, minecraftUuid: application.minecraftUuid },
      )
    },
  }

  const counters = new SessionCounters()
  const statsService = new StatsService({
    startedAt,
    gateway,
    directory,
    counters,
    // domain counters for /stats; constructed unconditionally - a boot-time
    // readiness check is always false here (connect retries in the
    // background), and a mongo blip degrades per request via allSettled
    counts: new MongoStatsCounts(),
  })

  // retention: the backend owns the database, so it does the housekeeping.
  // audit/server_events 90d, closed sessions 60d, orphaned guest snapshots,
  // old votes/keyboards 90d, dead invites 30d, dead-end applications 1y —
  // atlas m0 has 512mb and dead rows add up fast
  const retention = new RetentionService({
    audit: new MongoAuditLogPurge(),
    serverEvents: new MongoServerEventPurge(),
    sessions: new MongoSessionPurge(),
    snapshots: snapshotPurge,
    votingMessages: new MongoVotingMessagePurge(),
    invites: new MongoGroupInvitePurge(),
    votes: new MongoVotePurge(),
    applications: new MongoApplicationPurge(),
    options: {
      auditDays: config.retentionAuditDays,
      sessionDays: config.retentionSessionDays,
      initialDelayMs: config.retentionInitialDelayMinutes * MS_PER_MINUTE,
    },
    logger,
  })
  const stopRetention = retention.start()

  // the one and only bot instance: account routes + admission flows + auth
  // callbacks all register on it. a second bot would silently discard the
  // other side's handlers, so createAdmissionFlows receives this instance.
  const bot = createBot(config, logger, {
    accounts,
    deletion,
    defaultLanguage: config.opsLocale,
    adminTelegramIds: config.adminTelegramIds,
    stats: statsService,
    // constructed unconditionally, same reasoning as the stats counts above;
    // the /stats route skips the db section when collect() fails
    collectionStats: new MongoCollectionStats(),
  })
  sharedApi.current = bot?.api ?? null

  let admission: AdmissionServices | null = null
  if (bot !== null) {
    // admission: questionnaire + group voting, riding the shared notification
    // engine; /start /status /help /stats are owned by the account routes
    admission = registerAdmissionFlows(bot, config, logger, createAdmissionRepositories(), {
      notifications,
      plugin: pluginPush,
    })
    // auth approve/reject buttons ride the same instance
    registerAuthCallbacks(bot, { auth: authService, ui: authUi, logger })
  }

  // grammy api is ready the moment the Bot exists, swap the real senders in
  const api = bot !== null ? bot.api : null
  notifications.attachSender(api !== null ? new GrammySender(api) : null)
  authUi.attachApi(api)

  // durable catch-up scan on boot: votings that expired while we were down
  // (including already-extended ones) are processed by the first sweep
  const scheduler = admission
    ? new VotingScheduler({
        voting: admission.voting,
        group: admission.group,
        logger,
        intervalMs: config.votingScanIntervalMs,
      })
    : null

  if (databaseReady()) {
    try {
      await syncAdmissionIndexes()
      logger.info("admission indexes synced")
    } catch (err) {
      logger.warn({ err }, "index sync failed, continuing")
    }
  } else {
    logger.warn("mongo not ready at boot, indexes not synced")
  }

  // crash detection: ws gone for N minutes => presumed offline
  const offlineWatcher = new OfflineWatcher({
    directory,
    gateway: { get connectedServers() { return gateway.connectedServers } },
    logger,
    thresholdMs: config.serverOfflineAlertMinutes * MS_PER_MINUTE,
  })
  offlineWatcher.onReconnect = (serverId) => {
    // the dedupe memory must go: the next state flip re-announces. and the
    // admin who got the crash alert also gets the all-clear
    opsNotifier.forget(serverId)
    void opsNotifier.serverBack(serverId).catch((error) => {
      logger.error({ err: error, serverId }, "server-back notification failed")
    })
  }
  offlineWatcher.onAlert = (alert) => {
    void opsNotifier
      .offlineAlert(alert.serverId, alert.minutesUnreachable, config.notifyUsersOnOffline)
      .catch((error) => logger.error({ err: error, serverId: alert.serverId }, "offline alert failed"))
  }
  offlineWatcher.start()

  const app = await buildHttpServer({
    config,
    logger,
    health: () => ({
      uptimeSeconds: Math.floor((Date.now() - startedAt) / 1000),
      mongoConnected: databaseReady(),
      gateway: gateway.stats,
      connectedServers: gateway.connectedServers,
    }),
  })
  await app.listen({ port: config.port, host: config.host })
  logger.info({ port: config.port, host: config.host }, "http listening")

  if (bot !== null) {
    void bot.start({
      allowed_updates: ALLOWED_UPDATES,
      onStart: (me) => logger.info({ username: me.username }, "bot polling started"),
    })
    scheduler?.start()
  } else {
    logger.warn("bot is disabled: admission and account flows inactive")
  }

  // auth sweeper: expiry enforcement, prompt retries, outbox flush; the same
  // tick keeps /stats session counters honest
  const sweeper = setInterval(() => {
    void authService.sweep(Date.now()).catch((error) => {
      logger.error({ err: error }, "auth sweep failed")
    })
    const stats = authService.stats()
    counters.activeSessions = stats.activeSessions
    counters.guestSessions = stats.guestSessions
    counters.pendingAuths = stats.pendingAuths
  }, config.authSweepIntervalMs)
  sweeper.unref()

  logger.info({ env: config.env }, "servercore backend started")
  booted = true

  let shuttingDown = false
  async function shutdown(signal: string, exitCode = 0): Promise<void> {
    if (shuttingDown) return
    shuttingDown = true
    logger.info({ signal }, "shutting down")
    try {
      scheduler?.stop()
      clearInterval(sweeper)
      authService.stop()
      offlineWatcher.stop()
      stopRetention()
      await bot?.stop()
      await gateway.stop()
      await app.close()
      await disconnectDatabase(logger)
    } catch (err) {
      logger.error({ err }, "error during shutdown")
    }
    process.exit(exitCode)
  }

  process.once("SIGINT", () => void shutdown("SIGINT"))
  process.once("SIGTERM", () => void shutdown("SIGTERM"))
}

/** the group kick rides the shared bot api; null means the bot is disabled */
function kickApi(sharedApi: { current: Api | null }): Api {
  if (sharedApi.current === null) throw new Error("telegram bot disabled, cannot kick")
  return sharedApi.current
}

main().catch((err) => {
  console.error("[servercore] fatal startup error:", err)
  process.exit(1)
})
