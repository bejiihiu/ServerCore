import { Bot as GrammyBot } from "grammy"
import { loadConfig, ConfigError as EnvConfigError } from "./config/env.js"
import type { AppConfig } from "./config/env.js"
import { createLogger } from "./logging/logger.js"
import type { Logger } from "./logging/logger.js"
import { connectMongo, type MongoHandle } from "./db/mongo.js"
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
import { NotificationService, TelegramNotificationPort, LogOnlyNotificationPort } from "./modules/notification/notification-service.js"
import { createBot } from "./bot/bot.js"
import type { BotDeps } from "./bot/context.js"

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
  registerLifecycleHandlers(handlers, directory, logger)
  const gateway: Gateway = createGateway(config, logger, handlers)

  // account module wiring: token service on a process-local store for now
  // (single instance), repositories on mongo
  const tokenStore = new InMemoryTokenStore()
  const tokens = new TokenService(tokenStore, config.authRequestTtlSeconds)
  const verifier = new GatewayOwnershipVerifier(gateway, logger)

  // one bot api handle for transports; the routed bot (below) is separate
  const botApi = config.botToken !== undefined ? new GrammyBot(config.botToken).api : null
  const notificationPort = botApi !== null
    ? new TelegramNotificationPort({ sendMessage: (chatId, text) => botApi.sendMessage(chatId, text) })
    : new LogOnlyNotificationPort(logger)
  const notifier = new NotificationService(notificationPort, config.adminChatIds, logger)

  const accounts = new AccountService({
    users: new MongoUserRepository(),
    links: new MongoTelegramLinkRepository(),
    audit: new MongoAuditRepository(),
    tokens,
    ownership: verifier,
    notifier,
    logger,
    defaultLanguage: config.defaultLanguage,
  })

  const deletion = new AccountDeletionService({
    users: new MongoUserRepository(),
    links: new MongoTelegramLinkRepository(),
    sessions: new MongoSessionRepository(),
    authRequests: new MongoAuthRequestRepository(),
    audit: new MongoAuditRepository(),
    group: new BotApiTelegramGroupPort(
      {
        banChatMember: (chatId, userId) => {
          if (botApi === null) throw new Error("telegram bot disabled, cannot kick")
          return botApi.banChatMember(chatId, userId)
        },
        unbanChatMember: (chatId, userId, options) => {
          if (botApi === null) throw new Error("telegram bot disabled, cannot kick")
          return botApi.unbanChatMember(chatId, userId, options)
        },
      },
      config.telegramGroupChatId,
    ),
    sessionRevoker: new GatewaySessionRevoker(gateway),
    notifier,
    logger,
  })

  registerAccountHandlers(handlers, { accounts, verifier, logger })

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

  const botDeps: BotDeps = {
    accounts,
    deletion,
    defaultLanguage: config.defaultLanguage,
  }
  const bot = createBot(config, logger, botDeps)
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

main().catch((err) => {
  console.error("[servercore] fatal startup error:", err)
  process.exit(1)
})
