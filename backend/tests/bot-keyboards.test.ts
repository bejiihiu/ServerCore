import { describe, expect, test } from "bun:test"
import {
  accountKeyboard,
  deleteConfirmKeyboard,
  toggleKey,
  CALLBACK,
} from "../src/bot/keyboards.js"
import {
  actionRow,
  backButton,
  backRow,
  callbackData,
  cancelButton,
  confirmKeyboard,
  inlineKeyboard,
  menuGrid,
  paginationRow,
  toggleButton,
  CALLBACK_DATA_MAX_BYTES,
  type ActionButton,
} from "../src/bot/ui/keyboards/index.js"

function button(label: string, data: string): ActionButton {
  return { label, callbackData: data }
}

describe("callback data scheme", () => {
  test("joins parts with colons", () => {
    expect(callbackData("menu", "status")).toBe("menu:status")
    expect(callbackData("vote", "page", "3")).toBe("vote:page:3")
  })

  test("rejects data over 64 bytes", () => {
    const long = "x".repeat(CALLBACK_DATA_MAX_BYTES + 1)
    expect(() => callbackData(long)).toThrow(/exceeds/)
  })

  test("accepts data at exactly 64 bytes", () => {
    const exact = "x".repeat(CALLBACK_DATA_MAX_BYTES)
    expect(callbackData(exact)).toBe(exact)
  })
})

describe("actionRow", () => {
  test("maps plain buttons", () => {
    const row = actionRow([button("Статус", "menu:status")])
    expect(row).toEqual([{ text: "Статус", callback_data: "menu:status" }])
  })

  test("keeps style and icon when given", () => {
    const row = actionRow([{ label: "Рестарт", callbackData: "srv:restart", style: "danger", iconEmojiId: "42" }])
    expect(row).toEqual([
      { text: "Рестарт", callback_data: "srv:restart", style: "danger", icon_custom_emoji_id: "42" },
    ])
  })

  test("disabled buttons carry no callback data", () => {
    const row = actionRow([{ label: "Подождите…", callbackData: "x:y", disabled: true }])
    expect(row).toEqual([{ text: "Подождите…", disabled: {} }])
  })

  test("disabled keeps style", () => {
    const row = actionRow([{ label: "…", callbackData: "x:y", disabled: true, style: "primary" }])
    expect(row).toEqual([{ text: "…", disabled: {}, style: "primary" }])
  })
})

describe("inlineKeyboard", () => {
  test("copies rows defensively", () => {
    const row = actionRow([button("a", "a:b")])
    const markup = inlineKeyboard([row])
    expect(markup.inline_keyboard).toHaveLength(1)
    expect(markup.force_reply).toBeUndefined()
  })

  test("forceReply flag lands on the markup (bot api 10.3)", () => {
    const markup = inlineKeyboard([[actionRow([button("a", "a:b")])[0] as never]], { forceReply: true })
    expect(markup.force_reply).toBe(true)
  })
})

describe("menuGrid", () => {
  test("two columns by default", () => {
    const rows = menuGrid([button("1", "a:1"), button("2", "a:2"), button("3", "a:3")])
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveLength(2)
    expect(rows[1]).toHaveLength(1)
  })

  test("single item renders full width", () => {
    const rows = menuGrid([button("only", "a:1")])
    expect(rows).toEqual([[{ text: "only", callback_data: "a:1" }]])
  })

  test("rejects zero columns", () => {
    expect(() => menuGrid([], 0)).toThrow(/column/)
  })
})

describe("paginationRow", () => {
  test("middle page: both arrows active", () => {
    const [prev, counter, next] = paginationRow({ page: 1, totalPages: 3, callbackPrefix: "list" })
    expect(prev).toEqual({ text: "‹", callback_data: "list:page:0" })
    expect(counter).toEqual({ text: "2 / 3", disabled: {} })
    expect(next).toEqual({ text: "›", callback_data: "list:page:2" })
  })

  test("first page: prev is disabled", () => {
    const [prev] = paginationRow({ page: 0, totalPages: 3, callbackPrefix: "list" })
    expect(prev).toEqual({ text: "‹", disabled: {} })
  })

  test("last page: next is disabled", () => {
    const [, , next] = paginationRow({ page: 2, totalPages: 3, callbackPrefix: "list" })
    expect(next).toEqual({ text: "›", disabled: {} })
  })

  test("single page: both ends disabled", () => {
    const [prev, , next] = paginationRow({ page: 0, totalPages: 1, callbackPrefix: "list" })
    expect(prev).toEqual({ text: "‹", disabled: {} })
    expect(next).toEqual({ text: "›", disabled: {} })
  })

  test("custom labels", () => {
    const [prev] = paginationRow({ page: 1, totalPages: 3, callbackPrefix: "list", labels: { prev: "назад" } })
    expect(prev).toEqual({ text: "назад", callback_data: "list:page:0" })
  })
})

