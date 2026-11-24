import pino from "pino"
import type { AppConfig } from "../config/env.js"

export type Logger = pino.Logger

// plain json on stdout, no transports: pino's transport subsystem runs on
// node worker_threads + thread-stream and is unreliable under bun.
// dev pretty-printing is a piping job: `bun run dev | bunx pino-pretty`
export function createLogger(config: AppConfig): Logger {
  return pino({
    level: config.logLevel,
    base: { app: "servercore-backend", env: config.env },
  })
}
