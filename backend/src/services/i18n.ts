import type { Lang } from "./ports.js"

/**
 * tiny key-based i18n: two flat maps, no framework. adding a locale means
 * adding one more record to TEXTS and extending the Lang union.
 */
const TEXTS = {
  ru: {
    "app.intro":
      "Заявка на вступление\n\nОтвечай на вопросы сообщениями — по одному ответу на вопрос.",
    "app.question": "Вопрос {n} из {total}\n\n{question}",
    "app.answerReceived": "Принято.",
    "app.answerEmpty": "Ответ пустой. Напиши что-нибудь.",
    "app.answerTooLong": "Слишком длинный ответ, сократи до {max} символов.",
    "app.cancelButton": "Отменить",
    "app.submitButton": "Отправить анкету",
    "app.viewButton": "Моя заявка",
    "app.summary": "Заявка на вступление\n\nMinecraft: {username}\n\n{answers}",
    "app.cancelled": "Заполнение анкеты отменено. Начать заново можно командой /application.",
    "app.submitted":
      "Анкета отправлена. Голосование участников идёт в закрытой группе, о результате сообщат здесь.",
    "app.rateLimited": "Слишком много попыток, подожди немного и попробуй снова.",
    "app.blockedLive": "У тебя уже есть активная заявка — она на голосовании.",
    "app.blockedStatus": "Сейчас подать заявку нельзя: твой аккаунт не в нужном статусе.",
    "app.blockedCooldown":
      "Повторную заявку можно подать не раньше {date} (МСК) — не прошло три месяца с прошлой.",
    "app.viewNone": "У тебя ещё нет отправленных заявок.",
    "app.viewHeader": "Твоя последняя заявка:",
    "app.alreadyInProgress": "Анкета уже заполняется — продолжай отвечать на вопросы или отмени её.",
    "lang.choose": "Выбери язык интерфейса / Choose interface language",
    "lang.changed": "Язык обновлён.",
    "vote.announce": "НОВАЯ АНКЕТА\n\nИгрок: {username}\n\n{answers}\n\nГолосование завершится {endTime} МСК.",
    "vote.extended":
      "Кворум не собран — голосование продлено ещё на 3 часа. Итог будет подведён {endTime} МСК.",
    "vote.resultLine": "Итог: {verdict}",
    "vote.resultApproved": "заявка принята. За — {for}, против — {against}.",
    "vote.resultRejected": "заявка отклонена. За — {for}, против — {against}.",
    "vote.resultExpired":
      "кворум не собран ({cast} из {eligible} голосов), заявка передана на рассмотрение администрации.",
    "vote.buttonAccept": "Принять ({n})",
    "vote.buttonReject": "Отклонить ({n})",
    "vote.confirmation": "Голос принят: {verdict}. До кворума осталось {remaining}.",
    "vote.confirmationQuorum": "Голос принят: {verdict}. Кворум собран, итог будет подведён по окончании голосования.",
    "vote.verdictAccept": "ЗА",
    "vote.verdictReject": "ПРОТИВ",
    "vote.feedbackAccepted": "Голос учтён.",
    "vote.feedbackAlready": "Ты уже голосовал по этой заявке.",
    "vote.feedbackSelf": "За собственную заявку голосовать нельзя.",
    "vote.feedbackInactive": "Голосовать могут только действующие участники.",
    "vote.feedbackNotInGroup": "Голосовать могут только участники группы.",
    "vote.feedbackClosed": "Голосование по этой заявке уже завершено.",
    "vote.feedbackUnknown": "Кнопка устарела, голосование уже не активно.",
    "result.approvedToApplicant":
      "Твоя заявка одобрена!\n\nИтог: за — {for}, против — {against}.\nСейчас пришлём приглашение в группу сервера.",
    "result.rejectedToApplicant":
      "Твоя заявка отклонена.\n\nИтог: за — {for}, против — {against}.\nПодать новую заявку можно не раньше, чем через три месяца.",
    "result.expiredToApplicant":
      "Кворум не собран, твоя заявка передана на рассмотрение администрации. О решении сообщат отдельно.",
    "group.inviteText":
      "Твоя заявка одобрена, добро пожаловать!\n\nОдноразовая ссылка для вступления в группу сервера:\n{url}\n\nСсылка работает один раз и действительна {hours} ч.",
    "group.activated": "Аккаунт активирован, доступ к серверу открыт. Хорошей игры!",
    "status.notLinked": "Аккаунт пока не привязан. Зайди на сервер — бот выдаст персональную ссылку в игровой чат.",
    "status.notFound": "Аккаунт не найден. Зайди на сервер и пройди регистрацию по ссылке из игры.",
    "status.reply": "Minecraft: {username}\nСтатус: {status}\nTelegram: подключён",
    "help.text":
      "Команды:\n/application — заполнить заявку на вступление\n/myapplication — посмотреть последнюю заявку\n/status — статус аккаунта\n/help — эта справка",
    "error.generic": "Что-то пошло не так, попробуй позже.",
  },
  en: {
    "app.intro":
      "Membership application\n\nAnswer with messages — one answer per question.",
    "app.question": "Question {n} of {total}\n\n{question}",
    "app.answerReceived": "Saved.",
    "app.answerEmpty": "Empty answer. Please write something.",
    "app.answerTooLong": "Too long, please shorten it to {max} characters.",
    "app.cancelButton": "Cancel",
    "app.submitButton": "Submit application",
    "app.viewButton": "My application",
    "app.summary": "Membership application\n\nMinecraft: {username}\n\n{answers}",
    "app.cancelled": "Application cancelled. You can start over with /application.",
    "app.submitted":
      "Application submitted. The members' vote runs in the closed group; the result will be reported here.",
    "app.rateLimited": "Too many attempts, wait a bit and try again.",
    "app.blockedLive": "You already have an active application — it is being voted on.",
    "app.blockedStatus": "You cannot apply right now: your account is not in the right state.",
    "app.blockedCooldown":
      "You can submit a new application after {date} (MSK) — three months have not passed since the last one.",
    "app.viewNone": "You have no submitted applications yet.",
    "app.viewHeader": "Your latest application:",
    "app.alreadyInProgress": "Your application is already in progress — keep answering the questions or cancel it.",
    "lang.choose": "Выбери язык интерфейса / Choose interface language",
    "lang.changed": "Language updated.",
    "vote.announce": "NEW APPLICATION\n\nPlayer: {username}\n\n{answers}\n\nVoting ends at {endTime} MSK.",
    "vote.extended":
      "Quorum not reached — voting extended by another 3 hours. The result is due {endTime} MSK.",
    "vote.resultLine": "Result: {verdict}",
    "vote.resultApproved": "application approved. For — {for}, against — {against}.",
    "vote.resultRejected": "application rejected. For — {for}, against — {against}.",
    "vote.resultExpired":
      "quorum not reached ({cast} of {eligible} votes), application forwarded to the admins for review.",
    "vote.buttonAccept": "Accept ({n})",
    "vote.buttonReject": "Reject ({n})",
    "vote.confirmation": "Vote counted: {verdict}. {remaining} more votes until quorum.",
    "vote.confirmationQuorum": "Vote counted: {verdict}. Quorum reached, the result will be published when voting ends.",
    "vote.verdictAccept": "FOR",
    "vote.verdictReject": "AGAINST",
    "vote.feedbackAccepted": "Vote counted.",
    "vote.feedbackAlready": "You have already voted on this application.",
    "vote.feedbackSelf": "You cannot vote on your own application.",
    "vote.feedbackInactive": "Only active members can vote.",
    "vote.feedbackNotInGroup": "Only group members can vote.",
    "vote.feedbackClosed": "Voting on this application is already over.",
    "vote.feedbackUnknown": "This button is stale, the voting is no longer active.",
    "result.approvedToApplicant":
      "Your application is approved!\n\nResult: for — {for}, against — {against}.\nYour group invite is on its way.",
    "result.rejectedToApplicant":
      "Your application is rejected.\n\nResult: for — {for}, against — {against}.\nYou can apply again in three months.",
    "result.expiredToApplicant":
      "Quorum was not reached, your application was forwarded to the admins. They will contact you.",
    "group.inviteText":
      "Your application is approved, welcome!\n\nOne-time link to join the server group:\n{url}\n\nThe link works once and is valid for {hours} h.",
    "group.activated": "Account activated, server access is open. Have fun!",
    "status.notLinked": "Account is not linked yet. Join the server — the bot will post your personal link in the game chat.",
    "status.notFound": "Account not found. Join the server and register via the in-game link.",
    "status.reply": "Minecraft: {username}\nStatus: {status}\nTelegram: connected",
    "help.text":
      "Commands:\n/application — fill in the membership application\n/myapplication — view your latest application\n/status — account status\n/help — this help",
    "error.generic": "Something went wrong, try again later.",
  },
} as const

