import type { Clock } from "./ports.js"

/** real clock, everything time-based goes through this so tests can freeze it */
export const systemClock: Clock = {
  nowMs: () => Date.now(),
}
