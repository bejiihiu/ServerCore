import { describe, expect, test } from "bun:test"
import ruDict from "../src/i18n/locales/ru.json"
import enDict from "../src/i18n/locales/en.json"
import { createI18n, defaultPlural, ruPlural, type LocaleDict } from "../src/i18n/index.js"
import { EmojiRegistry, createEmojiRenderer } from "../src/i18n/emoji.js"
import { createTranslator, escapeHtml } from "../src/bot/ui/text/index.js"
import {
  MENU_CALLBACK,
  ACCOUNT_CALLBACK,
  mainMenuScreen,
  statusScreen,
  accountCardScreen,
  noticeScreen,
  applicationCardScreen,
  confirmDialogScreen,
  languagePickerScreen,
  paginatedListScreen,
  loadingScreen,
  hintScreen,
} from "../src/bot/ui/screens/index.js"

function ui(locale: "ru" | "en" = "ru"): {
  t: ReturnType<typeof createTranslator>
  e: ReturnType<typeof createEmojiRenderer>
} {
  const i18n = createI18n(
    { ru: ruDict as LocaleDict, en: enDict as LocaleDict },
    { defaultLocale: "ru", pluralRules: { ru: ruPlural, en: defaultPlural } },
  )
  const t = createTranslator(i18n.provider(locale))
  const e = createEmojiRenderer(new EmojiRegistry(false))
  return { t, e }
}

describe("main menu screen", () => {
  test("not linked: neutral dot, subtitle, hint blockquote, enabled buttons only", () => {
    const { t, e } = ui()
    const screen = mainMenuScreen({
      t,
      e,
      account: { linked: false },
      actions: [
        { key: "status", enabled: true, callbackData: MENU_CALLBACK.status },
        { key: "account", enabled: false, callbackData: MENU_CALLBACK.account },
      ],
    })
    expect(screen.text).toContain("⚪")
    expect(screen.text).toContain("<b>ServerCore</b> — доступ на сервер")
    expect(screen.text).toContain("Аккаунт не привязан")
    expect(screen.text).toContain("<blockquote expandable>")
    expect(screen.markup?.inline_keyboard).toEqual([
      [{ text: "Статус сервера", callback_data: "menu:status" }],
    ])
  })

  test("linked: green dot and nickname in the subtitle", () => {
    const { t, e } = ui()
    const screen = mainMenuScreen({
      t,
      e,
      account: { linked: true, nickname: "Steve" },
      actions: [{ key: "status", enabled: true, callbackData: MENU_CALLBACK.status }],
    })
    expect(screen.text).toContain("🟢")
    expect(screen.text).toContain("привязан: Steve")
    expect(screen.text).not.toContain("blockquote")
  })

  test("user-controlled data never breaks the html", () => {
    const { t, e } = ui()
    const screen = mainMenuScreen({
      t,
      e,
      account: { linked: true, nickname: "<script>alert(1)</script>" },
      actions: [],
    })
    expect(screen.text).not.toContain("<script>")
    expect(screen.text).toContain(escapeHtml("<script>alert(1)</script>"))
  })

  test("no enabled actions: no markup at all", () => {
    const { t, e } = ui()
    const screen = mainMenuScreen({ t, e, account: { linked: false }, actions: [] })
    expect(screen.markup).toBeUndefined()
  })

  test("multiple enabled actions form a grid", () => {
    const { t, e } = ui()
    const screen = mainMenuScreen({
      t,
      e,
      account: { linked: false },
      actions: [
        { key: "status", enabled: true, callbackData: MENU_CALLBACK.status },
        { key: "account", enabled: true, callbackData: MENU_CALLBACK.account },
        { key: "help", enabled: true, callbackData: MENU_CALLBACK.help },
      ],
    })
    expect(screen.markup?.inline_keyboard).toHaveLength(2)
    expect(screen.markup?.inline_keyboard[0]).toHaveLength(2)
    expect(screen.markup?.inline_keyboard[1]).toHaveLength(1)
  })

  test("copy has no slop: no greetings, no exclamation marks", () => {
    const { t, e } = ui()
    const screen = mainMenuScreen({
      t,
      e,
      account: { linked: false },
      actions: [{ key: "status", enabled: true, callbackData: MENU_CALLBACK.status }],
    })
    expect(screen.text).not.toMatch(/Привет|Добро пожаловать|Welcome|!/)
  })

  test("english locale renders the same screen structure", () => {
    const { t, e } = ui("en")
    const screen = mainMenuScreen({
      t,
      e,
      account: { linked: false },
      actions: [{ key: "status", enabled: true, callbackData: MENU_CALLBACK.status }],
    })
    expect(screen.text).toContain("<b>ServerCore</b> — server access")
    expect(screen.text).toContain("No account linked")
    expect(screen.markup?.inline_keyboard[0]?.[0]?.text).toBe("Server status")
  })
})

