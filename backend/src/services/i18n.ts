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
  "ops.stats.admission": {
    ru: "🗳 Голосования: открытых {count}",
    en: "🗳 Votings: open {count}",
  },
  "ops.stats.accounts": {
    ru: "👤 Аккаунты: активных {count}",
    en: "👤 Accounts: active {count}",
  },
  "ops.stats.spec": {
    ru: "👁 Наблюдения: активных {count}",
    en: "👁 Observations: active {count}",
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
  "app.question": {
    ru: "Вопрос {n} из {total}\n\n{question}",
    en: "Question {n} of {total}\n\n{question}",
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
  "app.answerNonText": {
    ru: "Пришли ответ текстовым сообщением.",
    en: "Send your answer as a text message.",
  },
  "app.commandHint": {
    ru: "Команды сейчас не работают — отвечай на вопрос текстом или нажми «Отменить».",
    en: "Commands are off while the questionnaire is open — answer with text or press Cancel.",
  },
  "app.buttonHint": {
    ru: "Используй кнопки под сообщением: «Отправить анкету» или «Отменить».",
    en: "Use the buttons below: Submit application or Cancel.",
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
  "error.generic": {
    ru: "Что-то пошло не так, попробуй позже.",
    en: "Something went wrong, try again later.",
  },

  // admission: admin-only duplicates (locale follows the backend default)
  "admission.dm.vote_opened": {
    ru: "Новая анкета на голосовании: {username}. Голосование идёт в закрытой группе.",
    en: "A new application is being voted on: {username}. The vote runs in the closed group.",
  },
  "admission.admin.vote_opened": {
    ru: "Новая заявка на голосовании: {username} ({for} за / {against} против на данный момент).",
    en: "New application is being voted on: {username} ({for} for / {against} against so far).",
  },
  "admission.admin.vote_decided": {
    ru: "Голосование завершено: {username} — {verdict}",
    en: "Voting finished: {username} — {verdict}",
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
  "admission.admin.invite_ref": {
    ru: ", ссылка {name}",
    en: ", invite {name}",
  },
  "admission.admin.yes": {
    ru: "да",
    en: "yes",
  },
  "admission.admin.no": {
    ru: "нет",
    en: "no",
  },

  // account module: telegram linking flow, account screen, deletion.
  // user-facing, every key exists in both locales
  "bot.help": {
    ru: "Команды бота:\n\n/status — мой аккаунт и его статус\n/application — заполнить заявку на вступление\n/myapplication — посмотреть последнюю заявку\n/language — сменить язык интерфейса\n/help — эта справка\n\nРегистрация начинается в игре: зайди на сервер, и в чате появится персональная ссылка.",
    en: "Bot commands:\n\n/status — my account and its status\n/application — fill in the membership application\n/myapplication — view your latest application\n/language — switch the interface language\n/help — this help\n\nRegistration starts in-game: join the server and your personal link will appear in the chat.",
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
    ru: "Аккаунт привязан!\n\nMinecraft: {username}\n\nТеперь этот Telegram-аккаунт подтверждает вход на сервер и управляет твоим аккаунтом.",
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
  "bot.conversation.busy": {
    ru: "Сейчас активен другой диалог с ботом. Заверши его и попробуй снова.",
    en: "Another bot dialog is already active. Finish it and try again.",
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

  // bot ui: shared bits every screen uses
  "common.back": {
    ru: "‹ Назад",
    en: "‹ Back",
  },
  "common.refresh": {
    ru: "Обновить",
    en: "Refresh",
  },
  "common.loading": {
    ru: "Загрузка…",
    en: "Loading…",
  },
  "common.empty": {
    ru: "Здесь пока ничего нет",
    en: "Nothing here yet",
  },

  // bot ui: main menu
  "menu.title": {
    ru: "ServerCore",
    en: "ServerCore",
  },
  "menu.subtitle": {
    ru: "доступ на сервер",
    en: "server access",
  },
  "menu.statusNotLinked": {
    ru: "Аккаунт не привязан",
    en: "Account not linked",
  },
  "menu.statusLinked": {
    ru: "Аккаунт привязан: {nickname}",
    en: "Account linked: {nickname}",
  },
  "menu.hint": {
    ru: "Привязка идёт через персональную ссылку из игры: зайди на сервер — ссылка появится в игровом чате.",
    en: "Linking goes through a personal link from the game: join the server and the link will appear in the game chat.",
  },
  "menu.action.status": {
    ru: "Статус сервера",
    en: "Server status",
  },
  "menu.action.account": {
    ru: "Мой аккаунт",
    en: "My account",
  },
  "menu.action.help": {
    ru: "Помощь",
    en: "Help",
  },

  // bot ui: status screen
  "status.title": {
    ru: "Статус",
    en: "Status",
  },
  "status.unknown": {
    ru: "нет данных",
    en: "no data",
  },
  "status.account": {
    ru: "Аккаунт",
    en: "Account",
  },
  "status.accountNotLinked": {
    ru: "не привязан",
    en: "not linked",
  },
  "status.accountLinked": {
    ru: "привязан: {nickname}",
    en: "linked: {nickname}",
  },
  "status.server": {
    ru: "Сервер",
    en: "Server",
  },
  "status.serverOnline": {
    ru: "онлайн",
    en: "online",
  },
  "status.playersLabel": {
    ru: "Игроки",
    en: "Players",
  },
  "status.players.one": {
    ru: "{count} игрок",
    en: "{count} player",
  },
  "status.players.few": {
    ru: "{count} игрока",
    en: "{count} players",
  },
  "status.players.many": {
    ru: "{count} игроков",
    en: "{count} players",
  },
  "status.players.other": {
    ru: "{count} игроков",
    en: "{count} players",
  },

  // bot ui: account card
  "account.title": {
    ru: "Мой аккаунт",
    en: "My account",
  },
  "account.notLinkedTitle": {
    ru: "Аккаунт не привязан",
    en: "Account not linked",
  },
  "account.notLinkedHint": {
    ru: "Чтобы играть на сервере, привяжи аккаунт: зайди на сервер — персональная ссылка появится в игровом чате.",
    en: "To play on the server, link your account: join the server and a personal link will appear in the game chat.",
  },
  "account.fieldNickname": {
    ru: "Ник",
    en: "Nickname",
  },
  "account.fieldLinked": {
    ru: "Дата привязки",
    en: "Linked on",
  },
  "account.fieldRole": {
    ru: "Роль",
    en: "Role",
  },
  "account.action.refresh": {
    ru: "Обновить",
    en: "Refresh",
  },
  "confirm.defaultTitle": {
    ru: "Подтверждение",
    en: "Confirmation",
  },

  // bot ui: login prompt
  "auth.title": {
    ru: "🔐 Авторизация на сервере",
    en: "🔐 Server login",
  },
  "auth.player": {
    ru: "Игрок: {player}",
    en: "Player: {player}",
  },
  "auth.server": {
    ru: "Сервер: {server}",
    en: "Server: {server}",
  },
  "auth.detected": {
    ru: "Обнаружена новая игровая сессия.",
    en: "A new game session was detected.",
  },
  "auth.window": {
    ru: "Подтверди вход — запрос действителен {seconds} сек.",
    en: "Confirm the login — the request is valid for {seconds} s.",
  },
  "auth.info": {
    ru: "Запрос привязан к текущей сессии и одноразовый. Если это был не ты — отклони вход: аккаунт останется в гостевом режиме и никто не получит доступ.",
    en: "The request is bound to the current session and can be used once. If this was not you, reject the login: the account stays in guest mode and nobody gets access.",
  },
  "auth.approve": {
    ru: "✅ Подтвердить вход",
    en: "✅ Confirm login",
  },
  "auth.reject": {
    ru: "❌ Отклонить",
    en: "❌ Reject",
  },
  "auth.approved": {
    ru: "✅ Вход подтверждён. Приятной игры, {player}.",
    en: "✅ Login confirmed. Have a good game, {player}.",
  },
  "auth.rejected": {
    ru: "❌ Вход отклонён. Игрок останется в гостевом режиме.",
    en: "❌ Login rejected. The player stays in guest mode.",
  },
  "auth.alreadyProcessed": {
    ru: "Этот запрос уже обработан.",
    en: "This request has already been processed.",
  },
  "auth.expired": {
    ru: "Запрос истёк. Зайди на сервер заново.",
    en: "The request has expired. Join the server again.",
  },
  "auth.notYours": {
    ru: "Это запрос не для твоего аккаунта.",
    en: "This request belongs to another account.",
  },
  "auth.unknown": {
    ru: "Запрос не найден или устарел.",
    en: "The request was not found or is out of date.",
  },
  "auth.noActiveSession": {
    ru: "Активной игровой сессии нет — подтверждать нечего.",
    en: "There is no active game session to confirm.",
  },
  "auth.staleSession": {
    ru: "Сессия изменилась, запрос устарел. Зайди на сервер заново.",
    en: "The session has changed and the request is out of date. Join the server again.",
  },
  "auth.rateLimited": {
    ru: "Слишком много попыток, попробуй позже.",
    en: "Too many attempts, try again later.",
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
  return interpolate(entry[locale] ?? entry[DEFAULT_LOCALE], params)
}

export type PluralForm = "one" | "few" | "many" | "other"

/** plural families: the base key plus a form suffix stored as sibling entries */
export type PluralKey = "status.players"

/** ru follows the cldr one/few/many split, en only distinguishes one */
function pluralForm(locale: Locale, count: number): PluralForm {
  if (locale === "ru") {
    if (!Number.isInteger(count)) return "other"
    const mod100 = Math.abs(count) % 100
    const mod10 = mod100 % 10
    if (mod10 === 1 && mod100 !== 11) return "one"
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "few"
    return "many"
  }
  if (!Number.isInteger(count)) return "other"
  return count === 1 ? "one" : "other"
}

/**
 * plural lookup: resolves `<key>.<form>` and injects {count}. falls back to
 * `.other` so a family missing a form still renders instead of dying.
 */
export function tPlural(
  locale: Locale,
  key: PluralKey,
  count: number,
  params: Record<string, string | number> = {},
): string {
  const withCount = { ...params, count }
  const entry =
    messages[`${key}.${pluralForm(locale, count)}` as MessageKey] ??
    messages[`${key}.other` as MessageKey]
  if (!entry) return key
  return interpolate(entry[locale] ?? entry[DEFAULT_LOCALE], withCount)
}

function interpolate(text: string, params: Record<string, string | number>): string {
  let result = text
  for (const [name, value] of Object.entries(params)) {
    result = result.replaceAll(`{${name}}`, String(value))
  }
  return result
}

/** user language with config fallback */
export function langOf(language: Locale | undefined, fallback: Locale): Locale {
  return language ?? fallback
}

/** user-facing display name of an account status in the given locale */
export function statusLabel(locale: Locale, status: string): string {
  return t(locale, `account.status.${status}` as MessageKey)
}