describe("back navigation", () => {
  test("the return target is the callback data itself", () => {
    expect(backButton("menu:back")).toEqual({ text: "‹ Назад", callback_data: "menu:back" })
    expect(backRow("menu:back")).toEqual([{ text: "‹ Назад", callback_data: "menu:back" }])
  })

  test("custom label", () => {
    expect(backButton("menu:back", "← в меню")).toEqual({ text: "← в меню", callback_data: "menu:back" })
  })
})

describe("toggleButton", () => {
  test("marks state in the label", () => {
    const on = toggleButton({ label: "Уведомления", on: true, callbackData: "set:notif" })
    expect(on).toEqual({ text: "● Уведомления", callback_data: "set:notif" })
    const off = toggleButton({ label: "Уведомления", on: false, callbackData: "set:notif" })
    expect(off).toEqual({ text: "○ Уведомления", callback_data: "set:notif" })
  })

  test("custom markers", () => {
    const btn = toggleButton({
      label: "Тема",
      on: false,
      callbackData: "set:theme",
      marker: { on: "тёмная:", off: "светлая:" },
    })
    expect(btn.text).toBe("светлая: Тема")
  })
})

describe("confirmKeyboard", () => {
  test("destructive confirm is danger-styled", () => {
    const rows = confirmKeyboard({
      confirm: { label: "Удалить", callbackData: "acc:delete:ok" },
      cancel: { label: "Отмена", callbackData: "acc:delete:no" },
      tone: "danger",
    })
    expect(rows).toEqual([
      [
        { text: "Отмена", callback_data: "acc:delete:no" },
        { text: "Удалить", callback_data: "acc:delete:ok", style: "danger" },
      ],
    ])
  })

  test("approval confirm is success-styled", () => {
    const rows = confirmKeyboard({
      confirm: { label: "Одобрить", callbackData: "adm:ok" },
      cancel: { label: "Отклонить", callbackData: "adm:no" },
      tone: "success",
    })
    expect(rows[0]?.[1]).toEqual({ text: "Одобрить", callback_data: "adm:ok", style: "success" })
  })

  test("no tone: plain buttons", () => {
    const rows = confirmKeyboard({
      confirm: { label: "Да", callbackData: "a:yes" },
      cancel: { label: "Нет", callbackData: "a:no" },
    })
    expect(rows[0]?.[1]).toEqual({ text: "Да", callback_data: "a:yes" })
  })
})

describe("cancelButton", () => {
  test("default and custom label", () => {
    expect(cancelButton("a:cancel")).toEqual({ text: "✕ Отмена", callback_data: "a:cancel" })
    expect(cancelButton("a:cancel", "Не сейчас")).toEqual({ text: "Не сейчас", callback_data: "a:cancel" })
  })
})

describe("account keyboard", () => {
  test("toggle button invites the other language, not the current one", () => {
    const ru = accountKeyboard((key) => key, "ru")
    const en = accountKeyboard((key) => key, "en")
    // raw keys are the labels here; the toggle row must follow the locale
    expect(ru.inline_keyboard.flat().some((b) => b.text === "bot.kb.toggle_to_en")).toBe(true)
    expect(en.inline_keyboard.flat().some((b) => b.text === "bot.kb.toggle_to_ru")).toBe(true)
    expect(toggleKey("ru")).toBe("bot.kb.toggle_to_en")
    expect(toggleKey("en")).toBe("bot.kb.toggle_to_ru")
  })

  test("delete button carries the account callback", () => {
    const kb = accountKeyboard((key) => key, "ru")
    expect(kb.inline_keyboard[0]?.[0]).toEqual({ text: "bot.kb.delete", callback_data: CALLBACK.accountDelete })
  })
})

describe("delete confirmation uses its own callback namespace", () => {
  test("stale linking keyboard cannot confirm a deletion", () => {
    const kb = deleteConfirmKeyboard((key) => key)
    const datas = kb.inline_keyboard.flat().map((b) => (b as { callback_data?: string }).callback_data)
    expect(datas).toContain(CALLBACK.deleteConfirm)
    expect(datas).toContain(CALLBACK.deleteCancel)
    // the linking conversation waits on link:* — the two flows must never share data
    expect(datas).not.toContain(CALLBACK.linkConfirm)
    expect(datas).not.toContain(CALLBACK.linkCancel)
    expect(CALLBACK.deleteConfirm).not.toBe(CALLBACK.linkConfirm)
  })
})
