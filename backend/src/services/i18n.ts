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

  // account module: telegram linking flow, account screen, deletion.
  // user-facing, every key exists in both locales
  "bot.help": {
    ru: "Команды бота:\n\n/status — мой аккаунт и его статус\n/help — эта справка\n\nРегистрация начинается в игре: зайди на сервер, и в чате появится персональная ссылка.",
    en: "Bot commands:\n\n/status — my account and its status\n/help — this help\n\nRegistration starts in-game: join the server and your personal link will appear in the chat.",
  },
  "bot.start.no_token": {
    ru: "Привет! Это бот доступа на сервер.\n\nПерсональную ссылку для регистрации бот выдаёт в игре — зайди на сервер, и в игровом чате появится твоя ссылка для запуска регистрации.",
    en: "Hi! This is the server access bot.\n\nThe bot hands out personal registration links in-game — join the server and your personal link will appear in the game chat.",
  },
  "bot.start.bad_token": {
    ru: "Ссылка недействительна: код привязки повреждён или устарел.\n\nЗайди на сервер заново — в игровом чате появится свежая персональная ссылка.",
    en: "The link is invalid: the linking code is damaged or expired.\n\nRejoin the server — a fresh personal link will appear in the game chat.",
  },
  "bot.link.prompt": {
    ru: "Привязка аккаунта\n\nMinecraft: {username}\n\nПривязать этот Minecraft-аккаунт к текущему Telegram?\n\nПодтверждать вход и управлять аккаунтом сможет только этот Telegram-аккаунт.",
    en: "Account linking\n\nMinecraft: {username}\n\nLink this Minecraft account to the current Telegram account?\n\nOnly this Telegram account will be able to confirm logins and manage the account.",
  },
  "bot.link.confirming": {
    ru: "Проверяю, что ты сейчас в игре на этом аккаунте…",
    en: "Checking that you are currently in-game on this account…",
  },
  "bot.link.cancelled": {
    ru: "Привязка отменена. Ссылка осталась действительной — можешь продолжить позже через /start.",
    en: "Linking cancelled. The link stays valid — you can continue later via /start.",
  },
  "bot.link.success": {
    ru: "Аккаунт привязан!\n\nMinecraft: {username}\n\nТеперь этот Telegram-аккаунт подтверждает вход на сервер и управляет вашим аккаунтом.",
    en: "Account linked!\n\nMinecraft: {username}\n\nThis Telegram account now confirms server logins and manages the account.",
  },
  "bot.link.fail_token": {
    ru: "Не удалось привязать аккаунт.\n\nКод привязки недействителен или уже использован.\nЗайди на сервер заново — в игровом чате появится свежая персональная ссылка.",
    en: "Linking failed.\n\nThe linking code is invalid or has already been used.\nRejoin the server — a fresh personal link will appear in the game chat.",
  },
  "bot.link.fail_frozen": {
    ru: "Не удалось привязать аккаунт.\n\nАккаунт заблокирован или заморожен. Обратись к администрации сервера.",
    en: "Linking failed.\n\nThe account is banned or frozen. Contact the server administration.",
  },
  "bot.link.fail_already_linked": {
    ru: "Не удалось привязать аккаунт.\n\nЭтот Minecraft-аккаунт уже привязан к другому Telegram, или этот Telegram уже занят другим аккаунтом.",
    en: "Linking failed.\n\nThis Minecraft account is already linked to another Telegram account, or this Telegram account already belongs to someone else.",
  },
  "bot.link.fail_ownership": {
    ru: "Не удалось привязать аккаунт.\n\nНе удалось подтвердить, что ты сейчас в игре под этим аккаунтом.\nЗайди на сервер под этим ником и открой ссылку из игрового чата, не выходя с сервера.",
    en: "Linking failed.\n\nCould not confirm that you are currently in-game on this account.\nJoin the server with this nickname and open the link from the game chat without leaving the server.",
  },
  "bot.link.fail_offline": {
    ru: "Сейчас сервер недоступен, подтвердить владение аккаунтом нельзя.\n\nПопробуй позже: зайди на сервер и используй свежую ссылку из игрового чата.",
    en: "The server is unreachable right now, so account ownership cannot be confirmed.\n\nTry again later: join the server and use a fresh link from the game chat.",
  },
  "bot.link.fail_rate_limited": {
    ru: "Слишком много попыток привязки.\n\nПодожди немного и попробуй снова.",
    en: "Too many linking attempts.\n\nWait a moment and try again.",
  },
  "bot.link.fail_not_found": {
    ru: "Не удалось привязать аккаунт.\n\nАккаунт не найден. Зайди на сервер заново и используй свежую ссылку из игрового чата.",
    en: "Linking failed.\n\nAccount not found. Rejoin the server and use a fresh link from the game chat.",
  },
  "bot.account.screen": {
    ru: "Мой аккаунт\n\nMinecraft: {username}\nСтатус: {status}\n\nTelegram: {telegram}",
    en: "My account\n\nMinecraft: {username}\nStatus: {status}\n\nTelegram: {telegram}",
  },
  "bot.account.no_account": {
    ru: "К твоему Telegram пока не привязан аккаунт Minecraft.\n\nЗайди на сервер — в игровом чате появится персональная ссылка для регистрации.",
    en: "No Minecraft account is linked to this Telegram yet.\n\nJoin the server — your personal registration link will appear in the game chat.",
  },
  "bot.account.telegram_linked": {
    ru: "Подключён",
    en: "Connected",
  },
  "bot.account.telegram_not_linked": {
    ru: "Не подключён",
    en: "Not connected",
  },
  "bot.delete.confirm": {
    ru: "Вы действительно хотите удалить аккаунт?\n\nДоступ к Minecraft будет отозван.\nВы будете удалены из Telegram-группы.\nПерсональные данные будут удалены или обезличены.\n\nДействие необратимо.",
    en: "Do you really want to delete your account?\n\nMinecraft access will be revoked.\nYou will be removed from the Telegram group.\nPersonal data will be deleted or anonymized.\n\nThis action cannot be undone.",
  },
  "bot.delete.done": {
    ru: "Аккаунт удалён.\n\nMinecraft: {username}\n\nДоступ к серверу отозван, привязка Telegram снята, данные аккаунта обезличены.",
    en: "Account deleted.\n\nMinecraft: {username}\n\nServer access has been revoked, the Telegram link removed and account data anonymized.",
  },
  "bot.delete.fail": {
    ru: "Не удалось завершить удаление аккаунта.\n\nЧасть операций отзыва доступа завершилась с ошибкой — аккаунт остался в состоянии ожидания удаления.\nПопробуйте позже или обратитесь к администрации.",
    en: "Account deletion could not be completed.\n\nSome of the access-revocation steps failed — the account remains pending deletion.\nTry again later or contact the administration.",
  },
  "bot.account.status_changed": {
    ru: "Статус аккаунта изменён: {from} → {to}.",
    en: "Account status changed: {from} → {to}.",
  },
  "bot.error.generic": {
    ru: "Что-то пошло не так. Попробуй ещё раз позже.",
    en: "Something went wrong. Please try again later.",
  },
  "bot.kb.delete": {
    ru: "Удалить аккаунт",
    en: "Delete account",
  },
  "bot.kb.confirm": {
    ru: "Подтвердить",
    en: "Confirm",
  },
  "bot.kb.cancel": {
    ru: "Отмена",
    en: "Cancel",
  },
  "bot.kb.toggle_to_en": {
    ru: "🌐 English",
    en: "🌐 English",
  },
  "bot.kb.toggle_to_ru": {
    ru: "🌐 Русский",
    en: "🌐 Русский",
  },
  "bot.stats.no_mongo": {
    ru: "Статистика недоступна: база данных не подключена.",
    en: "Stats unavailable: the database is not connected.",
  },
  "bot.stats.db_header": {
    ru: "🗄 База данных:",
    en: "🗄 Database:",
  },
  "bot.stats.db_line": {
    ru: "{collection}: {documents} док. {size}",
    en: "{collection}: {documents} docs {size}",
  },
  "bot.stats.db_total": {
    ru: "Всего: {size}",
    en: "Total: {size}",
  },

  // account status display names (the {status} params in bot.* texts)
  "account.status.UNREGISTERED": { ru: "Без регистрации", en: "Unregistered" },
  "account.status.APPLICATION_PENDING": { ru: "Заявка на рассмотрении", en: "Application pending" },
  "account.status.VOTING": { ru: "Голосование", en: "Voting" },
  "account.status.APPROVED": { ru: "Одобрен", en: "Approved" },
  "account.status.ACTIVE": { ru: "Активен", en: "Active" },
  "account.status.SUSPENDED": { ru: "Заморожен", en: "Suspended" },
  "account.status.BANNED": { ru: "Заблокирован", en: "Banned" },
  "account.status.DELETION_PENDING": { ru: "Ожидает удаления", en: "Pending deletion" },
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

/** user-facing display name of an account status in the given locale */
export function statusLabel(locale: Locale, status: string): string {
  return t(locale, `account.status.${status}` as MessageKey)
}
