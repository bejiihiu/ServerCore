import type { Bot } from "grammy"
import type { BotContext } from "./bot/context.js"
import { loadConfig, ConfigError as EnvConfigError } from "./config/env.js"
import type { AppConfig } from "./config/env.js"
import { createLogger } from "./logging/logger.js"
import type { Logger } from "./logging/logger.js"
import { connectMongo, type MongoHandle } from "./db/mongo.js"
import { buildHttpServer } from "./http/server.js"
import { createGateway, type Gateway } from "./websocket/gateway.js"
import { HandlerRegistry } from "./websocket/handlers/registry.js"
import { registerLifecycleHandlers, ServerDirectory } from "./websocket/handlers/server-lifecycle.js"
import { createAdmissionBot } from "./bot/admission-bot.js"
import { createAdmissionRepositories, syncAdmissionIndexes } from "./database/repos.js"
import { VotingScheduler } from "./services/voting-scheduler.js"

// chat_member events must be requested explicitly, telegram does not send
// them by default
const ALLOWED_UPDATES = ["message", "callback_query", "chat_member", "my_chat_member"] as const

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

  const repos = createAdmissionRepositories()

  const admission = createAdmissionBot(config, logger, repos)
  const bot: Bot<BotContext> | null = admission?.bot ?? null

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

  if (mongo.ready) {
    try {
      await syncAdmissionIndexes()
      logger.info("admission indexes synced")
    } catch (err) {
      logger.warn({ err }, "admission index sync failed, continuing")
    }
  } else {
    logger.warn("mongo not ready at boot, admission indexes not synced")
  }

  if (bot !== null) {
    void bot.start({
      allowed_updates: ALLOWED_UPDATES,
      onStart: (me) => logger.info({ username: me.username }, "bot polling started"),
    })
    scheduler?.start()
  } else {
    logger.warn("admission flow inactive: bot is disabled")
  }

  logger.info({ env: config.env }, "servercore backend started")

  let shuttingDown = false
  async function shutdown(signal: string): Promise<void> {
    if (shuttingDown) return
    shuttingDown = true
    logger.info({ signal }, "shutting down")
    try {
      scheduler?.stop()
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
