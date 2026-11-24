import { isValidLinkTokenFormat } from "../services/token-service.js"

export type StartPayloadResult =
  | { ok: true; token: string }
  | { ok: false; reason: "MISSING" | "BAD_FORMAT" }

/**
 * parses the /start deep-link payload: t.me/<bot>?start=<token>.
 * the payload is a one-time linking token, never a raw telegram id.
 */
export function parseStartPayload(match: string): StartPayloadResult {
  const payload = match.trim()
  if (payload.length === 0) return { ok: false, reason: "MISSING" }
  if (!isValidLinkTokenFormat(payload)) return { ok: false, reason: "BAD_FORMAT" }
  return { ok: true, token: payload }
}