describe("status screen", () => {
  test("online server: green dot, pluralized players, refresh + back rows", () => {
    const { t, e } = ui()
    const screen = statusScreen({ t, e, server: { online: true, players: 3 }, account: { linked: true, nickname: "Steve" } })
    expect(screen.text).toContain("🟢")
    expect(screen.text).toContain("<b>Сервер</b> онлайн")
    expect(screen.text).toContain("<b>Игроки</b> 3 игрока")
    expect(screen.text).toContain("<b>Аккаунт</b> привязан: Steve")
    const rows = screen.markup?.inline_keyboard ?? []
    expect(rows).toHaveLength(2)
    expect(rows[0]?.[0]).toEqual({ text: "Обновить", callback_data: "menu:status" })
    expect(rows[1]?.[0]).toEqual({ text: "‹ Назад", callback_data: "menu:back" })
  })

  test("reported down: red dot and the down wording, not 'no data'", () => {
    const { t, e } = ui()
    const screen = statusScreen({ t, e, server: { online: false, players: 0 }, account: { linked: false } })
    expect(screen.text).toContain("🔴")
    expect(screen.text).toContain("<b>Сервер</b> недоступен")
    expect(screen.text).toContain("<b>Игроки</b> 0 игроков")
  })

  test("no live link: white dot and no invented player count", () => {
    const { t, e } = ui()
    const screen = statusScreen({ t, e, server: { online: null, players: 0 }, account: { linked: false } })
    expect(screen.text).toContain("⚪")
    expect(screen.text).toContain("<b>Сервер</b> нет данных")
    expect(screen.text).not.toContain("<b>Игроки</b>")
  })

  test("player count uses ru plural forms", () => {
    const { t, e } = ui()
    for (const [count, word] of [[1, "игрок"], [2, "игрока"], [5, "игроков"], [11, "игроков"], [22, "игрока"]] as const) {
      const screen = statusScreen({ t, e, server: { online: true, players: count }, account: { linked: false } })
      expect(screen.text).toContain(`${count} ${word}`)
    }
  })

  test("english plural: one player vs players", () => {
    const { t, e } = ui("en")
    expect(statusScreen({ t, e, server: { online: true, players: 1 }, account: { linked: false } }).text).toContain("1 player")
    expect(statusScreen({ t, e, server: { online: true, players: 2 }, account: { linked: false } }).text).toContain("2 players")
  })
})

