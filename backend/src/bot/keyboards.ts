import { InlineKeyboard } from "grammy"
import { languageToggleLabel, languageToggleNewLabel, t, type Language } from "./i18n.js"

/** callback data constants; conversations wait on these exact strings */
export const CALLBACK = {
  linkConfirm: "link:confirm",
  linkCancel: "link:cancel",
  accountDelete: "account:delete",
  languageToggle: "lang:toggle",
} as const

/** confirmation keyboard for the linking flow */
export function linkingKeyboard(lang: Language): InlineKeyboard {
  return new InlineKeyboard()
    .text(t(lang, "kbConfirmDelete"), CALLBACK.linkConfirm)
    .text(t(lang, "kbCancel"), CALLBACK.linkCancel)
}

/** keyboard under the account screen */
export function accountKeyboard(lang: Language): InlineKeyboard {
  return new InlineKeyboard()
    .text(t(lang, "kbDelete"), CALLBACK.accountDelete)
    .row()
    .text(languageToggleLabel(lang), CALLBACK.languageToggle)
}

/** confirmation keyboard for account deletion */
export function deleteConfirmKeyboard(lang: Language): InlineKeyboard {
  return new InlineKeyboard()
    .text(t(lang, "kbConfirmDelete"), CALLBACK.linkConfirm)
    .text(t(lang, "kbCancel"), CALLBACK.linkCancel)
}

export { languageToggleNewLabel }
