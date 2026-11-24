import { describe, expect, test } from "bun:test"
import { MESSAGE_TYPES } from "../src/websocket/protocol/message-types.js"
import { HandlerRegistry, silentLogger } from "../src/websocket/handlers/registry.js"
import { ServerDirectory } from "../src/websocket/handlers/server-lifecycle.js"
import { registerAllHandlers } from "../src/websocket/handlers/wiring.js"
import type { ServerConnection } from "../src/websocket/connection.js"
import type { GatewayOwnershipVerifier } from "../src/modules/account/session-probe.js"
import type { SpecAuditService } from "../src/modules/audit/spec-audit-service.js"
import { LocaleService } from "../src/services/locale-service.js"

/** every message type the plugin can send that the backend must handle */
const PLUGIN_ORIGIN_DOMAIN_TYPES = [
  "PLAYER_JOINED",
  "PLAYER_LEFT",
  "GUEST_STARTED",
  "GUEST_ENDED",
  "SESSION_CHECK_RESULT",
  "SPEC_STARTED",
  "SPEC_ENDED",
  "LOCALE_ACK",
  "SERVER_STATUS",
  "SERVER_HEARTBEAT",
] as const

function fakeConnection(serverId: string): ServerConnection {
  return { serverId } as unknown as ServerConnection
}

/** wires the full production handler set over stubs */
function buildWiring(hooks: Parameters<typeof registerAllHandlers>[1]["hooks"] = {}) {
  const registry = new HandlerRegistry(silentLogger())
  const specRecords: string[] = []
  registerAllHandlers(registry, {
    directory: new ServerDirectory(),
    logger: silentLogger(),
    verifier: { onProbeAnswer() {} } as unknown as GatewayOwnershipVerifier,
    specAudit: {
      async record(entry: { action: string }) {
        specRecords.push(entry.action)
      },
    } as unknown as SpecAuditService,
    locale: new LocaleService({ logger: silentLogger() }),
    hooks,
  })
  return { registry, specRecords }
}

describe("websocket handler wiring", () => {
  test("every plugin-origin domain type has exactly one handler", () => {
    const { registry } = buildWiring()
    const registered = registry.registeredTypes
    for (const type of PLUGIN_ORIGIN_DOMAIN_TYPES) {
      expect(registered.filter((t) => t === type)).toHaveLength(1)
    }
    // nothing beyond the domain set is registered (transport types never
    // route through the registry)
    expect(registered.sort()).toEqual([...PLUGIN_ORIGIN_DOMAIN_TYPES].sort())
  })

  test("all registered types are known wire protocol types", () => {
    const { registry } = buildWiring()
    const known: readonly string[] = MESSAGE_TYPES
    for (const type of registry.registeredTypes) {
      expect(known).toContain(type)
    }
  })

  test("double registration is impossible anywhere in the wiring", () => {
    const { registry } = buildWiring()
    for (const type of PLUGIN_ORIGIN_DOMAIN_TYPES) {
      expect(() => registry.register(type as never, () => undefined)).toThrow(/already registered/)
    }
  })

  test("PLAYER_JOINED fans out to the auth hook exactly once", async () => {
    const authJoins: string[] = []
    const { registry } = buildWiring({
      onPlayerJoined: (event) => {
        authJoins.push(event.minecraftUuid)
      },
    })
    const conn = {
      serverId: "game-1",
      async send() {},
    } as unknown as ServerConnection

    await registry.dispatch(
      {
        id: "msg_12345678",
        type: "PLAYER_JOINED",
        timestamp: Date.now(),
        payload: { minecraftUuid: "3b7a1f0e-1111-4222-8333-444455556666", username: "Steve" },
      },
      conn,
    )

    // authentication module saw the join once; the account side of the join
    // lives in the app-level joinPipeline (AccountJoinFlow + auth), not in
    // the registry — the registry delivers one event, modules hook into it
    expect(authJoins).toEqual(["3b7a1f0e-1111-4222-8333-444455556666"])
  })

  test("GUEST_STARTED forwards the captured snapshot to the hook", async () => {
    const starts: { sessionId: string; world: string; x: number }[] = []
    const { registry } = buildWiring({
      onGuestStarted: (event) => {
        starts.push({ sessionId: event.sessionId, world: event.snapshot.world, x: event.snapshot.x })
      },
    })

    await registry.dispatch(
      {
        id: "msg_12345680",
        type: "GUEST_STARTED",
        timestamp: Date.now(),
        payload: {
          minecraftUuid: "3b7a1f0e-1111-4222-8333-444455556666",
          username: "Steve",
          sessionId: "guest-abc123",
          snapshot: {
            world: "world",
            x: 1.5,
            y: 64,
            z: -3.25,
            yaw: 90,
            pitch: 0,
            gameMode: "SURVIVAL",
            flying: false,
            flySpeed: 0.1,
            walkSpeed: 0.2,
          },
        },
      },
      fakeConnection("game-1"),
    )

    // the handler used to drop the payload field; the hook is what persists it
    expect(starts).toEqual([{ sessionId: "guest-abc123", world: "world", x: 1.5 }])
  })

  test("SPEC events land in the audit trail through the wiring", async () => {
    const { registry, specRecords } = buildWiring()
    await registry.dispatch(
      {
        id: "msg_12345679",
        type: "SPEC_STARTED",
        timestamp: Date.now(),
        payload: { adminUuid: "3b7a1f0e-1111-4222-8333-444455556666", targetUuid: "3b7a1f0e-1111-4222-8333-444455557777" },
      },
      fakeConnection("game-1"),
    )
    expect(specRecords).toEqual(["spec.started"])
  })

  test("per-connection seeding fires once per fresh socket, not per status frame", async () => {
    const seeded: string[] = []
    const { registry } = buildWiring({
      onServerRegistered: (serverId) => {
        seeded.push(serverId)
      },
    })
    const statusFrame = (id: string) => ({
      id,
      type: "SERVER_STATUS" as const,
      timestamp: Date.now(),
      payload: { onlinePlayers: 0, maxPlayers: 20 },
    })

    const connection = fakeConnection("game-1")
    await registry.dispatch(statusFrame("msg_12345681"), connection)
    await registry.dispatch(statusFrame("msg_12345682"), connection)
    // the registration frame repeats on this socket; the hook must not
    await registry.dispatch(statusFrame("msg_12345683"), connection)

    // reconnect = a new connection object, which is a fresh registration
    await registry.dispatch(statusFrame("msg_12345684"), fakeConnection("game-1"))

    expect(seeded).toEqual(["game-1", "game-1"])
  })
})
