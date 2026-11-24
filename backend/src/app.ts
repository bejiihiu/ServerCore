import type { Bot } from "grammy"
import { loadConfig, ConfigError as EnvConfigError } from "./config/env.js"
import type { AppConfig } from "./config/env.js"
import { createLogger } from "./logging/logger.js"
import type { Logger } from "./logging/logger.js"
import { connectMongo, type MongoHandle } from "./db/mongo.js"
import { buildHttpServer } from "./http/server.js"
import { createGateway, type Gateway } from "./websocket/gateway.js"
import { HandlerRegistry } from "./websocket/handlers/registry.js"
import { registerLifecycleHandlers, ServerDirectory } from "./websocket/handlers/server-lifecycle.js"
import { createBot } from "./bot/bot.js"
import { LinkedTelegramRecipients } from "./services/recipients.js"
import { NotificationService } from "./services/notification-service.js"
import { GrammySender } from "./services/telegram-sender.js"
import { OpsNotifier, eventForState } from "./services/ops-notifier.js"
import { OfflineWatcher } from "./services/offline-watcher.js"
import { SessionCounters, StatsService } from "./services/runtime-stats.js"
import { AuthenticationService } from "./modules/authentication/auth-service.js"
import { MongoAuthenticationStore } from "./modules/authentication/mongo-store.js"
import { TelegramAuthUi } from "./modules/authentication/telegram-prompt.js"
import { registerAuthCallbacks } from "./modules/authentication/bot-callbacks.js"
import { RetentionService, mongoCollectionSizes } from "./modules/authentication/maintenance.js"
import { AuthRequestModel } from "./database/models/auth-request.js"
import { PlayerSessionModel } from "./database/models/session.js"

/** how long a queued backend->plugin push waits for the link to come back */
const OUTBOX_GIVE_UP_MS = 10 * 60_000

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

  const mongo: MongoHandle = await connectMongo(config, logger)

  const handlers = new HandlerRegistry(logger)
  const directory = new ServerDirectory()
  // assigned below, before any message can possibly arrive; the hook only
  // dereferences it at runtime
  let opsNotifier: OpsNotifier

  // authentication module: session lifecycle + telegram login flow.
  // the gateway push fails closed: a dead game-server link parks the message
  // in the service outbox and the sweeper retries it.
  const authStore = new MongoAuthenticationStore()
  const authUi = new TelegramAuthUi({
    api: null,
    logger,
    defaultLocale: config.opsLocale,
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
      rateLimitWindowMs: config.authRateLimitWindowSeconds * 1000,
      maxRequestsPerPlayer: config.authRateLimitRequestsPerPlayer,
      maxRequestsPerTelegram: config.authRateLimitRequestsPerTelegram,
      maxApprovalsPerTelegram: config.authRateLimitApprovalsPerTelegram,
    },
    logger,
    push: async (serverId, type, payload, correlationId) => {
      try {
        await gateway.sendTo(serverId, type, payload, { correlationId, awaitAck: true })
        return true
      } catch (error) {
        logger.debug({ err: error, serverId, type }, "auth push not delivered, staying in outbox")
        return false
      }
    },
    sendPrompt: (request) => authUi.send(request),
  })

  registerLifecycleHandlers(handlers, directory, logger, {
    onStateChange: (serverId, state, previous) => {
      // crash/status events flow to telegram: all events, dm + admin copy
      void opsNotifier.announce(serverId, eventForState(state)).catch((error) => {
        logger.error({ err: error, serverId, state, previous }, "state-change notification failed")
      })
    },
    onPlayerJoined: (event) => {
      void authService.handlePlayerJoined(event, Date.now()).catch((error) => {
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
      void authService.handleGuestEnded(event, Date.now()).catch((error) => {
        logger.error({ err: error, uuid: event.minecraftUuid }, "guest end handling failed")
      })
    },
  })
  const gateway: Gateway = createGateway(config, logger, handlers)

  const counters = new SessionCounters()
  const statsService = new StatsService({
    startedAt,
    gateway,
    directory,
    counters,
    collectionSizes: mongoCollectionSizes([PlayerSessionModel, AuthRequestModel]),
  })

  // ops plumbing: rate-limited broadcast -> dm users with linked telegram
  // accounts + admin chat copies. without a bot token everything degrades to
  // log lines instead of crashing.
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

  const bot: Bot | null = createBot(
    config,
    logger,
    { stats: statsService },
    { registerCallbacks: (b) => registerAuthCallbacks(b, { auth: authService, ui: authUi, logger, defaultLocale: config.opsLocale }) },
  )
  // grammy api is ready the moment the Bot exists, swap the real senders in
  const api = bot !== null ? bot.api : null
  notifications.attachSender(api !== null ? new GrammySender(api) : null)
  authUi.attachApi(api)

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
      mongoConnected: mongo.ready,
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

  // daily data hygiene: rolling retention over closed sessions
  const retention = new RetentionService({
    store: authStore,
    config: { sessionRetentionDays: 60 },
    logger,
  })
  const DAY_MS = 86_400_000
  const retentionTimer = setInterval(
    () => {
      void retention.run().catch((error) => logger.error({ err: error }, "retention purge failed"))
    },
    DAY_MS,
  )
  // first purge happens shortly after boot so an existing backlog drains
  setTimeout(() => {
    void retention.run().catch((error) => logger.error({ err: error }, "retention purge failed"))
  }, 30_000)

  // db indexes for the auth collections, synced once mongo is reachable
  void syncAuthIndexes(mongo, logger)

  logger.info({ env: config.env }, "servercore backend started")

  let shuttingDown = false
  async function shutdown(signal: string): Promise<void> {
    if (shuttingDown) return
    shuttingDown = true
    logger.info({ signal }, "shutting down")
    try {
      sweeper.unref()
      retentionTimer.unref()
      authService.stop()
      offlineWatcher.stop()
      await bot?.stop()
      gateway.stop()
      await app.close()
      await mongo.disconnect()
    } catch (err) {
      logger.error({ err }, "error during shutdown")
    }
    process.exit(0)
  }

  process.once("SIGINT", () => void shutdown("SIGINT"))
  process.once("SIGTERM", () => void shutdown("SIGTERM"))
}

/** waits for mongo in the background, then syncs the auth collection indexes */
async function syncAuthIndexes(mongo: MongoHandle, logger: Logger): Promise<void> {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    if (mongo.ready) break
    await new Promise((resolve) => setTimeout(resolve, 2_000))
  }
  if (!mongo.ready) {
    logger.warn("mongo never became ready, auth indexes not synced")
    return
  }
  try {
    await PlayerSessionModel.syncIndexes()
    await AuthRequestModel.syncIndexes()
    logger.info("auth collection indexes synced")
  } catch (err) {
    logger.error({ err }, "auth index sync failed")
  }
}

main().catch((err) => {
  console.error("[servercore] fatal startup error:", err)
  process.exit(1)
})
