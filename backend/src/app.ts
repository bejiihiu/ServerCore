import type { Bot } from "grammy"
import { loadConfig, ConfigError as EnvConfigError } from "./config/env.js"
import type { AppConfig } from "./config/env.js"
import { createLogger } from "./logging/logger.js"
import type { Logger } from "./logging/logger.js"
import { connectDatabase, databaseReady, disconnectDatabase } from "./database/connection.js"
import { buildHttpServer } from "./http/server.js"
import { createGateway, type Gateway } from "./websocket/gateway.js"
import { HandlerRegistry } from "./websocket/handlers/registry.js"
import { registerLifecycleHandlers, ServerDirectory } from "./websocket/handlers/server-lifecycle.js"
import { registerAccountHandlers } from "./websocket/handlers/account-handlers.js"
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
import { LinkedTelegramRecipients } from "./services/recipients.js"
import { NotificationService } from "./services/notification-service.js"
import { GrammySender } from "./services/telegram-sender.js"
import { OpsNotifier, eventForState } from "./services/ops-notifier.js"
import { OfflineWatcher } from "./services/offline-watcher.js"
import { SessionCounters, StatsService } from "./services/runtime-stats.js"
import { RetentionService } from "./modules/retention/retention-service.js"
import {
  MongoAuditLogPurge,
  MongoGuestSnapshotPurge,
  MongoServerEventPurge,
  MongoSessionPurge,
} from "./modules/retention/repository-mongo.js"
import { MongoCollectionStats } from "./modules/retention/collection-stats.js"
import { createBot } from "./bot/bot.js"
import type { BotDeps } from "./bot/context.js"

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

  process.on("unhandledRejection", (reason) => {
    logger.error({ err: reason }, "unhandled rejection")
  })
  process.on("uncaughtException", (err) => {
    logger.fatal({ err }, "uncaught exception")
    void shutdown("uncaughtException")
  })

  await connectDatabase(config, logger)

  const handlers = new HandlerRegistry(logger)
  const directory = new ServerDirectory()
  // assigned below, before any message can possibly arrive; the hook only
  // dereferences it at runtime
  let opsNotifier: OpsNotifier
  registerLifecycleHandlers(handlers, directory, logger, {
    onStateChange: (serverId, state, previous) => {
      // crash/status events flow to telegram: all events, dm + admin copy
      void opsNotifier.announce(serverId, eventForState(state)).catch((error) => {
        logger.error({ err: error, serverId, state, previous }, "state-change notification failed")
      })
    },
  })
  const gateway: Gateway = createGateway(config, logger, handlers)

  const counters = new SessionCounters()
  const statsService = new StatsService({ startedAt, gateway, directory, counters })

  // shared notification engine: rate-limited broadcast -> dm users with linked
  // telegram accounts + admin chat copies. without a bot token everything
  // degrades to log lines instead of crashing.
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

  // the deletion pipeline needs raw bot api access for the group kick
  const kickApi = () => {
    if (config.botToken === undefined) throw new Error("telegram bot disabled, cannot kick")
    return new Bot(config.botToken).api
  }
  const deletion = new AccountDeletionService({
    users: new MongoUserRepository(),
    links: new MongoTelegramLinkRepository(),
    sessions: new MongoSessionRepository(),
    authRequests: new MongoAuthRequestRepository(),
    audit: new MongoAuditRepository(),
    group: new BotApiTelegramGroupPort(
      {
        banChatMember: (chatId, userId) => kickApi().banChatMember(chatId, userId),
        unbanChatMember: (chatId, userId, options) => kickApi().unbanChatMember(chatId, userId, options),
      },
      config.telegramGroupChatId,
    ),
    sessionRevoker: new GatewaySessionRevoker(gateway),
    notifier: new FanoutAdapter(notifications, config.adminTelegramIds),
    logger,
  })

  registerAccountHandlers(handlers, { accounts, verifier, logger })

  // retention: the backend owns the database, so it does the housekeeping.
  // audit/server_events 90d, closed sessions 60d, orphaned guest snapshots —
  // atlas m0 has 512mb and dead rows add up fast
  const retention = new RetentionService({
    audit: new MongoAuditLogPurge(),
    serverEvents: new MongoServerEventPurge(),
    sessions: new MongoSessionPurge(),
    snapshots: new MongoGuestSnapshotPurge(),
    options: {
      auditDays: config.retentionAuditDays,
      sessionDays: config.retentionSessionDays,
      initialDelayMs: config.retentionInitialDelayMinutes * 60_000,
    },
    logger,
  })
  const stopRetention = retention.start()

  const bot: Bot | null = createBot(config, logger, {
    accounts,
    deletion,
    defaultLanguage: config.opsLocale,
    adminTelegramIds: config.adminTelegramIds,
    stats: statsService,
    collectionStats: databaseReady() ? new MongoCollectionStats() : null,
  })
  // grammy api is ready the moment the Bot exists, swap the real sender in
  notifications.attachSender(bot !== null ? new GrammySender(bot.api) : null)

  // crash detection: ws gone for N minutes => presumed offline
  const offlineWatcher = new OfflineWatcher({
    directory,
    gateway: { get connectedServers() { return gateway.connectedServers } },
    logger,
    thresholdMs: config.serverOfflineAlertMinutes * 60_000,
  })
  offlineWatcher.onReconnect = (serverId) => opsNotifier.forget(serverId)
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
      onStart: (me) => logger.info({ username: me.username }, "bot polling started"),
    })
  }

  logger.info({ env: config.env }, "servercore backend started")

  let shuttingDown = false
  async function shutdown(signal: string): Promise<void> {
    if (shuttingDown) return
    shuttingDown = true
    logger.info({ signal }, "shutting down")
    try {
      offlineWatcher.stop()
      stopRetention()
      await bot?.stop()
      gateway.stop()
      await app.close()
      await disconnectDatabase(logger)
    } catch (err) {
      logger.error({ err }, "error during shutdown")
    }
    process.exit(0)
  }

  process.once("SIGINT", () => void shutdown("SIGINT"))
  process.once("SIGTERM", () => void shutdown("SIGTERM"))
}

main().catch((err) => {
  console.error("[servercore] fatal startup error:", err)
  process.exit(1)
})