describe("account card screen", () => {
  test("linked: fields render, role/date omitted when absent", () => {
    const { t, e } = ui()
    const screen = accountCardScreen({ t, e, account: { linked: true, nickname: "Steve", status: "Активен" } })
    expect(screen.text).toContain("🟢")
    expect(screen.text).toContain("<b>Ник</b> Steve")
    expect(screen.text).toContain("<b>Статус</b> Активен")
    expect(screen.text).not.toContain("Роль")
    expect(screen.text).not.toContain("Дата привязки")
  })

  test("linked with role and date: all fields present", () => {
    const { t, e } = ui()
    const screen = accountCardScreen({
      t,
      e,
      account: { linked: true, nickname: "Steve", role: "модератор", linkedAt: "24.11.2026" },
    })
    expect(screen.text).toContain("<b>Роль</b> модератор")
    expect(screen.text).toContain("<b>Дата привязки</b> 24.11.2026")
  })

  test("unlinked: neutral dot and hint", () => {
    const { t, e } = ui()
    const screen = accountCardScreen({ t, e, account: { linked: false } })
    expect(screen.text).toContain("⚪")
    expect(screen.text).toContain("<b>Аккаунт не привязан</b>")
    expect(screen.text).toContain("<blockquote expandable>")
    expect(screen.markup?.inline_keyboard.at(-1)?.[0]).toEqual({ text: "‹ Назад", callback_data: "menu:back" })
  })

  test("default actions: refresh, then back", () => {
    const { t, e } = ui()
    const screen = accountCardScreen({ t, e, account: { linked: true, nickname: "Steve" } })
    const rows = screen.markup?.inline_keyboard ?? []
    expect(rows[0]?.[0]).toEqual({ text: "Обновить", callback_data: ACCOUNT_CALLBACK.refresh })
    expect(rows[1]?.[0]).toEqual({ text: "‹ Назад", callback_data: "menu:back" })
  })

  test("custom actions land above the back row", () => {
    const { t, e } = ui()
    const screen = accountCardScreen({
      t,
      e,
      account: { linked: true, nickname: "Steve" },
      actions: [
        { label: "Обновить", callbackData: ACCOUNT_CALLBACK.refresh },
        { label: "Язык: English", callbackData: "lang:toggle" },
        { label: "Удалить аккаунт", callbackData: ACCOUNT_CALLBACK.delete, style: "danger" },
      ],
    })
    const rows = screen.markup?.inline_keyboard ?? []
    expect(rows).toHaveLength(3)
    expect(rows[1]?.[0]).toEqual({ text: "Удалить аккаунт", callback_data: "account:delete", style: "danger" })
    expect(rows[2]?.[0]).toEqual({ text: "‹ Назад", callback_data: "menu:back" })
  })
})

describe("notice screen", () => {
  test("one dot, one bold title, body, aside and collapsed details in that order", () => {
    const { t, e } = ui()
    const screen = noticeScreen({
      t,
      e,
      tone: "attention",
      title: "Ссылка недействительна",
      body: "Код привязан к другой сессии.",
      aside: "Зайди на сервер заново.",
      details: "длинный блок",
      back: "menu:back",
    })
    const lines = screen.text.split("\n")
    expect(lines[0]).toBe("🔴 <b>Ссылка недействительна</b>")
    expect(screen.text).toContain("Код привязан к другой сессии.")
    expect(screen.text.indexOf("<blockquote>")).toBeGreaterThan(screen.text.indexOf("Код привязан"))
    expect(screen.text.indexOf("<blockquote expandable>")).toBeGreaterThan(screen.text.indexOf("<blockquote>"))
    expect(screen.markup?.inline_keyboard.at(-1)?.[0]).toEqual({ text: "‹ Назад", callback_data: "menu:back" })
  })

  test("no actions and no back: no markup", () => {
    const { t, e } = ui()
    const screen = noticeScreen({ t, e, title: "Готово" })
    expect(screen.text).toBe("⚪ <b>Готово</b>")
    expect(screen.markup).toBeUndefined()
  })

  test("every tone maps to its registry dot", () => {
    const { t, e } = ui()
    const tones = { active: "🟢", pending: "🟡", attention: "🔴", neutral: "⚪" } as const
    for (const [tone, dot] of Object.entries(tones)) {
      const screen = noticeScreen({ t, e, tone: tone as keyof typeof tones, title: "x" })
      expect(screen.text.startsWith(dot)).toBe(true)
    }
  })
})

describe("application card screen", () => {
  test("nickname row, collapsed answers, footer and actions", () => {
    const { t, e } = ui()
    const screen = applicationCardScreen({
      t,
      e,
      title: "Проверь заявку",
      nickname: "Steve",
      answers: ["1. Откуда узнал?\nиз чата", "2. Опыт?\nдва года"],
      footer: "Итог: Голосование",
      actions: [{ label: "Отправить анкету", callbackData: "app:submit", style: "primary" }],
    })
    expect(screen.text).toContain("🟡 <b>Проверь заявку</b>")
    expect(screen.text).toContain("<b>Minecraft</b> Steve")
    expect(screen.text).toContain("<blockquote expandable>1. Откуда узнал?")
    expect(screen.text).toContain("Итог: Голосование")
    expect(screen.markup?.inline_keyboard[0]?.[0]).toEqual({
      text: "Отправить анкету",
      callback_data: "app:submit",
      style: "primary",
    })
  })

  test("answers are escaped, so markup in user text cannot break the message", () => {
    const { t, e } = ui()
    const screen = applicationCardScreen({
      t,
      e,
      title: "Заявка",
      nickname: "<b>bad</b>",
      answers: ["<script>x</script>"],
    })
    expect(screen.text).not.toContain("<script>")
    expect(screen.text).toContain("&lt;script&gt;")
  })
})

