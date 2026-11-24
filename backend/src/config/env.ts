import { z } from "zod"

const serverCredentialsSchema = z.record(
  z.string().min(1).max(64).regex(/^[a-z0-9._-]+$/),
  z.string().min(32).max(256),
)

const envSchema = z.object({
  NODE_ENV: z.enum(["development", "production", "test"]).default("development"),
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  HOST: z.string().min(1).default("0.0.0.0"),

  // plugin websocket gateway runs on its own port, separate from fastify http
  WS_PORT: z.coerce.number().int().min(1).max(65535).default(8081),

  // atlas in production: mongodb+srv://... (tls is implicit for srv); local
  // dev keeps the plain mongodb:// form. retryWrites is always enabled below
  MONGODB_URI: z.string().min(1).default("mongodb://localhost:27017/servercore"),
  BOT_TOKEN: z.string().min(1).optional(),
  TELEGRAM_BOT_USERNAME: z.string().min(1).default("ServerCoreBot"),

  // comma-separated telegram chat ids receiving duplicates of key events
  ADMIN_CHAT_IDS: z.string().default(""),
  // closed telegram group; empty until the group exists (kick needs it)
  TELEGRAM_GROUP_CHAT_ID: z.string().default(""),

  // default bot language for users without a stored preference
  DEFAULT_LANGUAGE: z.enum(["ru", "en"]).default("ru"),
  // logs and schedules render in this zone (project standard: Europe/Moscow)
  LOG_TIMEZONE: z.string().min(1).default("Europe/Moscow"),

  LOG_LEVEL: z.enum(["trace", "debug", "info", "warn", "error", "fatal"]).default("info"),

  // json object mapping server id -> per-server secret
  SERVERCORE_SERVER_CREDENTIALS: z.string().min(1).default("{}"),

  WS_HEARTBEAT_INTERVAL_MS: z.coerce.number().int().min(1000).default(15_000),
  WS_HEARTBEAT_TIMEOUT_MS: z.coerce.number().int().min(1000).default(10_000),
  WS_MAX_PACKET_BYTES: z.coerce.number().int().min(1024).max(1_048_576).default(262_144),
  WS_REPLAY_WINDOW_MS: z.coerce.number().int().min(1000).default(30_000),
  WS_NONCE_TTL_MS: z.coerce.number().int().min(1000).default(120_000),

  // one-time login/link token lifetime, must stay in the 60-90 band
  AUTH_REQUEST_TTL_SECONDS: z.coerce.number().int().min(60).max(90).default(75),
})

export type AppConfig = {
  env: "development" | "production" | "test"
  port: number
  host: string
  wsPort: number
  mongoUri: string
  botToken?: string
  telegramBotUsername: string
  adminChatIds: string[]
  telegramGroupChatId: string
  defaultLanguage: "ru" | "en"
  logTimezone: string
  logLevel: "trace" | "debug" | "info" | "warn" | "error" | "fatal"
  serverCredentials: Record<string, string>
  wsHeartbeatIntervalMs: number
  wsHeartbeatTimeoutMs: number
  wsMaxPacketBytes: number
  wsReplayWindowMs: number
  wsNonceTtlMs: number
  authRequestTtlSeconds: number
}

export class ConfigError extends Error {
  readonly issues: string[]

  constructor(issues: string[]) {
    super(`invalid configuration: ${issues.join("; ")}`)
    this.name = "ConfigError"
    this.issues = issues
  }
}

function parseServerCredentials(raw: string): Record<string, string> {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw) as unknown
  } catch {
    throw new ConfigError(["SERVERCORE_SERVER_CREDENTIALS is not valid json"])
  }
  const result = serverCredentialsSchema.safeParse(parsed)
  if (!result.success) {
    throw new ConfigError([`SERVERCORE_SERVER_CREDENTIALS: ${result.error.message}`])
  }
  return result.data
}

function parseChatIds(raw: string): string[] {
  return raw
    .split(",")
    .map((id) => id.trim())
    .filter((id) => id.length > 0)
}

export function loadConfig(env: Record<string, string | undefined> = process.env): AppConfig {
  const result = envSchema.safeParse(env)
  if (!result.success) {
    throw new ConfigError(result.error.issues.map((issue) => `${issue.path.join(".")}: ${issue.message}`))
  }

  return {
    env: result.data.NODE_ENV,
    port: result.data.PORT,
    host: result.data.HOST,
    wsPort: result.data.WS_PORT,
    mongoUri: result.data.MONGODB_URI,
    botToken: result.data.BOT_TOKEN,
    telegramBotUsername: result.data.TELEGRAM_BOT_USERNAME,
    adminChatIds: parseChatIds(result.data.ADMIN_CHAT_IDS),
    telegramGroupChatId: result.data.TELEGRAM_GROUP_CHAT_ID,
    defaultLanguage: result.data.DEFAULT_LANGUAGE,
    logTimezone: result.data.LOG_TIMEZONE,
    logLevel: result.data.LOG_LEVEL,
    serverCredentials: parseServerCredentials(result.data.SERVERCORE_SERVER_CREDENTIALS),
    wsHeartbeatIntervalMs: result.data.WS_HEARTBEAT_INTERVAL_MS,
    wsHeartbeatTimeoutMs: result.data.WS_HEARTBEAT_TIMEOUT_MS,
    wsMaxPacketBytes: result.data.WS_MAX_PACKET_BYTES,
    wsReplayWindowMs: result.data.WS_REPLAY_WINDOW_MS,
    wsNonceTtlMs: result.data.WS_NONCE_TTL_MS,
    authRequestTtlSeconds: result.data.AUTH_REQUEST_TTL_SECONDS,
  }
}
