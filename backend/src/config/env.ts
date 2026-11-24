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

  MONGODB_URI: z.string().min(1).default("mongodb://localhost:27017/servercore"),
  BOT_TOKEN: z.string().min(1).optional(),
  TELEGRAM_BOT_USERNAME: z.string().min(1).default("ServerCoreBot"),
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

  // pretty name of the game server shown in telegram prompts
  SERVER_NAME: z.string().min(1).max(64).default("ServerCore"),

  // sliding window rate limits for auth request issuance and approvals
  AUTH_RATE_LIMIT_WINDOW_SECONDS: z.coerce.number().int().min(10).max(3600).default(300),
  AUTH_RATE_LIMIT_REQUESTS_PER_PLAYER: z.coerce.number().int().min(1).max(100).default(5),
  AUTH_RATE_LIMIT_REQUESTS_PER_TELEGRAM: z.coerce.number().int().min(1).max(100).default(10),
  AUTH_RATE_LIMIT_APPROVALS_PER_TELEGRAM: z.coerce.number().int().min(1).max(200).default(30),

  // telegram message effect for the auth prompt, unset = no effect
  AUTH_MESSAGE_EFFECT_ID: z.string().min(1).max(64).optional(),

  // how often pending auth requests are swept for expiry and delivery retries
  AUTH_SWEEP_INTERVAL_MS: z.coerce.number().int().min(250).max(60_000).default(1_000),

  // custom emoji entities need telegram premium on the bot owner (bot api 9.4);
  // off by default, everything degrades to unicode fallbacks
  BOT_CUSTOM_EMOJI: z.enum(["true", "false"]).default("false"),
})

export type AppConfig = {
  env: "development" | "production" | "test"
  port: number
  host: string
  wsPort: number
  mongoUri: string
  botToken?: string
  telegramBotUsername: string
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
  serverName: string
  authRateLimitWindowSeconds: number
  authRateLimitRequestsPerPlayer: number
  authRateLimitRequestsPerTelegram: number
  authRateLimitApprovalsPerTelegram: number
  authMessageEffectId?: string
  authSweepIntervalMs: number
  botCustomEmoji: boolean
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
    serverName: result.data.SERVER_NAME,
    authRateLimitWindowSeconds: result.data.AUTH_RATE_LIMIT_WINDOW_SECONDS,
    authRateLimitRequestsPerPlayer: result.data.AUTH_RATE_LIMIT_REQUESTS_PER_PLAYER,
    authRateLimitRequestsPerTelegram: result.data.AUTH_RATE_LIMIT_REQUESTS_PER_TELEGRAM,
    authRateLimitApprovalsPerTelegram: result.data.AUTH_RATE_LIMIT_APPROVALS_PER_TELEGRAM,
    authMessageEffectId: result.data.AUTH_MESSAGE_EFFECT_ID,
    authSweepIntervalMs: result.data.AUTH_SWEEP_INTERVAL_MS,
    botCustomEmoji: result.data.BOT_CUSTOM_EMOJI === "true",
  }
}
