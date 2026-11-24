/**
 * keyboard factories — pure functions returning plain InlineKeyboardMarkup
 * objects (grammY types), no builder state, no side effects. button api
 * surface used: text, callback_data, style (9.4), icon_custom_emoji_id (9.4),
 * disabled (10.3), force_reply on the markup itself (10.3).
 */

export type ButtonStyle = "danger" | "success" | "primary"

/** spec for a callback button; disabled buttons drop the callback entirely */
export type ActionButton = {
  label: string
  callbackData: string
  style?: ButtonStyle
  /** custom emoji id shown before the label; omit when custom emoji is off */
  iconEmojiId?: string
  disabled?: boolean
}

export type InlineButton =
  | { text: string; callback_data: string; style?: ButtonStyle; icon_custom_emoji_id?: string }
  | { text: string; disabled: Record<string, never>; style?: ButtonStyle; icon_custom_emoji_id?: string }

export type InlineKeyboardMarkup = {
  inline_keyboard: InlineButton[][]
  force_reply?: boolean
}

/**
 * callback data scheme: <ns>:<action>[:<arg>] — see DESIGN.md. throws on
 * overflow instead of silently truncating (the api rejects >64 bytes anyway).
 */
export const CALLBACK_DATA_MAX_BYTES = 64

export function callbackData(...parts: readonly string[]): string {
  const data = parts.join(":")
  const bytes = Buffer.byteLength(data, "utf8")
  if (bytes > CALLBACK_DATA_MAX_BYTES) {
    throw new Error(`callback data exceeds ${CALLBACK_DATA_MAX_BYTES} bytes: "${data}" (${bytes})`)
  }
  return data
}

function toInlineButton(spec: ActionButton): InlineButton {
  const style = spec.style === undefined ? {} : { style: spec.style }
  const icon = spec.iconEmojiId === undefined ? {} : { icon_custom_emoji_id: spec.iconEmojiId }
  if (spec.disabled === true) {
    return { text: spec.label, disabled: {}, ...style, ...icon }
  }
  return { text: spec.label, callback_data: spec.callbackData, ...style, ...icon }
}

/** assembles rows into a markup; forceReply turns it into a reply nudge */
export function inlineKeyboard(
  rows: ReadonlyArray<readonly InlineButton[]>,
  options: { forceReply?: boolean } = {},
): InlineKeyboardMarkup {
  const markup: InlineKeyboardMarkup = { inline_keyboard: rows.map((row) => [...row]) }
  if (options.forceReply === true) markup.force_reply = true
  return markup
}

/** one horizontal row of action buttons */
export function actionRow(buttons: ReadonlyArray<ActionButton>): InlineButton[] {
  return buttons.map(toInlineButton)
}

/**
 * menu grid: action buttons laid out in fixed-width rows. the last row holds
 * the remainder; a single item renders as a full-width row.
 */
export function menuGrid(
  items: ReadonlyArray<ActionButton>,
  columns = 2,
): InlineButton[][] {
  if (columns < 1) throw new Error(`menu grid needs at least 1 column, got ${columns}`)
  const rows: InlineButton[][] = []
  for (let index = 0; index < items.length; index += columns) {
    rows.push(items.slice(index, index + columns).map(toInlineButton))
  }
  return rows
}

export type PaginationSpec = {
  page: number
  totalPages: number
  /** callback prefix; rendered data: <prefix>:page:<n> */
  callbackPrefix: string
  labels?: { prev?: string; next?: string }
}

const PAGINATION_DEFAULT_LABELS = { prev: "‹", next: "›" } as const

/** prev / page counter / next row; ends degrade to disabled buttons */
export function paginationRow(spec: PaginationSpec): InlineButton[] {
  const labels = { ...PAGINATION_DEFAULT_LABELS, ...spec.labels }
  const counter = toInlineButton({
    label: `${spec.page + 1} / ${spec.totalPages}`,
    callbackData: callbackData(spec.callbackPrefix, "noop"),
    disabled: true,
  })
  const prev = toInlineButton({
    label: labels.prev,
    callbackData: callbackData(spec.callbackPrefix, "page", String(spec.page - 1)),
    disabled: spec.page <= 0,
  })
  const next = toInlineButton({
    label: labels.next,
    callbackData: callbackData(spec.callbackPrefix, "page", String(spec.page + 1)),
    disabled: spec.page >= spec.totalPages - 1,
  })
  return [prev, counter, next]
}

/**
 * back-navigation: the return target travels inside the callback data, so
 * keyboards stay stateless. the target must be a registered callback id
 * ("menu:back" etc) — the flow's handler owns where back leads.
 */
export function backButton(target: string, label = "‹ Назад"): InlineButton {
  return toInlineButton({ label, callbackData: target })
}

/** one back button on its own row */
export function backRow(target: string, label?: string): InlineButton[] {
  return [backButton(target, label)]
}

export type ToggleSpec = {
  label: string
  on: boolean
  /** render the state as a unicode marker in the label */
  marker?: { on: string; off: string }
  callbackData: string
  style?: ButtonStyle
  iconEmojiId?: string
}

/**
 * toggle button with visible state: the state marker lives in the label
 * (telegram has no switch control). caller owns the texts incl. markers.
 */
export function toggleButton(spec: ToggleSpec): InlineButton {
  const marker = spec.marker ?? { on: "●", off: "○" }
  const state = spec.on ? marker.on : marker.off
  return toInlineButton({
    label: `${state} ${spec.label}`,
    callbackData: spec.callbackData,
    style: spec.style,
    iconEmojiId: spec.iconEmojiId,
  })
}

export type ConfirmSpec = {
  confirm: { label: string; callbackData: string }
  cancel: { label: string; callbackData: string }
  /** danger confirm (destructive) vs success confirm (approving) */
  tone?: "danger" | "success"
}

/** confirm/cancel pair: destructive confirm is danger-styled */
export function confirmKeyboard(spec: ConfirmSpec): InlineButton[][] {
  const confirmStyle: ButtonStyle | undefined = spec.tone === "danger" ? "danger" : spec.tone === "success" ? "success" : undefined
  return [
    [
      toInlineButton({ label: spec.cancel.label, callbackData: spec.cancel.callbackData }),
      toInlineButton({ label: spec.confirm.label, callbackData: spec.confirm.callbackData, style: confirmStyle }),
    ],
  ]
}

/** standalone cancel button (own row) */
export function cancelButton(callbackData: string, label = "✕ Отмена"): InlineButton {
  return toInlineButton({ label, callbackData })
}
