import pino from "pino"
import type { AppConfig } from "../config/env.js"
import { pinoTimestampFor } from "../utils/time.js"

export type Logger = pino.Logger

// plain json on stdout, no transports: pino's transport subsystem runs on
// node worker_threads + thread-stream and is unreliable under bun.
// dev pretty-printing is a piping job: `bun run dev | bunx pino-pretty`
// timestamps render in the project zone (europe/moscow) as +03:00 iso.
export function createLogger(config: AppConfig): Logger {
  return pino({
    level: config.logLevel,
    base: { app: "servercore-backend", env: config.env },
    timestamp: pinoTimestampFor(config.logTimezone),
  })
}
