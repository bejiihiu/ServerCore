import { z } from "zod"
import { MOSCOW_TZ, MS_PER_HOUR, MS_PER_MINUTE } from "../utils/time.js"

const serverCredentialsSchema = z.record(
  z.string().min(1).max(64).regex(/^[a-z0-9._-]+$/),
  z.string().min(32).max(256),
)

/** int field that treats an empty env value as "not configured" */
function optionalInt() {
  return z.preprocess(
    (value) => (typeof value === "string" && value.trim() === "" ? undefined : value),
    z.coerce.number().int().optional(),
  )
}

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

  // closed telegram group; empty until the group exists (kick needs it)
  TELEGRAM_GROUP_CHAT_ID: z.string().default(""),

  // logs and schedules render in this zone (project standard: Europe/Moscow)
  LOG_TIMEZONE: z.string().min(1).default(MOSCOW_TZ),

  LOG_LEVEL: z.enum(["trace", "debug", "info", "warn", "error", "fatal"]).default("info"),

  // closed telegram group the voting runs in and approved members join.
  // empty until the group exists — the group flow degrades gracefully
  GROUP_CHAT_ID: optionalInt(),

  // questionnaire questions as json arrays, one entry per question.
  // ru and en lists must have the same length
  APPLICATION_QUESTIONS_RU: z.string().default(
    JSON.stringify([
      "Как давно играешь в Minecraft?",
      "Почему хочешь присоединиться?",
      "Как часто планируешь играть?",
      "Есть ли опыт игры на подобных серверах?",
    ]),
  ),
  APPLICATION_QUESTIONS_EN: z.string().default(
    JSON.stringify([
      "How long have you been playing Minecraft?",
      "Why do you want to join?",
      "How often do you plan to play?",
      "Do you have experience on similar servers?",
    ]),
  ),

  // voting window, the spec value is 3 hours
  VOTING_DURATION_MINUTES: z.coerce.number().int().min(1).default(180),
  // how often the scheduler sweeps for expired votings
  VOTING_SCAN_INTERVAL_MS: z.coerce.number().int().min(1000).default(60_000),

  // one-time group invite lifetime
  GROUP_INVITE_TTL_HOURS: z.coerce.number().int().min(1).max(168).default(48),

  // vote button icons via icon_custom_emoji_id (bot api 9.4). needs telegram
  // premium on the bot owner — default off until confirmed, api degrades to
  // plain buttons on failure. boolean envs go through the truthy-string
  // transform: z.coerce.boolean would read "false" as true
  VOTE_BUTTON_ICONS_ENABLED: z
    .string()
    .default("false")
    .transform((raw) => ["1", "true", "yes", "on"].includes(raw.trim().toLowerCase())),
  VOTE_BUTTON_ICON_ACCEPT_ID: z.string().default(""),
  VOTE_BUTTON_ICON_REJECT_ID: z.string().default(""),

  // message effect (bot api 7.4) played on the approval celebration message
  APPROVAL_MESSAGE_EFFECT_ID: z.string().default("5104841245759776310"),

  // application starts per rate-limit window per telegram account
  APPLICATION_START_LIMIT: z.coerce.number().int().min(1).default(3),
  APPLICATION_START_WINDOW_MS: z.coerce.number().int().min(1000).default(60_000),

  // json object mapping server id -> per-server secret
  SERVERCORE_SERVER_CREDENTIALS: z.string().min(1).default("{}"),

  WS_HEARTBEAT_INTERVAL_MS: z.coerce.number().int().min(1000).default(15_000),
  WS_HEARTBEAT_TIMEOUT_MS: z.coerce.number().int().min(1000).default(10_000),
  WS_MAX_PACKET_BYTES: z.coerce.number().int().min(1024).max(1_048_576).default(262_144),
  WS_REPLAY_WINDOW_MS: z.coerce.number().int().min(1000).default(30_000),
  WS_NONCE_TTL_MS: z.coerce.number().int().min(1000).default(120_000),
  // graceful shutdown: in-flight handler waits get this long before sockets close
  WS_DRAIN_TIMEOUT_MS: z.coerce.number().int().min(100).default(5_000),

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
  telegramGroupChatId: string
  logTimezone: string
  logLevel: "trace" | "debug" | "info" | "warn" | "error" | "fatal"
  serverCredentials: Record<string, string>
  wsHeartbeatIntervalMs: number
  wsHeartbeatTimeoutMs: number
  wsMaxPacketBytes: number
  wsReplayWindowMs: number
  wsNonceTtlMs: number
  wsDrainTimeoutMs: number
  authRequestTtlSeconds: number
  groupChatId: number | null
  /** interface language for group/admin-facing messages, per-user overrides */
  defaultLanguage: "ru" | "en"
  questions: { ru: string[]; en: string[] }
  votingDurationMs: number
  votingScanIntervalMs: number
  groupInviteTtlMs: number
  applicationStartLimit: number
  applicationStartWindowMs: number
  voteButtonIcons: { enabled: boolean; acceptId: string | null; rejectId: string | null }
  approvalEffectId: string | null
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

