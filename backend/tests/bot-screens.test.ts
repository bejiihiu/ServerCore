import { describe, expect, test } from "bun:test"
import { providerFor } from "../src/i18n/index.js"
import { EmojiRegistry, createEmojiRenderer } from "../src/i18n/emoji.js"
import { createTranslator } from "../src/bot/ui/text/index.js"
import { mainMenuScreen, MENU_CALLBACK, statusScreen, accountCardScreen, confirmDialogScreen, paginatedListScreen, loadingScreen } from "../src/bot/ui/screens/index.js"
import { escapeHtml } from "../src/bot/ui/text/index.js"

function ui(): { t: ReturnType<typeof createTranslator>; e: ReturnType<typeof createEmojiRenderer> } {
  const t = createTranslator(providerFor("ru"))
  const e = createEmojiRenderer(new EmojiRegistry(false))
  return { t, e }
}

describe("main menu screen", () => {
  test("not linked: neutral dot, subtitle, hint blockquote, status button only", () => {
    const { t, e } = ui()
    const screen = mainMenuScreen({
      t,
      e,
      account: { linked: false },
      actions: [
        { key: "status", enabled: true, callbackData: MENU_CALLBACK.status },
        { key: "account", enabled: false, callbackData: "account:open" },
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
        { key: "status", enabled: true, callbackData: "menu:status" },
        { key: "account", enabled: true, callbackData: "account:open" },
        { key: "help", enabled: true, callbackData: "menu:help" },
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

  test("offline server: red dot and no-data wording", () => {
    const { t, e } = ui()
    const screen = statusScreen({ t, e, server: { online: false, players: 0 }, account: { linked: false } })
    expect(screen.text).toContain("🔴")
    expect(screen.text).toContain("<b>Сервер</b> нет данных")
    expect(screen.text).toContain("<b>Игроки</b> 0 игроков")
  })

  test("player count uses ru plural forms", () => {
    const { t, e } = ui()
    for (const [count, word] of [[1, "игрок"], [2, "игрока"], [5, "игроков"], [11, "игроков"], [22, "игрока"]] as const) {
      const screen = statusScreen({ t, e, server: { online: true, players: count }, account: { linked: false } })
      expect(screen.text).toContain(`${count} ${word}`)
    }
  })
})

describe("account card screen", () => {
  test("linked: fields render, role/date omitted when absent", () => {
    const { t, e } = ui()
    const screen = accountCardScreen({ t, e, account: { linked: true, nickname: "Steve" } })
    expect(screen.text).toContain("🟢")
    expect(screen.text).toContain("<b>Ник</b> Steve")
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
    expect(screen.markup?.inline_keyboard[0]?.[0]).toEqual({ text: "‹ Назад", callback_data: "menu:back" })
  })
})

describe("confirm dialog screen", () => {
  test("title, body, aside blockquote, danger pair", () => {
    const { t } = ui()
    const screen = confirmDialogScreen({
      t,
      title: "Отвязать аккаунт",
      body: "Ник Steve потеряет доступ к серверу.",
      aside: "Привязку можно восстановить только через новую ссылку из игры.",
      keyboard: {
        confirm: { label: "Отвязать", callbackData: "acc:unlink:ok" },
        cancel: { label: "Оставить", callbackData: "acc:unlink:no" },
      },
      tone: "danger",
    })
    expect(screen.text).toContain("<b>Отвязать аккаунт</b>")
    expect(screen.text).toContain("Ник Steve потеряет доступ к серверу.")
    expect(screen.text).toContain("<blockquote>Привязку можно восстановить")
    expect(screen.markup?.inline_keyboard[0]?.[1]).toEqual({ text: "Отвязать", callback_data: "acc:unlink:ok", style: "danger" })
  })

  test("default title from i18n when not given", () => {
    const { t } = ui()
    const screen = confirmDialogScreen({
      t,
      body: "точно?",
      keyboard: { confirm: { label: "Да", callbackData: "a:y" }, cancel: { label: "Нет", callbackData: "a:n" } },
    })
    expect(screen.text).toContain("<b>Подтверждение</b>")
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

describe("loading screen", () => {
  test("italic single line", () => {
    const { t } = ui()
    expect(loadingScreen(t)).toEqual({ text: "<i>Загрузка…</i>" })
  })
})