describe("confirm dialog screen", () => {
  test("dot, title, body, aside blockquote, danger button", () => {
    const { t, e } = ui()
    const screen = confirmDialogScreen({
      t,
      e,
      tone: "attention",
      title: "Удаление аккаунта",
      body: "Доступ к Minecraft будет отозван.",
      aside: "Отменить это нельзя.",
      keyboard: {
        confirm: { label: "Удалить", callbackData: "del:confirm" },
        cancel: { label: "Отмена", callbackData: "del:cancel" },
      },
      buttonTone: "danger",
    })
    expect(screen.text.startsWith("🔴 <b>Удаление аккаунта</b>")).toBe(true)
    expect(screen.text).toContain("Доступ к Minecraft будет отозван.")
    expect(screen.text).toContain("<blockquote>Отменить это нельзя.")
    expect(screen.markup?.inline_keyboard[0]?.[1]).toEqual({
      text: "Удалить",
      callback_data: "del:confirm",
      style: "danger",
    })
  })

  test("default title from i18n when not given", () => {
    const { t, e } = ui()
    const screen = confirmDialogScreen({
      t,
      e,
      body: "точно?",
      keyboard: { confirm: { label: "Да", callbackData: "a:y" }, cancel: { label: "Нет", callbackData: "a:n" } },
    })
    expect(screen.text).toContain("<b>Подтверждение</b>")
  })
})

describe("language picker screen", () => {
  test("marks the active language in the label and keeps both picks alive", () => {
    const { t, e } = ui()
    const screen = languagePickerScreen({
      t,
      e,
      current: "ru",
      options: [
        { code: "ru", label: "Русский", callbackData: "lang:ru" },
        { code: "en", label: "English", callbackData: "lang:en" },
      ],
      back: "menu:back",
    })
    expect(screen.text).toContain("⚪ <b>Язык интерфейса</b>")
    expect(screen.markup?.inline_keyboard[0]).toEqual([
      { text: "● Русский", callback_data: "lang:ru" },
      { text: "○ English", callback_data: "lang:en" },
    ])
  })
})

describe("paginated list screen", () => {
  test("items, pagination, back", () => {
    const { t } = ui()
    const screen = paginatedListScreen({
      t,
      title: "Заявки",
      items: ["• alice — 2 часа", "• bob — 40 минут"],
      page: 0,
      totalPages: 4,
      paginationPrefix: "adm:apps",
      backTarget: "menu:back",
    })
    expect(screen.text).toContain("<b>Заявки</b>")
    expect(screen.text).toContain("• alice — 2 часа")
    const rows = screen.markup?.inline_keyboard ?? []
    expect(rows[0]).toEqual([
      { text: "‹", disabled: {} },
      { text: "1 / 4", disabled: {} },
      { text: "›", callback_data: "adm:apps:page:1" },
    ])
    expect(rows[1]).toEqual([{ text: "‹ Назад", callback_data: "menu:back" }])
  })

  test("empty list: placeholder text", () => {
    const { t } = ui()
    const screen = paginatedListScreen({
      t,
      title: "Заявки",
      items: [],
      page: 0,
      totalPages: 1,
      paginationPrefix: "adm:apps",
    })
    expect(screen.text).toBe("Здесь пока ничего нет")
  })

  test("footer renders under the list", () => {
    const { t } = ui()
    const screen = paginatedListScreen({
      t,
      title: "Логи",
      items: ["• строка"],
      page: 0,
      totalPages: 1,
      paginationPrefix: "adm:logs",
      footer: "всего 12 записей",
    })
    expect(screen.text).toContain("всего 12 записей")
  })
})

describe("loading and hint screens", () => {
  test("loading: italic single line, custom text when the flow knows it", () => {
    const { t } = ui()
    expect(loadingScreen(t)).toEqual({ text: "<i>Загрузка…</i>" })
    expect(loadingScreen(t, "Проверяю…")).toEqual({ text: "<i>Проверяю…</i>" })
  })

  test("hint: one quiet line, no dot", () => {
    expect(hintScreen("пришли ответ текстом")).toEqual({ text: "<i>пришли ответ текстом</i>" })
  })
})
