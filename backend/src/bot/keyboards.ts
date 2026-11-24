import { InlineKeyboard } from "grammy"

/** callback data constants; conversations wait on these exact strings */
export const CALLBACK = {
  linkConfirm: "link:confirm",
  linkCancel: "link:cancel",
  // deletion gets its own pair: a stale linking keyboard must never be able
  // to confirm an account deletion
  deleteConfirm: "del:confirm",
  deleteCancel: "del:cancel",
  accountDelete: "account:delete",
  languageToggle: "lang:toggle",
} as const

/** confirmation keyboard for the linking flow */
export function linkingKeyboard(t: (key: "bot.kb.confirm" | "bot.kb.cancel") => string): InlineKeyboard {
  return new InlineKeyboard()
    .text(t("bot.kb.confirm"), CALLBACK.linkConfirm)
    .text(t("bot.kb.cancel"), CALLBACK.linkCancel)
}

/** keyboard under the account screen; the toggle label follows the current locale */
export function accountKeyboard(
  t: (key: "bot.kb.delete" | "bot.kb.toggle_to_en" | "bot.kb.toggle_to_ru") => string,
  current: "ru" | "en",
): InlineKeyboard {
  return new InlineKeyboard()
    .text(t("bot.kb.delete"), CALLBACK.accountDelete)
    .row()
    .text(t(toggleKey(current)), CALLBACK.languageToggle)
}

/** confirmation keyboard for account deletion */
export function deleteConfirmKeyboard(t: (key: "bot.kb.confirm" | "bot.kb.cancel") => string): InlineKeyboard {
  return new InlineKeyboard()
    .text(t("bot.kb.confirm"), CALLBACK.deleteConfirm)
    .text(t("bot.kb.cancel"), CALLBACK.deleteCancel)
}

/** which label the language toggle shows for a given current locale */
export function toggleKey(current: "ru" | "en"): "bot.kb.toggle_to_en" | "bot.kb.toggle_to_ru" {
  return current === "ru" ? "bot.kb.toggle_to_en" : "bot.kb.toggle_to_ru"
}
