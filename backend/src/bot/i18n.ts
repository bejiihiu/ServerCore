/**
 * key-based message map for every user-facing bot text. ru is the primary
 * language, en is the full fallback mirror. params are plain string
 * substitutions: {username}, {status}, ...
 */
export type Language = "ru" | "en"

export const LANGUAGES: readonly Language[] = ["ru", "en"]

export function normalizeLanguage(value: string | undefined | null): Language {
  return value === "en" ? "en" : "ru"
}

export function languageToggleLabel(current: Language): string {
  return current === "ru" ? "🌐 English" : "🌐 Русский"
}

export function languageToggleNewLabel(current: Language): string {
  return current === "ru" ? "en" : "ru"
}

// the message keys are shared between commands and conversations; adding a
// key without filling both languages is a type error
export type MessageKey =
  | "help"
  | "startNoToken"
  | "startBadToken"
  | "linkPrompt"
  | "linkConfirmed"
  | "linkCancelled"
  | "linkSuccess"
  | "linkFailTokenInvalid"
  | "linkFailAccountFrozen"
  | "linkFailAlreadyLinked"
  | "linkFailOwnership"
  | "linkFailServerOffline"
  | "linkFailRateLimited"
  | "linkFailAccountNotFound"
  | "accountScreen"
  | "statusNoAccount"
  | "statusTelegramLinked"
  | "statusTelegramNotLinked"
  | "deleteConfirm"
  | "deleteDone"
  | "deleteFail"
  | "statusChanged"
  | "errorGeneric"
  | "kbDelete"
  | "kbConfirmDelete"
  | "kbCancel"

