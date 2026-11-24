import mongoose from "mongoose"
import {
  ApplicationModel,
  AuditLogModel,
  AuthRequestModel,
  GuestSnapshotModel,
  PlayerSessionModel,
  PunishmentExternalModel,
  ServerEventModel,
  TelegramLinkModel,
  UserModel,
  VoteModel,
} from "../database/models/index.js"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"

export type MongoHandle = {
  /** true while the underlying connection is in readyState connected */
  readonly ready: boolean
  disconnect(): Promise<void>
}

const RETRY_BASE_MS = 1_000
const RETRY_MAX_MS = 30_000

let retrying = false

export async function connectMongo(config: AppConfig, logger: Logger): Promise<MongoHandle> {
  // index sync is explicit, not per-boot magic: models run their own
  // syncIndexes() from boot code when they actually ship
  mongoose.set("autoIndex", false)

  mongoose.connection.on("error", (err) => logger.error({ err }, "mongo connection error"))
  mongoose.connection.on("disconnected", () => logger.warn("mongo disconnected"))

  // boot proceeds even if mongo is unreachable: health flips to 503 degraded
  // and we keep retrying in the background. crash-looping on a transient db
  // blip is worse than serving degraded
  retrying = true
  void connectWithRetry(config, logger)

  return {
    get ready(): boolean {
      return mongoose.connection.readyState === 1
    },
    async disconnect(): Promise<void> {
      retrying = false
      await mongoose.disconnect()
      logger.info("mongo disconnected")
    },
  }
}

async function connectWithRetry(config: AppConfig, logger: Logger): Promise<void> {
  let attempt = 0
  while (retrying && mongoose.connection.readyState !== 1) {
    attempt += 1
    try {
      await mongoose.connect(config.mongoUri, {
        serverSelectionTimeoutMS: 5_000,
        maxPoolSize: 10,
        appName: "servercore-backend",
        // atlas in production: tls is implicit for mongodb+srv uris, and
        // retryWrites keeps transient primary elections from failing writes
        retryWrites: true,
      })
      logger.info({ host: mongoose.connection.host, name: mongoose.connection.name, attempt }, "mongo connected")
      await syncIndexesWithTolerance(logger)
      return
    } catch (err) {
      const delayMs = Math.min(RETRY_BASE_MS * 2 ** (attempt - 1), RETRY_MAX_MS)
      logger.warn({ err, attempt, nextRetryMs: delayMs }, "mongo connect failed, retrying")
      await bunDelay(delayMs)
    }
  }
}

/**
 * creates missing indexes on boot. tolerant by design: on atlas a connection
 * without index-creation rights must not take the backend down — the unique
 * constraints get created by an admin run once and this call becomes a no-op.
 */
export async function syncIndexesWithTolerance(logger: Logger): Promise<void> {
  const models = [
    UserModel,
    ApplicationModel,
    VoteModel,
    PlayerSessionModel,
    GuestSnapshotModel,
    AuthRequestModel,
    TelegramLinkModel,
    PunishmentExternalModel,
    AuditLogModel,
    ServerEventModel,
  ]
  for (const model of models) {
    try {
      await model.syncIndexes()
    } catch (err: unknown) {
      logger.warn({ err, collection: model.collection.name }, "index sync failed, continuing without it")
    }
  }
}

function bunDelay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

// note for whoever adds models in here:
// - ttl only works on single-field indexes, never compound. need a compound
//   unique constraint plus expiry? separate Date field with its own single-field
//   ttl index, and the compound unique index next to it.
// - mongoose under bun has a known slow heap-growth issue in long-lived
//   processes (mongoose #16228, oven-sh/bun #12117). keep queries short-lived,
//   avoid unbounded cursors and long-lived doc references, watch rss in prod.
//   fallback plan if it bites: drop mongoose and go straight to the mongodb
//   driver - models live in this folder, so the swap stays local.
