import type { Translator } from "../text/index.js"
import { italic } from "../text/index.js"
import type { ScreenMessage } from "./types.js"

/** loading placeholder — a single italic line, no fake progress */
export function loadingScreen(t: Translator): ScreenMessage {
  return { text: italic(t.t("common.loading")) }
}
