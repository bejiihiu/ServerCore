import { callbackData, type ActionButton } from "./ui/keyboards/index.js"
import { ACCOUNT_CALLBACK } from "./ui/screens/account.js"
import { MENU_CALLBACK } from "./ui/screens/menu.js"

/**
 * callback data constants; conversations wait on these exact strings.
 * every value is `<namespace>:<action>[:<arg>]` — see CALLBACK_NAMESPACES.
 * menu and account entries come from the screens that own them, so a screen
 * and its handler can never drift apart.
 */
export const CALLBACK = {
  ...MENU_CALLBACK,
  ...ACCOUNT_CALLBACK,
  linkConfirm: callbackData("link", "confirm"),
  linkCancel: callbackData("link", "cancel"),
  // deletion gets its own pair: a stale linking keyboard must never be able
  // to confirm an account deletion
  deleteConfirm: callbackData("del", "confirm"),
  deleteCancel: callbackData("del", "cancel"),
  languageToggle: callbackData("lang", "toggle"),
  langRu: callbackData("lang", "ru"),
  langEn: callbackData("lang", "en"),
  appSubmit: callbackData("app", "submit"),
  appCancel: callbackData("app", "cancel"),
} as const

/**
 * one table of callback namespaces. a handler owns exactly one prefix, so two
 * flows can never answer each other's buttons by accident.
 */
export const CALLBACK_NAMESPACES = {
  menu: "main menu navigation",
  account: "account screen",
  link: "telegram linking conversation",
  del: "account deletion conversation",
  app: "application questionnaire",
  lang: "interface language",
  vote: "group voting",
  auth: "login confirmation",
} as const

export type CallbackNamespace = keyof typeof CALLBACK_NAMESPACES

/** prefix of a callback payload, or null when it carries no namespace */
export function namespaceOf(data: string): string | null {
  const index = data.indexOf(":")
  return index <= 0 ? null : data.slice(0, index)
}

/**
 * account screen buttons: refresh, language switch, deletion when the
 * telegram link exists. the language button names the language it switches
 * to, never the current one.
 */
export function accountActions(params: {
  labels: { refresh: string; language: string; delete: string }
  canDelete: boolean
}): ActionButton[] {
  const actions: ActionButton[] = [
    { label: params.labels.refresh, callbackData: CALLBACK.refresh },
    { label: params.labels.language, callbackData: CALLBACK.languageToggle },
  ]
  if (params.canDelete) {
    actions.push({ label: params.labels.delete, callbackData: CALLBACK.delete, style: "danger" })
  }
  return actions
}
