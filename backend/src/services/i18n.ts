/**
 * tiny key-map i18n for user-facing texts. locales are ru (default) and en,
 * keys are dot-namespaced strings. every entry must exist in both locales —
 * a missing translation is a programming error and falls back to the key
 * itself so the gap is visible in output.
 *
 * params use {name} placeholders and are substituted order-agnostic by name.
 * adding a locale means adding it to LOCALES and a column to every entry.
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

  // admission: questionnaire
  "app.intro": {
    ru: "Заявка на вступление\n\nОтвечай на вопросы сообщениями — по одному ответу на вопрос.",
    en: "Membership application\n\nAnswer with messages — one answer per question.",
  },
  "app.question": {
    ru: "Вопрос {n} из {total}\n\n{question}",
    en: "Question {n} of {total}\n\n{question}",
  },
  "app.answerReceived": {
    ru: "Принято.",
    en: "Saved.",
  },
  "app.answerEmpty": {
    ru: "Ответ пустой. Напиши что-нибудь.",
    en: "Empty answer. Please write something.",
  },
  "app.answerTooLong": {
    ru: "Слишком длинный ответ, сократи до {max} символов.",
    en: "Too long, please shorten it to {max} characters.",
  },
  "app.cancelButton": {
    ru: "Отменить",
    en: "Cancel",
  },
  "app.submitButton": {
    ru: "Отправить анкету",
    en: "Submit application",
  },
  "app.viewButton": {
    ru: "Моя заявка",
    en: "My application",
  },
  "app.summary": {
    ru: "Заявка на вступление\n\nMinecraft: {username}\n\n{answers}",
    en: "Membership application\n\nMinecraft: {username}\n\n{answers}",
  },
  "app.cancelled": {
    ru: "Заполнение анкеты отменено. Начать заново можно командой /application.",
    en: "Application cancelled. You can start over with /application.",
  },
  "app.submitted": {
    ru: "Анкета отправлена. Голосование участников идёт в закрытой группе, о результате сообщат здесь.",
    en: "Application submitted. The members' vote runs in the closed group; the result will be reported here.",
  },
  "app.rateLimited": {
    ru: "Слишком много попыток, подожди немного и попробуй снова.",
    en: "Too many attempts, wait a bit and try again.",
  },
  "app.blockedLive": {
    ru: "У тебя уже есть активная заявка — она на голосовании.",
    en: "You already have an active application — it is being voted on.",
  },
  "app.blockedStatus": {
    ru: "Сейчас подать заявку нельзя: твой аккаунт не в нужном статусе.",
    en: "You cannot apply right now: your account is not in the right state.",
  },
  "app.blockedCooldown": {
    ru: "Повторную заявку можно подать не раньше {date} (МСК) — не прошло три месяца с прошлой.",
    en: "You can submit a new application after {date} (MSK) — three months have not passed since the last one.",
  },
  "app.viewNone": {
    ru: "У тебя ещё нет отправленных заявок.",
    en: "You have no submitted applications yet.",
  },
  "app.viewHeader": {
    ru: "Твоя последняя заявка:",
    en: "Your latest application:",
  },
  "app.alreadyInProgress": {
    ru: "Анкета уже заполняется — продолжай отвечать на вопросы или отмени её.",
    en: "Your application is already in progress — keep answering the questions or cancel it.",
  },

  // admission: language picker
  "lang.choose": {
    ru: "Выбери язык интерфейса / Choose interface language",
    en: "Выбери язык интерфейса / Choose interface language",
  },
  "lang.changed": {
    ru: "Язык обновлён.",
    en: "Language updated.",
  },

  // admission: voting in the closed group
  "vote.announce": {
    ru: "НОВАЯ АНКЕТА\n\nИгрок: {username}\n\n{answers}\n\nГолосование завершится {endTime} МСК.",
    en: "NEW APPLICATION\n\nPlayer: {username}\n\n{answers}\n\nVoting ends at {endTime} MSK.",
  },
  "vote.extended": {
    ru: "Кворум не собран — голосование продлено ещё на 3 часа. Итог будет подведён {endTime} МСК.",
    en: "Quorum not reached — voting extended by another 3 hours. The result is due {endTime} MSK.",
  },
  "vote.resultLine": {
    ru: "Итог: {verdict}",
    en: "Result: {verdict}",
  },
  "vote.resultApproved": {
    ru: "заявка принята. За — {for}, против — {against}.",
    en: "application approved. For — {for}, against — {against}.",
  },
  "vote.resultRejected": {
    ru: "заявка отклонена. За — {for}, против — {against}.",
    en: "application rejected. For — {for}, against — {against}.",
  },
  "vote.resultExpired": {
    ru: "кворум не собран ({cast} из {eligible} голосов), заявка передана на рассмотрение администрации.",
    en: "quorum not reached ({cast} of {eligible} votes), application forwarded to the admins for review.",
  },
  "vote.buttonAccept": {
    ru: "Принять ({n})",
    en: "Accept ({n})",
  },
  "vote.buttonReject": {
    ru: "Отклонить ({n})",
    en: "Reject ({n})",
  },
  "vote.confirmation": {
    ru: "Голос принят: {verdict}. До кворума осталось {remaining}.",
    en: "Vote counted: {verdict}. {remaining} more votes until quorum.",
  },
  "vote.confirmationQuorum": {
    ru: "Голос принят: {verdict}. Кворум собран, итог будет подведён по окончании голосования.",
    en: "Vote counted: {verdict}. Quorum reached, the result will be published when voting ends.",
  },
  "vote.verdictAccept": {
    ru: "ЗА",
    en: "FOR",
  },
  "vote.verdictReject": {
    ru: "ПРОТИВ",
    en: "AGAINST",
  },
  "vote.feedbackAccepted": {
    ru: "Голос учтён.",
    en: "Vote counted.",
  },
  "vote.feedbackAlready": {
    ru: "Ты уже голосовал по этой заявке.",
    en: "You have already voted on this application.",
  },
  "vote.feedbackSelf": {
    ru: "За собственную заявку голосовать нельзя.",
    en: "You cannot vote on your own application.",
  },
  "vote.feedbackInactive": {
    ru: "Голосовать могут только действующие участники.",
    en: "Only active members can vote.",
  },
  "vote.feedbackNotInGroup": {
    ru: "Голосовать могут только участники группы.",
    en: "Only group members can vote.",
  },
  "vote.feedbackClosed": {
    ru: "Голосование по этой заявке уже завершено.",
    en: "Voting on this application is already over.",
  },
  "vote.feedbackUnknown": {
    ru: "Кнопка устарела, голосование уже не активно.",
    en: "This button is stale, the voting is no longer active.",
  },

  // admission: outcomes
  "result.approvedToApplicant": {
    ru: "Твоя заявка одобрена!\n\nИтог: за — {for}, против — {against}.\nСейчас пришлём приглашение в группу сервера.",
    en: "Your application is approved!\n\nResult: for — {for}, against — {against}.\nYour group invite is on its way.",
  },
  "result.rejectedToApplicant": {
    ru: "Твоя заявка отклонена.\n\nИтог: за — {for}, против — {against}.\nПодать новую заявку можно не раньше, чем через три месяца.",
    en: "Your application is rejected.\n\nResult: for — {for}, against — {against}.\nYou can apply again in three months.",
  },
  "result.expiredToApplicant": {
    ru: "Кворум не собран, твоя заявка передана на рассмотрение администрации. О решении сообщат отдельно.",
    en: "Quorum was not reached, your application was forwarded to the admins. They will contact you.",
  },

  // admission: group
  "group.inviteText": {
    ru: "Твоя заявка одобрена, добро пожаловать!\n\nОдноразовая ссылка для вступления в группу сервера:\n{url}\n\nСсылка работает один раз и действительна {hours} ч.",
    en: "Your application is approved, welcome!\n\nOne-time link to join the server group:\n{url}\n\nThe link works once and is valid for {hours} h.",
  },
  "group.activated": {
    ru: "Аккаунт активирован, доступ к серверу открыт. Хорошей игры!",
    en: "Account activated, server access is open. Have fun!",
  },

  // admission: account status
  "status.notLinked": {
    ru: "Аккаунт пока не привязан. Зайди на сервер — бот выдаст персональную ссылку в игровой чат.",
    en: "Account is not linked yet. Join the server — the bot will post your personal link in the game chat.",
  },
  "status.notFound": {
    ru: "Аккаунт не найден. Зайди на сервер и пройди регистрацию по ссылке из игры.",
    en: "Account not found. Join the server and register via the in-game link.",
  },
  "status.reply": {
    ru: "Minecraft: {username}\nСтатус: {status}\nTelegram: подключён",
    en: "Minecraft: {username}\nStatus: {status}\nTelegram: connected",
  },
  "help.text": {
    ru: "Команды:\n/application — заполнить заявку на вступление\n/myapplication — посмотреть последнюю заявку\n/status — статус аккаунта\n/help — эта справка",
    en: "Commands:\n/application — fill in the membership application\n/myapplication — view your latest application\n/status — account status\n/help — this help",
  },
  "error.generic": {
    ru: "Что-то пошло не так, попробуй позже.",
    en: "Something went wrong, try again later.",
  },

  // admission: admin-only duplicates (locale follows the backend default)
  "admission.admin.vote_opened": {
    ru: "Новая заявка на голосовании: {username} ({for} за / {against} против на данный момент).",
    en: "New application is being voted on: {username} ({for} for / {against} against so far).",
  },
  "admission.admin.vote_decided": {
    ru: "Голосование завершено: {username} — {verdict}",
    en: "Voting finished: {username} — {verdict}",
  },
  "admission.admin.vote_expired": {
    ru: "Голосование завершилось без кворума: заявка {username} требует ручного рассмотрения. Голоса: за — {for}, против — {against}, проголосовало {cast} из {eligible}.",
    en: "Voting ended without quorum: {username}'s application needs manual review. Votes: for — {for}, against — {against}, {cast} of {eligible} voted.",
  },
  "admission.admin.vote_extended": {
    ru: "Голосование по заявке {username} продлено до {endTime} МСК (кворум не собран).",
    en: "Voting for {username}'s application extended until {endTime} MSK (quorum not reached).",
  },
  "admission.admin.invite_issued": {
    ru: "Одноразовое приглашение выдано: {username} ({name})",
    en: "One-time invite issued: {username} ({name})",
  },
  "admission.admin.member_kicked": {
    ru: "Участник удалён из группы: {username}, кик: {kicked}, отозвано ссылок: {revoked}",
    en: "Member removed from the group: {username}, kicked: {kicked}, invites revoked: {revoked}",
  },
  "admission.admin.anomaly": {
    ru: "Подозрительное событие в группе: {kind}, telegram {telegram}{invite}",
    en: "Suspicious group event: {kind}, telegram {telegram}{invite}",
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

/** user language with config fallback */
export function langOf(language: Locale | undefined, fallback: Locale): Locale {
  return language ?? fallback
}

/** user-facing account status names */
export function statusLabel(status: string, locale: Locale): string {
  const labels: Record<string, Record<Locale, string>> = {
    UNREGISTERED: { ru: "Не зарегистрирован", en: "Unregistered" },
    APPLICATION_PENDING: { ru: "Заявка заполняется", en: "Application in progress" },
    VOTING: { ru: "На голосовании", en: "Being voted on" },
    APPROVED: { ru: "Одобрен", en: "Approved" },
    ACTIVE: { ru: "Активен", en: "Active" },
    SUSPENDED: { ru: "Приостановлен", en: "Suspended" },
    BANNED: { ru: "Заблокирован", en: "Banned" },
    DELETION_PENDING: { ru: "Удаляется", en: "Deletion pending" },
  }
  return labels[status]?.[locale] ?? status
}
