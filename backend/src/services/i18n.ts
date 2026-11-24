/**
 * tiny key-map i18n for user-facing texts. locales are ru (default) and en,
 * keys are dot-namespaced strings like "ops.server.offline". every entry must
 * exist in both locales - a missing translation is a programming error and
 * falls back to the key itself so the gap is visible in output.
 *
 * params use {name} placeholders and are substituted in call order-agnostic
 * fashion by exact name.
 */
export const LOCALES = ["ru", "en"] as const

export type Locale = (typeof LOCALES)[number]

export const DEFAULT_LOCALE: Locale = "ru"

const messages = {
  // ops events, sent to every user with a linked telegram account (and copied
  // to the admin chat)
  "ops.server.starting": {
    ru: "🖥 Сервер запускается…",
    en: "🖥 The server is starting up…",
  },
  "ops.server.running": {
    ru: "✅ Сервер запущен и работает. Приятной игры!",
    en: "✅ The server is up and running. Have fun!",
  },
  "ops.server.restarting": {
    ru: "🔄 Сервер перезапускается. Он снова будет доступен через пару минут.",
    en: "🔄 The server is restarting. It will be back in a couple of minutes.",
  },
  "ops.server.restarting_scheduled": {
    ru: "🕒 Запланированный перезапуск сервера. Он снова будет доступен через пару минут.",
    en: "🕒 Scheduled server restart. It will be back in a couple of minutes.",
  },
  "ops.server.stopping": {
    ru: "🛑 Сервер останавливается.",
    en: "🛑 The server is stopping.",
  },
  "ops.server.offline": {
    ru: "⚠ Сервер сейчас недоступен. Мы уже разбираемся.",
    en: "⚠ The server is currently unreachable. We are on it.",
  },
  "ops.server.update_ready": {
    ru: "⬆ Установлено обновление сервера — сейчас он перезапустится.",
    en: "⬆ A server update was installed — the server is restarting now.",
  },
  // admin-only alerts
  "ops.admin.offline_alert": {
    ru: "⚠ Сервер {server} не выходил на связь уже {minutes} мин — возможно, краш.",
    en: "⚠ Server {server} has not been seen for {minutes} min — possible crash.",
  },
  "ops.admin.server_back": {
    ru: "✅ Сервер {server} снова на связи.",
    en: "✅ Server {server} is back online.",
  },
  // /stats admin command
  "ops.stats.uptime": {
    ru: "⏱ Аптайм бэкенда: {uptime}",
    en: "⏱ Backend uptime: {uptime}",
  },
  "ops.stats.ws": {
    ru: "🔌 WebSocket: {state}, подключений: {connections}",
    en: "🔌 WebSocket: {state}, connections: {connections}",
  },
  "ops.stats.ws_up": {
    ru: "работает",
    en: "up",
  },
  "ops.stats.ws_down": {
    ru: "остановлен",
    en: "stopped",
  },
  "ops.stats.sessions": {
    ru: "👥 Сессии: активных {active}, гостевых {guests}, ожидают авторизации {pending}",
    en: "👥 Sessions: active {active}, guest {guests}, pending auth {pending}",
  },
  "ops.stats.no_servers": {
    ru: "🎮 Игровые серверы: ни одного подключения",
    en: "🎮 Game servers: none connected",
  },
  "ops.stats.server": {
    ru: "🎮 {server}: {state}, игроки {players}, связь {seen} с назад",
    en: "🎮 {server}: {state}, players {players}, last seen {seen}s ago",
  },
  "ops.stats.state_unknown": {
    ru: "состояние неизвестно",
    en: "state unknown",
  },
} satisfies Record<string, Record<Locale, string>>

export type MessageKey = keyof typeof messages

export function isLocale(value: string): value is Locale {
  return (LOCALES as readonly string[]).includes(value)
}

/**
 * resolves a message for the locale. params interpolate {name} placeholders;
 * unknown keys return the key itself so a missing translation is loud.
 */
export function t(
  locale: Locale,
  key: MessageKey,
  params: Record<string, string | number> = {},
): string {
  const entry = messages[key]
  if (!entry) return key
  let text: string = entry[locale] ?? entry[DEFAULT_LOCALE]
  for (const [name, value] of Object.entries(params)) {
    text = text.replaceAll(`{${name}}`, String(value))
  }
  return text
}
