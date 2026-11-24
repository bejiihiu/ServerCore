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

  // closed telegram group; empty until the group exists (kick needs it)
  TELEGRAM_GROUP_CHAT_ID: z.string().default(""),

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

  // ops: telegram admin ids (comma separated), get event copies + /stats
  ADMIN_TELEGRAM_IDS: z.string().default(""),
  // locale for ops notifications ("ru" | "en")
  OPS_LOCALE: z.enum(["ru", "en"]).default("ru"),
  // ws silent for this long => server presumed offline, admins alerted
  SERVER_OFFLINE_ALERT_MINUTES: z.coerce.number().int().min(1).max(1440).default(5),
  // crash alert also goes to all linked users, not just admins
  NOTIFY_USERS_ON_OFFLINE: z
    .string()
    .default("false")
    .transform((raw) => ["1", "true", "yes", "on"].includes(raw.trim().toLowerCase())),
  // outbound broadcast rate ceiling (telegram allows ~30/s)
  NOTIFICATION_RATE_PER_SECOND: z.coerce.number().int().min(1).max(30).default(30),

  // atlas m0 housekeeping: audit/server_events lifetime and closed-session
  // lifetime in days; the daily cleanup task enforces both
  RETENTION_AUDIT_DAYS: z.coerce.number().int().min(7).default(90),
  RETENTION_SESSION_DAYS: z.coerce.number().int().min(7).default(60),
  // first cleanup pass after boot, minutes (then daily)
  RETENTION_INITIAL_DELAY_MINUTES: z.coerce.number().int().min(1).default(5),
})

export type AppConfig = {
  env: "development" | "production" | "test"
  port: number
  host: string
  wsPort: number
  mongoUri: string
  botToken?: string
  telegramBotUsername: string
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
  adminTelegramIds: number[]
  opsLocale: "ru" | "en"
  serverOfflineAlertMinutes: number
  notifyUsersOnOffline: boolean
  notificationRatePerSecond: number
  retentionAuditDays: number
  retentionSessionDays: number
  retentionInitialDelayMinutes: number
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

/** "123, 456" -> [123, 456]; empty string means no admins configured */
function parseAdminIds(raw: string): number[] {
  return raw
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
    .map((part) => Number.parseInt(part, 10))
    .filter((id) => Number.isInteger(id) && id > 0)
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
    telegramGroupChatId: result.data.TELEGRAM_GROUP_CHAT_ID,
    defaultLanguage: result.data.OPS_LOCALE,
    logTimezone: result.data.LOG_TIMEZONE,
    logLevel: result.data.LOG_LEVEL,
    serverCredentials: parseServerCredentials(result.data.SERVERCORE_SERVER_CREDENTIALS),
    wsHeartbeatIntervalMs: result.data.WS_HEARTBEAT_INTERVAL_MS,
    wsHeartbeatTimeoutMs: result.data.WS_HEARTBEAT_TIMEOUT_MS,
    wsMaxPacketBytes: result.data.WS_MAX_PACKET_BYTES,
    wsReplayWindowMs: result.data.WS_REPLAY_WINDOW_MS,
    wsNonceTtlMs: result.data.WS_NONCE_TTL_MS,
    authRequestTtlSeconds: result.data.AUTH_REQUEST_TTL_SECONDS,
    adminTelegramIds: parseAdminIds(result.data.ADMIN_TELEGRAM_IDS),
    opsLocale: result.data.OPS_LOCALE,
    serverOfflineAlertMinutes: result.data.SERVER_OFFLINE_ALERT_MINUTES,
    notifyUsersOnOffline: result.data.NOTIFY_USERS_ON_OFFLINE,
    notificationRatePerSecond: result.data.NOTIFICATION_RATE_PER_SECOND,
    retentionAuditDays: result.data.RETENTION_AUDIT_DAYS,
    retentionSessionDays: result.data.RETENTION_SESSION_DAYS,
    retentionInitialDelayMinutes: result.data.RETENTION_INITIAL_DELAY_MINUTES,
  }
}
