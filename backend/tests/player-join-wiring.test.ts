import { describe, expect, test } from "bun:test"
import { HandlerRegistry, silentLogger } from "../src/websocket/handlers/registry.js"
import { registerLifecycleHandlers, ServerDirectory } from "../src/websocket/handlers/server-lifecycle.js"
import { AccountJoinFlow, registerAccountHandlers } from "../src/websocket/handlers/account-handlers.js"
import type { Gateway } from "../src/websocket/gateway.js"
import type { Envelope, MessageType } from "../src/websocket/protocol/message-types.js"
import type { ServerConnection } from "../src/websocket/connection.js"
import type { GatewayOwnershipVerifier } from "../src/modules/account/session-probe.js"
import { buildFixture, UUID_1 } from "./helpers/account-fixture.js"

/** gateway double: records everything the account flow pushes */
class FakeGateway {
  sent: { serverId: string; type: string; payload: unknown }[] = []
  connectedServers = ["game-1"]

  async sendTo(serverId: string, type: string, payload: unknown): Promise<void> {
    this.sent.push({ serverId, type, payload })
  }
}

const JOIN_ENVELOPE = {
  id: "aaaaaaaaaaaaaaaa",
  type: "PLAYER_JOINED" as MessageType,
  timestamp: Date.now(),
  payload: { minecraftUuid: UUID_1, username: "steve" },
} as Envelope<"PLAYER_JOINED">

const CONNECTION = { serverId: "game-1" } as unknown as ServerConnection

describe("player join wiring", () => {
  test("lifecycle and account handlers share one registry without colliding", async () => {
    const registry = new HandlerRegistry(silentLogger())
    const joins: { minecraftUuid: string; serverId: string }[] = []
    registerLifecycleHandlers(registry, new ServerDirectory(), silentLogger(), {
      onPlayerJoined: (event) => {
        joins.push({ minecraftUuid: event.minecraftUuid, serverId: event.serverId })
      },
    })
    registerAccountHandlers(registry, {
      verifier: { onProbeAnswer: () => {} } as unknown as GatewayOwnershipVerifier,
      logger: silentLogger(),
    })

    // the historical bug: a second PLAYER_JOINED registration threw at boot,
    // killing the whole backend before it ever listened
    await registry.dispatch(JOIN_ENVELOPE, CONNECTION)
    expect(joins).toEqual([{ minecraftUuid: UUID_1, serverId: "game-1" }])
  })

  test("the account join flow creates the row and pushes the personal link token", async () => {
    const fixture = buildFixture()
    const gateway = new FakeGateway()
    const flow = new AccountJoinFlow({
      accounts: fixture.accounts,
      gateway: gateway as unknown as Gateway,
      logger: silentLogger(),
    })

    await flow.onPlayerJoined({ minecraftUuid: UUID_1, username: "steve", serverId: "game-1" })

    expect((await fixture.users.findByUuid(UUID_1))?.status).toBe("UNREGISTERED")
    expect(gateway.sent).toHaveLength(1)
    const push = gateway.sent[0]!
    expect(push.serverId).toBe("game-1")
    expect(push.type).toBe("LINK_TOKEN")
    const payload = push.payload as { minecraftUuid: string; username: string; token: string; expiresAt: number }
    expect(payload.minecraftUuid).toBe(UUID_1)
    expect(payload.username).toBe("steve")
    expect(payload.token).toHaveLength(16)
  })

  test("a linked player gets no link token push", async () => {
    const fixture = buildFixture()
    const gateway = new FakeGateway()
    const flow = new AccountJoinFlow({
      accounts: fixture.accounts,
      gateway: gateway as unknown as Gateway,
      logger: silentLogger(),
    })
    await fixture.accounts.getOrCreate(UUID_1, "steve")

    // direct seeding: mark the account linked without the full probe dance
    const record = await fixture.users.findByUuid(UUID_1)
    if (record) {
      record.telegramId = "10001"
      await fixture.users.update(record)
    }

    await flow.onPlayerJoined({ minecraftUuid: UUID_1, username: "steve", serverId: "game-1" })
    expect(gateway.sent).toHaveLength(0)
  })

  test("a failed token push does not break the join flow", async () => {
    const fixture = buildFixture()
    const gateway = new FakeGateway()
    gateway.sendTo = async () => {
      throw new Error("link down")
    }
    const flow = new AccountJoinFlow({
      accounts: fixture.accounts,
      gateway: gateway as unknown as Gateway,
      logger: silentLogger(),
    })

    await flow.onPlayerJoined({ minecraftUuid: UUID_1, username: "steve", serverId: "game-1" })
    expect((await fixture.users.findByUuid(UUID_1))?.status).toBe("UNREGISTERED")
  })
})
