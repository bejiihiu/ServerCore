import { canonicalize, hmacSha256Hex, safeEqualHex } from "../../utils/crypto.js"
import { envelopeMetaSchema, payloadSchemas, type Envelope, type MessageType, type WireMessage } from "./message-types.js"

/** number of bytes of hmac output we accept, hex encoded = 64 chars */
export const HMAC_LENGTH = 64

/**
 * builds the exact byte string that gets signed: canonical json of the
 * envelope without the hmac field. key order and absent optional fields are
 * normalized so both ends hash the same bytes no matter how they built the
 * object.
 */
export function signingInput(meta: {
  id: string
  type: string
  timestamp: number
  correlationId?: string
  payload: unknown
}): string {
  return canonicalize({
    id: meta.id,
    type: meta.type,
    timestamp: meta.timestamp,
    ...(meta.correlationId !== undefined ? { correlationId: meta.correlationId } : {}),
    payload: meta.payload,
  })
}

export function signEnvelope(
  message: { id: string; type: MessageType; timestamp: number; correlationId?: string; payload: unknown },
  secret: string,
): WireMessage {
  return {
    id: message.id,
    type: message.type,
    timestamp: message.timestamp,
    ...(message.correlationId !== undefined ? { correlationId: message.correlationId } : {}),
    payload: message.payload,
    hmac: hmacSha256Hex(secret, signingInput(message)),
  }
}

export type VerifyFailure =
  | { reason: "BAD_JSON" }
  | { reason: "BAD_ENVELOPE"; detail: string }
  | { reason: "BAD_PAYLOAD"; detail: string }
  | { reason: "BAD_HMAC" }
  | { reason: "STALE_TIMESTAMP" }

export type VerifySuccess<T extends MessageType = MessageType> = {
  ok: true
  envelope: Envelope<T>
}

export type VerifyResult = VerifySuccess | { ok: false; failure: VerifyFailure }

/**
 * verifies timestamp freshness and hmac, then validates the payload against
 * the schema for the message type. anything failing here means the peer is
 * broken or hostile — caller closes the connection.
 */
export function verifyWireMessage(
  raw: string,
  secret: string,
  now: number,
  options: { maxSkewMs: number; maxPacketBytes: number },
): VerifyResult {
  if (Buffer.byteLength(raw, "utf8") > options.maxPacketBytes) {
    return { ok: false, failure: { reason: "BAD_ENVELOPE", detail: "packet exceeds size limit" } }
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(raw) as unknown
  } catch {
    return { ok: false, failure: { reason: "BAD_JSON" } }
  }

  if (typeof parsed !== "object" || parsed === null) {
    return { ok: false, failure: { reason: "BAD_ENVELOPE", detail: "message is not an object" } }
  }

  const record = parsed as Record<string, unknown>
  const hmac = record["hmac"]
  if (typeof hmac !== "string" || hmac.length !== HMAC_LENGTH) {
    return { ok: false, failure: { reason: "BAD_HMAC" } }
  }

  const metaResult = envelopeMetaSchema.safeParse(parsed)
  if (!metaResult.success) {
    return {
      ok: false,
      failure: { reason: "BAD_ENVELOPE", detail: metaResult.error.issues[0]?.message ?? "invalid envelope" },
    }
  }

  if (Math.abs(now - metaResult.data.timestamp) > options.maxSkewMs) {
    return { ok: false, failure: { reason: "STALE_TIMESTAMP" } }
  }

  const expected = hmacSha256Hex(
    secret,
    signingInput({
      id: metaResult.data.id,
      type: metaResult.data.type,
      timestamp: metaResult.data.timestamp,
      correlationId: metaResult.data.correlationId,
      payload: record["payload"],
    }),
  )
  if (!safeEqualHex(expected, hmac)) {
    return { ok: false, failure: { reason: "BAD_HMAC" } }
  }

  const payloadSchema = payloadSchemas[metaResult.data.type]
  const payloadResult = payloadSchema.safeParse(record["payload"])
  if (!payloadResult.success) {
    return {
      ok: false,
      failure: {
        reason: "BAD_PAYLOAD",
        detail: payloadResult.error.issues[0]?.message ?? "invalid payload",
      },
    }
  }

  return {
    ok: true,
    envelope: {
      ...metaResult.data,
      payload: payloadResult.data,
    } as Envelope,
  }
}