/** parses a json string array of question texts, with shape validation */
function parseQuestions(raw: string, field: string): string[] {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw) as unknown
  } catch {
    throw new ConfigError([`${field} is not valid json`])
  }
  if (!Array.isArray(parsed) || parsed.length === 0 || parsed.length > 10) {
    throw new ConfigError([`${field} must be a json array with 1..10 questions`])
  }
  const questions = parsed.map((item) => {
    if (typeof item !== "string" || item.trim().length === 0 || item.length > 500) {
      throw new ConfigError([`${field} must contain non-empty strings of at most 500 chars`])
    }
    return item.trim()
  })
  return questions
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

  const questionsRu = parseQuestions(result.data.APPLICATION_QUESTIONS_RU, "APPLICATION_QUESTIONS_RU")
  const questionsEn = parseQuestions(result.data.APPLICATION_QUESTIONS_EN, "APPLICATION_QUESTIONS_EN")
  if (questionsRu.length !== questionsEn.length) {
    throw new ConfigError(["APPLICATION_QUESTIONS_RU and APPLICATION_QUESTIONS_EN must have the same length"])
  }

  return {
    env: result.data.NODE_ENV,
    port: result.data.PORT,
    host: result.data.HOST,
    wsPort: result.data.WS_PORT,
    mongoUri: result.data.MONGODB_URI,
    botToken: result.data.BOT_TOKEN,
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
    wsDrainTimeoutMs: result.data.WS_DRAIN_TIMEOUT_MS,
    authRequestTtlSeconds: result.data.AUTH_REQUEST_TTL_SECONDS,
    groupChatId: result.data.GROUP_CHAT_ID ?? null,
    questions: { ru: questionsRu, en: questionsEn },
    votingDurationMs: result.data.VOTING_DURATION_MINUTES * MS_PER_MINUTE,
    votingScanIntervalMs: result.data.VOTING_SCAN_INTERVAL_MS,
    groupInviteTtlMs: result.data.GROUP_INVITE_TTL_HOURS * MS_PER_HOUR,
    applicationStartLimit: result.data.APPLICATION_START_LIMIT,
    applicationStartWindowMs: result.data.APPLICATION_START_WINDOW_MS,
    voteButtonIcons: {
      enabled: result.data.VOTE_BUTTON_ICONS_ENABLED,
      acceptId: result.data.VOTE_BUTTON_ICON_ACCEPT_ID || null,
      rejectId: result.data.VOTE_BUTTON_ICON_REJECT_ID || null,
    },
    approvalEffectId: result.data.APPROVAL_MESSAGE_EFFECT_ID || null,
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
    retentionAuditDays: result.data.RETENTION_AUDIT_DAYS,
    retentionSessionDays: result.data.RETENTION_SESSION_DAYS,
    retentionInitialDelayMinutes: result.data.RETENTION_INITIAL_DELAY_MINUTES,
  }
}
