import mongoose from "mongoose"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"

let connected = false

/** connects mongoose with production-sane pool settings. idempotent. */
export async function connectDatabase(config: AppConfig, logger: Logger): Promise<typeof mongoose> {
  if (connected) return mongoose

  mongoose.set("strictQuery", true)

  await mongoose.connect(config.mongoUri, {
    maxPoolSize: 10,
    minPoolSize: 2,
    serverSelectionTimeoutMS: 5_000,
    socketTimeoutMS: 30_000,
    retryWrites: true,
    appName: "servercore-backend",
  })

  connected = true
  logger.info({ uri: sanitizeUri(config.mongoUri) }, "mongodb connected")

  mongoose.connection.on("error", (error: unknown) => {
    logger.error({ err: error }, "mongodb connection error")
  })
  mongoose.connection.on("disconnected", () => {
    logger.warn("mongodb disconnected")
  })

  return mongoose
}

export async function disconnectDatabase(logger: Logger): Promise<void> {
  if (!connected) return
  await mongoose.disconnect()
  connected = false
  logger.info("mongodb disconnected")
}

/** connection readyState per the mongoose enum */
export function databaseReady(): boolean {
  // 0 = disconnected, 1 = connected, 2 = connecting, 3 = disconnecting
  return mongoose.connection.readyState === 1
}

/** strips credentials before the uri can end up in a log line */
function sanitizeUri(uri: string): string {
  try {
    const parsed = new URL(uri)
    if (parsed.password) parsed.password = "***"
    if (parsed.username && parsed.username !== "***") parsed.username = "***"
    return parsed.toString()
  } catch {
    return "<unparseable uri>"
  }
}
