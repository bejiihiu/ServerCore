import type { InlineKeyboardMarkup } from "../keyboards/index.js"

/**
 * a screen is pure data: rendered html text + optional reply markup + optional
 * message effect. builders must not touch the api — sending/editing lives in
 * ../sending.ts, handlers own the effects.
 */
export type ScreenMessage = {
  text: string
  markup?: InlineKeyboardMarkup
}
