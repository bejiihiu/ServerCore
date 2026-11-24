import type { Translator } from "../text/index.js"
import { italic } from "../text/index.js"
import type { ScreenMessage } from "./types.js"

/**
 * loading placeholder — a single italic line, no fake progress. flows that
 * know exactly what they are waiting for pass their own line.
 */
export function loadingScreen(t: Translator, text?: string): ScreenMessage {
  return { text: italic(text ?? t.t("common.loading")) }
}

/**
 * one quiet line: an in-flow nudge ("send the answer as text") or a validation
 * hint. no dot, no title — the question or screen above it stays the subject.
 */
export function hintScreen(text: string): ScreenMessage {
  return { text: italic(text) }
}