export type TextKey = keyof (typeof TEXTS)["ru"]

export type TextParams = Record<string, string | number>

/** locales other than ru/en fail at compile time thanks to the key type */
export function t(lang: Lang, key: TextKey, params?: TextParams): string {
  const template = TEXTS[lang][key]
  if (!params) return template
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => {
    const value = params[name]
    return value === undefined ? whole : String(value)
  })
}

/** user language with config fallback */
export function langOf(language: Lang | undefined, fallback: Lang): Lang {
  return language ?? fallback
}

/** user-facing account status names */
export function statusLabel(status: string, lang: Lang): string {
  const labels: Record<string, Record<Lang, string>> = {
    UNREGISTERED: { ru: "Не зарегистрирован", en: "Unregistered" },
    APPLICATION_PENDING: { ru: "Заявка заполняется", en: "Application in progress" },
    VOTING: { ru: "На голосовании", en: "Being voted on" },
    APPROVED: { ru: "Одобрен", en: "Approved" },
    ACTIVE: { ru: "Активен", en: "Active" },
    SUSPENDED: { ru: "Приостановлен", en: "Suspended" },
    BANNED: { ru: "Заблокирован", en: "Banned" },
    DELETION_PENDING: { ru: "Удаляется", en: "Deletion pending" },
  }
  return labels[status]?.[lang] ?? status
}
