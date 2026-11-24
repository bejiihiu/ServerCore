import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto"

const TOKEN_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

/** json value that survived a JSON.parse round trip */
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }

/**
 * deterministic json serialization with sorted keys.
 * both sides must hash the exact same byte string, so key order has to be
 * normalized regardless of how the peer serialized its object. accepts
 * unknown because the input is always JSON-shaped by construction; anything
 * undefined is dropped (top-level undefined hashes as null).
 */
export function canonicalize(value: unknown): string {
  if (value === undefined) return "null"
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value) ?? "null"
  }

  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalize(item)).join(",")}]`
  }

  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))

  const body = entries.map(([key, v]) => `${JSON.stringify(key)}:${canonicalize(v)}`).join(",")
  return `{${body}}`
}

export function sha256Hex(input: string): string {
  return createHash("sha256").update(input, "utf8").digest("hex")
}

export function hmacSha256Hex(secret: string, input: string): string {
  return createHmac("sha256", secret).update(input, "utf8").digest("hex")
}

/** string compare that does not leak timing info */
export function safeEqualHex(a: string, b: string): boolean {
  const left = Buffer.from(a, "utf8")
  const right = Buffer.from(b, "utf8")
  if (left.length !== right.length) return false
  return timingSafeEqual(left, right)
}

export function randomToken(length: number): string {
  const bytes = randomBytes(length)
  let out = ""
  for (let i = 0; i < length; i += 1) {
    const byte = bytes[i]
    if (byte === undefined) break
    out += TOKEN_CHARSET[byte % TOKEN_CHARSET.length]
  }
  return out
}

export function randomHex(bytes: number): string {
  return randomBytes(bytes).toString("hex")
}