const MESSAGES: Record<Language, Record<MessageKey, string>> = {
  ru: {
    help: [
      "Команды бота:",
      "",
      "/status — мой аккаунт и его статус",
      "/help — эта справка",
      "",
      "Регистрация начинается в игре: зайди на сервер, и в чате появится персональная ссылка.",
    ].join("\n"),
    startNoToken: [
      "Привет! Это бот доступа на сервер.",
      "",
      "Персональную ссылку для регистрации бот выдаёт в игре — зайди на сервер,",
      "и в игровом чате появится твоя ссылка для запуска регистрации.",
    ].join("\n"),
    startBadToken: [
      "Ссылка недействительна: код привязки повреждён или устарел.",
      "",
      "Зайди на сервер заново — в игровом чате появится свежая персональная ссылка.",
    ].join("\n"),
    linkPrompt: [
      "Привязка аккаунта",
      "",
      "Minecraft: {username}",
      "",
      "Привязать этот Minecraft-аккаунт к текущему Telegram?",
      "",
      "Подтверждать вход и управлять аккаунтом сможет только этот Telegram-аккаунт.",
    ].join("\n"),
    linkConfirmed: "Проверяю, что ты сейчас в игре на этом аккаунте…",
    linkCancelled: "Привязка отменена. Ссылка осталась действительной — можешь продолжить позже через /start.",
    linkSuccess: [
      "Аккаунт привязан!",
      "",
      "Minecraft: {username}",
      "",
      "Теперь этот Telegram-аккаунт подтверждает вход на сервер и управляет вашим аккаунтом.",
    ].join("\n"),
    linkFailTokenInvalid: [
      "Не удалось привязать аккаунт.",
      "",
      "Код привязки недействителен или уже использован.",
      "Зайди на сервер заново — в игровом чате появится свежая персональная ссылка.",
    ].join("\n"),
    linkFailAccountFrozen: [
      "Не удалось привязать аккаунт.",
      "",
      "Аккаунт заблокирован или заморожен. Обратись к администрации сервера.",
    ].join("\n"),
    linkFailAlreadyLinked: [
      "Не удалось привязать аккаунт.",
      "",
      "Этот Minecraft-аккаунт уже привязан к другому Telegram, или этот Telegram уже занят другим аккаунтом.",
    ].join("\n"),
    linkFailOwnership: [
      "Не удалось привязать аккаунт.",
      "",
      "Не удалось подтвердить, что ты сейчас в игре под этим аккаунтом.",
      "Зайди на сервер под этим ником и открой ссылку из игрового чата, не выходя с сервера.",
    ].join("\n"),
    linkFailServerOffline: [
      "Сейчас сервер недоступен, подтвердить владение аккаунтом нельзя.",
      "",
      "Попробуй позже: зайди на сервер и используй свежую ссылку из игрового чата.",
    ].join("\n"),
    linkFailRateLimited: [
      "Слишком много попыток привязки.",
      "",
      "Подожди немного и попробуй снова.",
    ].join("\n"),
    linkFailAccountNotFound: [
      "Не удалось привязать аккаунт.",
      "",
      "Аккаунт не найден. Зайди на сервер заново и используй свежую ссылку из игрового чата.",
    ].join("\n"),
    accountScreen: [
      "Мой аккаунт",
      "",
      "Minecraft: {username}",
      "Статус: {status}",
      "",
      "Telegram: {telegram}",
    ].join("\n"),
    statusNoAccount: [
      "К твоему Telegram пока не привязан аккаунт Minecraft.",
      "",
      "Зайди на сервер — в игровом чате появится персональная ссылка для регистрации.",
    ].join("\n"),
    statusTelegramLinked: "Подключён",
    statusTelegramNotLinked: "Не подключён",
    deleteConfirm: [
      "Вы действительно хотите удалить аккаунт?",
      "",
      "Доступ к Minecraft будет отозван.",
      "Вы будете удалены из Telegram-группы.",
      "Персональные данные будут удалены или обезличены.",
      "",
      "Действие необратимо.",
    ].join("\n"),
    deleteDone: [
      "Аккаунт удалён.",
      "",
      "Minecraft: {username}",
      "",
      "Доступ к серверу отозван, привязка Telegram снята, данные аккаунта обезличены.",
    ].join("\n"),
    deleteFail: [
      "Не удалось завершить удаление аккаунта.",
      "",
      "Часть операций отзыва доступа завершилась с ошибкой — аккаунт остался в состоянии ожидания удаления.",
      "Попробуйте позже или обратитесь к администрации.",
    ].join("\n"),
    statusChanged: "Статус аккаунта изменён: {from} → {to}.",
    errorGeneric: "Что-то пошло не так. Попробуй ещё раз позже.",
    kbDelete: "Удалить аккаунт",
    kbConfirmDelete: "Подтвердить",
    kbCancel: "Отмена",
  },
  en: {
    help: [
      "Bot commands:",
      "",
      "/status — my account and its status",
      "/help — this help",
      "",
      "Registration starts in-game: join the server and your personal link will appear in the chat.",
    ].join("\n"),
    startNoToken: [
      "Hi! This is the server access bot.",
      "",
      "The bot hands out personal registration links in-game — join the server",
      "and your personal link will appear in the game chat.",
    ].join("\n"),
    startBadToken: [
      "The link is invalid: the linking code is damaged or expired.",
      "",
      "Rejoin the server — a fresh personal link will appear in the game chat.",
    ].join("\n"),
    linkPrompt: [
      "Account linking",
      "",
      "Minecraft: {username}",
      "",
      "Link this Minecraft account to the current Telegram account?",
      "",
      "Only this Telegram account will be able to confirm logins and manage the account.",
    ].join("\n"),
    linkConfirmed: "Checking that you are currently in-game on this account…",
    linkCancelled: "Linking cancelled. The link stays valid — you can continue later via /start.",
    linkSuccess: [
      "Account linked!",
      "",
      "Minecraft: {username}",
      "",
      "This Telegram account now confirms server logins and manages the account.",
    ].join("\n"),
    linkFailTokenInvalid: [
      "Linking failed.",
      "",
      "The linking code is invalid or has already been used.",
      "Rejoin the server — a fresh personal link will appear in the game chat.",
    ].join("\n"),
    linkFailAccountFrozen: [
      "Linking failed.",
      "",
      "The account is banned or frozen. Contact the server administration.",
    ].join("\n"),
    linkFailAlreadyLinked: [
      "Linking failed.",
      "",
      "This Minecraft account is already linked to another Telegram account, or this Telegram account already belongs to someone else.",
    ].join("\n"),
    linkFailOwnership: [
      "Linking failed.",
      "",
      "Could not confirm that you are currently in-game on this account.",
      "Join the server with this nickname and open the link from the game chat without leaving the server.",
    ].join("\n"),
    linkFailServerOffline: [
      "The server is unreachable right now, so account ownership cannot be confirmed.",
      "",
      "Try again later: join the server and use a fresh link from the game chat.",
    ].join("\n"),
    linkFailRateLimited: [
      "Too many linking attempts.",
      "",
      "Wait a moment and try again.",
    ].join("\n"),
    linkFailAccountNotFound: [
      "Linking failed.",
      "",
      "Account not found. Rejoin the server and use a fresh link from the game chat.",
    ].join("\n"),
    accountScreen: [
      "My account",
      "",
      "Minecraft: {username}",
      "Status: {status}",
      "",
      "Telegram: {telegram}",
    ].join("\n"),
    statusNoAccount: [
      "No Minecraft account is linked to this Telegram yet.",
      "",
      "Join the server — your personal registration link will appear in the game chat.",
    ].join("\n"),
    statusTelegramLinked: "Connected",
    statusTelegramNotLinked: "Not connected",
    deleteConfirm: [
      "Do you really want to delete your account?",
      "",
      "Minecraft access will be revoked.",
      "You will be removed from the Telegram group.",
      "Personal data will be deleted or anonymized.",
      "",
      "This action cannot be undone.",
    ].join("\n"),
    deleteDone: [
      "Account deleted.",
      "",
      "Minecraft: {username}",
      "",
      "Server access has been revoked, the Telegram link removed and account data anonymized.",
    ].join("\n"),
    deleteFail: [
      "Account deletion could not be completed.",
      "",
      "Some of the access-revocation steps failed — the account remains pending deletion.",
      "Try again later or contact the administration.",
    ].join("\n"),
    statusChanged: "Account status changed: {from} → {to}.",
    errorGeneric: "Something went wrong. Please try again later.",
    kbDelete: "Delete account",
    kbConfirmDelete: "Confirm",
    kbCancel: "Cancel",
  },
}

/** formats a message for the language, substituting {param} placeholders */
export function t(lang: Language, key: MessageKey, params: Record<string, string | number> = {}): string {
  let text: string = MESSAGES[lang][key]
  for (const [name, value] of Object.entries(params)) {
    text = text.replaceAll(`{${name}}`, String(value))
  }
  return text
}
