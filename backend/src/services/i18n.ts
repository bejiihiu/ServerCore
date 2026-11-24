/**
 * tiny key-map i18n for the plain-text channel: broadcast fan-outs, group
 * messages, callback toasts and the account status labels. locales are ru
 * (default) and en. every entry must exist in both locales — the `satisfies`
 * clause makes a missing translation a compile error.
 *
 * player-facing screens (private chat, html + keyboards) do NOT live here:
 * they come from src/i18n/locales/*.json through src/bot/ui/screens. the
 * split is by channel, not by convenience — a plain-text fan-out cannot carry
 * markup, and a screen cannot carry a broadcast.
 *
 * params use {name} placeholders and are substituted order-agnostic by name.
 * adding a locale means adding it to LOCALES and a column to every entry.
 */
export const LOCALES = ["ru", "en"] as const

export type Locale = (typeof LOCALES)[number]

export const DEFAULT_LOCALE: Locale = "ru"

const messages = {
  // ops events, sent to every user with a linked telegram account (and copied
  // to the admin chat). plain text, no emoji: the words carry the state.
  "ops.server.starting": {
    ru: "Сервер запускается",
    en: "The server is starting",
  },
  "ops.server.running": {
    ru: "Сервер запущен и работает",
    en: "The server is up",
  },
  "ops.server.restarting": {
    ru: "Сервер перезапускается — вернётся через пару минут",
    en: "The server is restarting — back in a couple of minutes",
  },
  "ops.server.restarting_scheduled": {
    ru: "Плановый перезапуск сервера — вернётся через пару минут",
    en: "Scheduled server restart — back in a couple of minutes",
  },
  "ops.server.stopping": {
    ru: "Сервер останавливается",
    en: "The server is stopping",
  },
  "ops.server.offline": {
    ru: "Сервер сейчас недоступен. Администрация уже получила оповещение.",
    en: "The server is unreachable right now. The admins have been alerted.",
  },
  "ops.server.update_ready": {
    ru: "Установлено обновление — сервер перезапускается",
    en: "An update was installed — the server is restarting",
  },
  // admin-only alerts
  "ops.admin.offline_alert": {
    ru: "Сервер {server} молчит {minutes} мин — похоже на краш",
    en: "Server {server} has been silent for {minutes} min — looks like a crash",
  },
  "ops.admin.server_back": {
    ru: "Сервер {server} снова на связи",
    en: "Server {server} is back online",
  },

  // admission: voting in the closed group (plain text + keyboard)
  "vote.announce": {
    ru: "Новая заявка\n\nИгрок: {username}\n\n{answers}\n\nГолосование завершится {endTime} (МСК). Для итога нужно больше половины голосов.",
    en: "New application\n\nPlayer: {username}\n\n{answers}\n\nVoting ends {endTime} (MSK). More than half of the eligible votes are needed for a result.",
  },
  "vote.extended": {
    ru: "Кворум не собран — голосование продлено. Итог {endTime} (МСК).",
    en: "Quorum not reached — voting extended. Result {endTime} (MSK).",
  },
  "vote.resultLine": {
    ru: "Итог: {verdict}",
    en: "Result: {verdict}",
  },
  "vote.resultApproved": {
    ru: "заявка принята: за — {for}, против — {against}",
    en: "application approved: for — {for}, against — {against}",
  },
  "vote.resultRejected": {
    ru: "заявка отклонена: за — {for}, против — {against}",
    en: "application rejected: for — {for}, against — {against}",
  },
  "vote.resultExpired": {
    ru: "кворум не собран ({cast} из {eligible} голосов) — заявка передана на рассмотрение администрации",
    en: "quorum not reached ({cast} of {eligible} votes) — application forwarded to the admins for review",
  },
  "vote.buttonAccept": {
    ru: "Принять ({n})",
    en: "Accept ({n})",
  },
  "vote.buttonReject": {
    ru: "Отклонить ({n})",
    en: "Reject ({n})",
  },
  // ephemeral confirmation, visible to the voter only
  "vote.confirmation": {
    ru: "Голос принят: {verdict}. До кворума — {remaining}.",
    en: "Vote counted: {verdict}. {remaining} to quorum.",
  },
  "vote.confirmationQuorum": {
    ru: "Голос принят: {verdict}. Кворум собран — итог будет по окончании голосования.",
    en: "Vote counted: {verdict}. Quorum reached — the result follows when voting ends.",
  },
  "vote.verdictAccept": {
    ru: "ЗА",
    en: "FOR",
  },
  "vote.verdictReject": {
    ru: "ПРОТИВ",
    en: "AGAINST",
  },
  // callback toasts: one line, no markup, no next step to describe
  "vote.feedbackAccepted": {
    ru: "Голос учтён.",
    en: "Vote counted.",
  },
  "vote.feedbackAlready": {
    ru: "Ты уже голосовал по этой заявке.",
    en: "You have already voted on this application.",
  },
  "vote.feedbackSelf": {
    ru: "За свою заявку голосовать нельзя.",
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
    ru: "Голосование по этой заявке уже закончилось.",
    en: "Voting on this application is already over.",
  },
  "vote.feedbackUnknown": {
    ru: "Этой кнопкой уже не проголосовать — голосование не активно.",
    en: "This button no longer votes — the voting is not active.",
  },

  // admission: outcomes, dm to the applicant
  "result.approvedToApplicant": {
    ru: "Твоя заявка одобрена\n\nИтог: за — {for}, против — {against}.\nПриглашение в группу придёт следующим сообщением.",
    en: "Your application is approved\n\nResult: for — {for}, against — {against}.\nThe group invite arrives in the next message.",
  },
  "result.rejectedToApplicant": {
    ru: "Твоя заявка отклонена\n\nИтог: за — {for}, против — {against}.\nНовую заявку можно подать через три месяца.",
    en: "Your application is rejected\n\nResult: for — {for}, against — {against}.\nYou can apply again in three months.",
  },
  "result.expiredToApplicant": {
    ru: "Кворум не собран — заявка передана администрации. О решении сообщат отдельно.",
    en: "Quorum was not reached — the application was forwarded to the admins. They will follow up.",
  },

  // admission: group
  "group.inviteText": {
    ru: "Заявка одобрена, доступ открыт\n\nОдноразовая ссылка для вступления в группу сервера:\n{url}\n\nСсылка работает один раз и живёт {hours} ч.",
    en: "Application approved, access is open\n\nOne-time link to join the server group:\n{url}\n\nThe link works once and lives for {hours} h.",
  },
  "group.activated": {
    ru: "Аккаунт активирован — доступ к серверу открыт",
    en: "Account activated — server access is open",
  },

  // admission: admin-only duplicates (locale follows the backend default)
  "admission.dm.vote_opened": {
    ru: "Новая заявка на голосовании: {username}. Голосование идёт в закрытой группе.",
    en: "A new application is being voted on: {username}. The vote runs in the closed group.",
  },
  "admission.admin.vote_opened": {
    ru: "Новая заявка на голосовании: {username} (за — {for}, против — {against})",
    en: "New application is being voted on: {username} (for — {for}, against — {against})",
  },
  "admission.admin.vote_decided": {
    ru: "Голосование завершено: {username} — {verdict}",
    en: "Voting finished: {username} — {verdict}",
  },
  "admission.admin.vote_extended": {
    ru: "Голосование по заявке {username} продлено до {endTime} (МСК): кворум не собран",
    en: "Voting for {username}'s application extended until {endTime} (MSK): quorum not reached",
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
  "admission.admin.invite_ref": {
    ru: ", ссылка {invite}",
    en: ", invite {invite}",
  },
  "admission.admin.yes": {
    ru: "да",
    en: "yes",
  },
  "admission.admin.no": {
    ru: "нет",
    en: "no",
  },

  // account notifications: out-of-band plain-text dms (link, status, deletion)
  "account.linked": {
    ru: "Minecraft-аккаунт {username} привязан к твоему Telegram",
    en: "Minecraft account {username} is linked to your Telegram",
  },
  "account.status_changed": {
    ru: "Статус аккаунта: {from} → {to}",
    en: "Account status: {from} → {to}",
  },
  "account.deleted": {
    ru: "Аккаунт {username} удалён: доступ отозван, привязка снята, данные обезличены",
    en: "Account {username} is deleted: access revoked, link removed, data anonymized",
  },
  "account.deletion_failed": {
    ru: "Удаление аккаунта не завершено — он ждёт удаления. Попробуй ещё раз позже или напиши администрации",
    en: "Account deletion did not finish — it is pending. Try again later or contact the administration",
  },

  // account status display names (the {status} params in plain-text texts)
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

/** user language with config fallback */
export function langOf(language: Locale | undefined, fallback: Locale): Locale {
  return language ?? fallback
}

/** user-facing display name of an account status in the given locale */
export function statusLabel(locale: Locale, status: string): string {
  return t(locale, `account.status.${status}` as MessageKey)
}
