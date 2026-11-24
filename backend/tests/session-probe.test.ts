import { describe, expect, test } from "bun:test"
import { GatewayOwnershipVerifier } from "../src/modules/account/session-probe.js"
import { silentLogger } from "../src/websocket/handlers/registry.js"
import type { Gateway } from "../src/websocket/gateway.js"

const UUID = "11111111-1111-4111-8111-111111111111"

/** gateway double recording sends and letting tests answer probes manually */
class FakeGateway implements Pick<Gateway, "connectedServers" | "sendTo"> {
  servers: string[] = ["game-1"]
  readonly sent: { serverId: string; type: string; payload: unknown; correlationId?: string }[] = []
  private readonly resolvers = new Map<string, () => void>()

  get connectedServers(): string[] {
    return this.servers
  }

  async sendTo(serverId: string, type: never, payload: never, options: { correlationId?: string } = {}): Promise<void> {
    this.sent.push({ serverId, type, payload, correlationId: options.correlationId })
    const correlationId = options.correlationId
    if (correlationId !== undefined) {
      await new Promise<void>((resolve) => this.resolvers.set(correlationId, resolve))
    }
  }

  /** simulates the plugin answering a probe */
  answer(correlationId: string, online: boolean, verifier: GatewayOwnershipVerifier, sessionId: string | null = null): void {
    this.resolvers.get(correlationId)?.()
    verifier.onProbeAnswer(correlationId, online, sessionId)
  }

  /** unblocks sends without answering (simulates ack-only traffic) */
  releaseAll(): void {
    for (const [, resolve] of this.resolvers) resolve()
    this.resolvers.clear()
  }
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 10))
}

describe("gateway ownership probe", () => {
  test("no connected servers -> SERVER_OFFLINE without any round trip", async () => {
    const gateway = new FakeGateway()
    gateway.servers = []
    const verifier = new GatewayOwnershipVerifier(gateway as unknown as Gateway, silentLogger())
    const result = await verifier.verify(UUID)
    expect(result).toEqual({ ok: false, reason: "SERVER_OFFLINE" })
    expect(gateway.sent.length).toBe(0)
  })

  test("a yes from the server resolves with the session id", async () => {
    const gateway = new FakeGateway()
    const verifier = new GatewayOwnershipVerifier(gateway as unknown as Gateway, silentLogger())
    const pending = verifier.verify(UUID)
    await flush()
    expect(gateway.sent.length).toBe(1)
    const correlationId = gateway.sent[0]?.correlationId
    expect(correlationId).toBeDefined()
    gateway.answer(correlationId as string, true, verifier, "session-77")
    const result = await pending
    expect(result).toEqual({ ok: true, sessionId: "session-77" })
    expect(verifier.pendingCount).toBe(0)
  })

  test("a no from the only server -> NOT_ONLINE", async () => {
    const gateway = new FakeGateway()
    const verifier = new GatewayOwnershipVerifier(gateway as unknown as Gateway, silentLogger())
    const pending = verifier.verify(UUID)
    await flush()
    const correlationId = gateway.sent[0]?.correlationId as string
    gateway.answer(correlationId, false, verifier)
    const result = await pending
    expect(result).toEqual({ ok: false, reason: "NOT_ONLINE" })
  })

  test("multi-server: one yes wins even after a no", async () => {
    const gateway = new FakeGateway()
    gateway.servers = ["game-1", "game-2"]
    const verifier = new GatewayOwnershipVerifier(gateway as unknown as Gateway, silentLogger())
    const pending = verifier.verify(UUID)
    await flush()
    expect(gateway.sent.length).toBe(2)
    gateway.answer(gateway.sent[0]?.correlationId as string, false, verifier)
    // second server still owes an answer; the probe must stay open
    await flush()
    gateway.answer(gateway.sent[1]?.correlationId as string, true, verifier, "s-9")
    const result = await pending
    expect(result).toEqual({ ok: true, sessionId: "s-9" })
  })

  test("multi-server: all no -> NOT_ONLINE", async () => {
    const gateway = new FakeGateway()
    gateway.servers = ["game-1", "game-2"]
    const verifier = new GatewayOwnershipVerifier(gateway as unknown as Gateway, silentLogger())
    const pending = verifier.verify(UUID)
    await flush()
    gateway.answer(gateway.sent[0]?.correlationId as string, false, verifier)
    gateway.answer(gateway.sent[1]?.correlationId as string, false, verifier)
    const result = await pending
    expect(result).toEqual({ ok: false, reason: "NOT_ONLINE" })
  })

  test("timeout when nobody answers", async () => {
    const gateway = new FakeGateway()
    const verifier = new GatewayOwnershipVerifier(gateway as unknown as Gateway, silentLogger(), 30)
    const result = await verifier.verify(UUID)
    expect(result).toEqual({ ok: false, reason: "TIMEOUT" })
    gateway.releaseAll()
  })

  test("send failure counts as answered-no instead of hanging", async () => {
    class BrokenGateway extends FakeGateway {
      override async sendTo(): Promise<void> {
        throw new Error("socket gone")
      }
    }
    const gateway = new BrokenGateway()
    const verifier = new GatewayOwnershipVerifier(gateway as unknown as Gateway, silentLogger())
    const result = await verifier.verify(UUID)
    expect(result).toEqual({ ok: false, reason: "NOT_ONLINE" })
  })

  test("late answers for unknown probes are ignored", () => {
    const gateway = new FakeGateway()
    const verifier = new GatewayOwnershipVerifier(gateway as unknown as Gateway, silentLogger())
    expect(() => verifier.onProbeAnswer("no-such-probe", true, "s-1")).not.toThrow()
  })
})
