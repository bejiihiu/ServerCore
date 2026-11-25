import mongoose from "mongoose"
import {
  ApplicationDraftModel,
  ApplicationModel,
  AuditLogModel,
  AuthRequestModel,
  ConversationStateModel,
  GroupInviteModel,
  GuestSnapshotModel,
  PlayerSessionModel,
  PunishmentExternalModel,
  ServerEventModel,
  TelegramLinkModel,
  UserModel,
  VoteModel,
  VotingMessageModel,
} from "./models/index.js"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"

let connected = false
let retrying = false

const RETRY_BASE_MS = 1_000
const RETRY_MAX_MS = 30_000

/**
 * exponential backoff with a hard cap. one attempt that throws is one failed
 * attempt; the loop never rejects, so a boot with an unreachable database
 * keeps retrying in the background instead of taking the process down.
 *
 * `shouldRetry` is re-evaluated before every attempt: a shutdown flips it and
 * the loop then returns quietly instead of holding the process open.
 */
export async function connectWithBackoff(deps: {
  attempt: () => Promise<void>
  shouldRetry: () => boolean
  logger: Logger
  /** injected in tests so the schedule is asserted without real waiting */
  sleep?: (ms: number) => Promise<void>
  baseDelayMs?: number
  maxDelayMs?: number
}): Promise<{ attempts: number; connected: boolean }> {
  const sleep = deps.sleep ?? delay
  const baseDelayMs = deps.baseDelayMs ?? RETRY_BASE_MS
  const maxDelayMs = deps.maxDelayMs ?? RETRY_MAX_MS
  let attempts = 0

  while (deps.shouldRetry()) {
    attempts += 1
    try {
      await deps.attempt()
      return { attempts, connected: true }
    } catch (err) {
      const delayMs = Math.min(baseDelayMs * 2 ** (attempts - 1), maxDelayMs)
      deps.logger.warn({ err, attempt: attempts, nextRetryMs: delayMs }, "mongo connect failed, retrying")
      await sleep(delayMs)
    }
  }
  return { attempts, connected: false }
}

/**
 * connects mongoose with production-sane pool settings. idempotent. boot
 * proceeds even if mongo is unreachable: health flips to 503 degraded and
 * the connect keeps retrying in the background — crash-looping on a transient
 * db blip is worse than serving degraded.
 */
export async function connectDatabase(config: AppConfig, logger: Logger): Promise<typeof mongoose> {
  if (connected) return mongoose

  mongoose.set("strictQuery", true)
  // index sync is explicit, not per-boot magic beyond this one tolerant pass
  mongoose.set("autoIndex", false)

  mongoose.connection.on("error", (error: unknown) => {
    logger.error({ err: error }, "mongodb connection error")
  })
  mongoose.connection.on("disconnected", () => {
    logger.warn("mongodb disconnected")
  })

  retrying = true
  void connectWithRetry(config, logger)

  connected = true
  return mongoose
}

async function connectWithRetry(config: AppConfig, logger: Logger): Promise<void> {
  await connectWithBackoff({
    logger,
    // stopped either by a shutdown (retrying=false) or by the connection
    // finally coming up (readyState 1)
    shouldRetry: () => retrying && mongoose.connection.readyState !== 1,
    attempt: async () => {
      await mongoose.connect(config.mongoUri, {
        maxPoolSize: 10,
        minPoolSize: 2,
        serverSelectionTimeoutMS: 5_000,
        socketTimeoutMS: 30_000,
        // atlas in production: tls is implicit for mongodb+srv uris, and
        // retryWrites keeps transient primary elections from failing writes
        retryWrites: true,
        appName: "servercore-backend",
      })
      logger.info(
        { host: mongoose.connection.host, name: mongoose.connection.name },
        "mongodb connected",
      )
      await syncIndexesWithTolerance(logger)
    },
  })
}

/**
 * creates missing indexes on boot. tolerant by design: on atlas a connection
 * without index-creation rights must not take the backend down — the unique
 * constraints get created once by an admin and this call becomes a no-op.
 */
export async function syncIndexesWithTolerance(logger: Logger): Promise<void> {
  const models = [
    UserModel,
    ApplicationModel,
    VoteModel,
    // admission-only models: they have no slot in the boot-time sync when mongo
    // is still connecting, this list is what actually creates their indexes
    ApplicationDraftModel,
    VotingMessageModel,
    GroupInviteModel,
    ConversationStateModel,
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

export async function disconnectDatabase(logger: Logger): Promise<void> {
  retrying = false
  if (!connected) {
    await mongoose.disconnect()
    return
  }
  connected = false
  await mongoose.disconnect()
  logger.info("mongodb disconnected")
}

/** connection readyState per the mongoose enum */
export function databaseReady(): boolean {
  // 0 = disconnected, 1 = connected, 2 = connecting, 3 = disconnecting
  return mongoose.connection.readyState === 1
}

function delay(ms: number): Promise<void> {
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
