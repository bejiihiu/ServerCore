import { describe, expect, test } from "bun:test"
import { MESSAGE_TYPES } from "../src/websocket/protocol/message-types.js"
import { HandlerRegistry, silentLogger } from "../src/websocket/handlers/registry.js"
import { ServerDirectory } from "../src/websocket/handlers/server-lifecycle.js"
import { registerAllHandlers } from "../src/websocket/handlers/wiring.js"
import type { ServerConnection } from "../src/websocket/connection.js"
import type { AccountService } from "../src/modules/account/account-service.js"
import type { GatewayOwnershipVerifier } from "../src/modules/account/session-probe.js"
import type { SpecAuditService } from "../src/modules/audit/spec-audit-service.js"

/** every message type the plugin can send that the backend must handle */
const PLUGIN_ORIGIN_DOMAIN_TYPES = [
  "PLAYER_JOINED",
  "PLAYER_LEFT",
  "GUEST_STARTED",
  "GUEST_ENDED",
  "SESSION_CHECK_RESULT",
  "SPEC_STARTED",
  "SPEC_ENDED",
  "SERVER_STATUS",
  "SERVER_HEARTBEAT",
] as const

function fakeConnection(serverId: string): ServerConnection {
  return { serverId } as unknown as ServerConnection
}

function stubAccounts(joined: { uuid: string }[] = []): AccountService {
  return {
    async getOrCreate(uuid: string) {
      joined.push({ uuid })
    },
    async createLinkToken() {
      return { token: "abcd1234abcd1234", expiresAt: Date.now() + 60_000 }
    },
  } as unknown as AccountService
}

/** wires the full production handler set over stubs */
function buildWiring(hooks: Parameters<typeof registerAllHandlers>[1]["hooks"] = {}) {
  const registry = new HandlerRegistry(silentLogger())
  const created: { uuid: string }[] = []
  const specRecords: string[] = []
  registerAllHandlers(registry, {
    directory: new ServerDirectory(),
    logger: silentLogger(),
    accounts: stubAccounts(created),
    verifier: { onProbeAnswer() {} } as unknown as GatewayOwnershipVerifier,
    specAudit: {
      async record(entry: { action: string }) {
        specRecords.push(entry.action)
      },
    } as unknown as SpecAuditService,
    hooks,
  })
  return { registry, created, specRecords }
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

  test("PLAYER_JOINED fans out to every module: auth hook + account link token", async () => {
    const authJoins: string[] = []
    const { registry, created } = buildWiring({
      onPlayerJoined: (event) => {
        authJoins.push(event.minecraftUuid)
      },
    })
    const sent: { type: string; payload: Record<string, unknown> }[] = []
    const conn = {
      serverId: "game-1",
      async send(type: string, payload: Record<string, unknown>) {
        sent.push({ type, payload })
      },
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

    // authentication module saw the join...
    expect(authJoins).toEqual(["3b7a1f0e-1111-4222-8333-444455556666"])
    // ...and the account module created the row and pushed the link token
    expect(created).toEqual([{ uuid: "3b7a1f0e-1111-4222-8333-444455556666" }])
    expect(sent).toEqual([
      {
        type: "LINK_TOKEN",
        payload: {
          minecraftUuid: "3b7a1f0e-1111-4222-8333-444455556666",
          username: "Steve",
          token: "abcd1234abcd1234",
          expiresAt: expect.any(Number),
        },
      },
    ])
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
})
