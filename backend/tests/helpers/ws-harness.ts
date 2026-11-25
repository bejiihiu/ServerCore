import pino from "pino"
import type { ConnectionOptions, GatewaySocketData } from "../../src/websocket/connection.js"
import type { MessageType } from "../../src/websocket/protocol/message-types.js"
import { signEnvelope } from "../../src/websocket/protocol/signing.js"

export const WS_SECRET = "a".repeat(32)
export const WS_NOW = 1_700_000_000_000

/** minimal stand-in for Bun's server socket: records writes, never throws */
export function fakeSocket() {
  const sent: string[] = []
  let closed = false
  const socket = {
    sendText: (text: string) => {
      sent.push(text)
    },
    getBufferedAmount: () => 0,
    ping: () => {},
    close: () => {
      closed = true
    },
    terminate: () => {
      closed = true
    },
  }
  return {
    socket: socket as unknown as Bun.ServerWebSocket<GatewaySocketData>,
    sent,
    isClosed: () => closed,
  }
}

export function connectionOptions(overrides: Partial<ConnectionOptions> = {}): ConnectionOptions {
  return {
    secret: WS_SECRET,
    heartbeatIntervalMs: 15_000,
    heartbeatTimeoutMs: 10_000,
    maxPacketBytes: 262_144,
    replayWindowMs: 30_000,
    replayGuardTtlMs: 150_000,
    ...overrides,
  }
}

/** a signed wire frame; the default timestamp sits inside the replay window */
export function signedFrame(
  type: MessageType,
  payload: Record<string, unknown>,
  id: string,
  correlationId?: string,
  timestamp = WS_NOW,
): string {
  return JSON.stringify(
    signEnvelope(
      { id, type, timestamp, ...(correlationId !== undefined ? { correlationId } : {}), payload },
      WS_SECRET,
    ),
  )
}

export function silentPino() {
  return pino({ level: "silent" })
}

/** payloads of the frames a fake socket received, parsed back from the wire */
export function sentFrames(sent: string[]): { type: string; payload: Record<string, unknown>; correlationId?: string }[] {
  return sent.map((text) => {
    const wire = JSON.parse(text) as { type: string; payload: Record<string, unknown>; correlationId?: string }
    return wire
  })
}
