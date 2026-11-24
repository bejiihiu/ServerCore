import { describe, expect, test } from "bun:test"
import { envelopeMetaSchema, payloadSchemas, SERVER_STATES } from "../src/websocket/protocol/message-types.js"
import { registerLifecycleHandlers, ServerDirectory } from "../src/websocket/handlers/server-lifecycle.js"
import { HandlerRegistry, silentLogger } from "../src/websocket/handlers/registry.js"
import type { ServerConnection } from "../src/websocket/connection.js"

describe("ops states in the wire protocol", () => {
  test("SERVER_STATES taxonomy is complete", () => {
    const actual: string[] = [...SERVER_STATES].sort()
    expect(actual).toEqual(
      ["OFFLINE", "RESTARTING", "RUNNING", "STARTING", "STOPPING", "UPDATE_READY"].sort(),
    )
  })

  test("SERVER_STATUS accepts every ops state", () => {
    for (const state of SERVER_STATES) {
      const result = payloadSchemas.SERVER_STATUS.safeParse({
        onlinePlayers: 3,
        maxPlayers: 20,
        state,
      })
      expect(result.success).toBe(true)
    }
  })

  test("SERVER_STATUS stays valid without a state (old plugin builds)", () => {
    const result = payloadSchemas.SERVER_STATUS.safeParse({ onlinePlayers: 1, maxPlayers: 2 })
    expect(result.success).toBe(true)
  })

  test("SERVER_STATUS rejects an unknown state", () => {
    const result = payloadSchemas.SERVER_STATUS.safeParse({ onlinePlayers: 1, maxPlayers: 2, state: "WARP_DRIVE" })
    expect(result.success).toBe(false)
  })

  test("detail string is validated", () => {
    expect(payloadSchemas.SERVER_STATUS.safeParse({ onlinePlayers: 1, maxPlayers: 2, detail: "SHA256SUMS ok" }).success).toBe(true)
    expect(payloadSchemas.SERVER_STATUS.safeParse({ onlinePlayers: 1, maxPlayers: 2, detail: "x".repeat(513) }).success).toBe(false)
  })

  test("envelope meta still validates with the untouched shape", () => {
    const result = envelopeMetaSchema.safeParse({ id: "msg_12345678", type: "SERVER_STATUS", timestamp: 1 })
    expect(result.success).toBe(true)
  })
})

describe("lifecycle state-change hook", () => {
  function fakeConnection(serverId: string): ServerConnection {
    return { serverId } as unknown as ServerConnection
  }

  function envelope(state: string | undefined) {
    return {
      id: "msg_12345678",
      type: "SERVER_STATUS" as const,
      timestamp: Date.now(),
      payload: {
        onlinePlayers: 2,
        maxPlayers: 10,
        ...(state !== undefined ? { state } : {}),
      },
    }
  }

  test("hook fires on state flips only, not on every status frame", async () => {
    const registry = new HandlerRegistry(silentLogger())
    const directory = new ServerDirectory()
    const changes: { serverId: string; state: string; previous: string | undefined }[] = []
    registerLifecycleHandlers(registry, directory, silentLogger(), {
      onStateChange: (serverId, state, previous) => changes.push({ serverId, state, previous }),
    })

    const conn = fakeConnection("game-1")
    // first status without state -> no hook
    await registry.dispatch(envelope(undefined), conn)
    expect(changes).toHaveLength(0)

    // STARTING arrives -> hook with previous undefined
    await registry.dispatch(envelope("STARTING"), conn)
    expect(changes).toEqual([{ serverId: "game-1", state: "STARTING", previous: undefined }])

    // same state repeated -> no hook
    await registry.dispatch(envelope("STARTING"), conn)
    expect(changes).toHaveLength(1)

    // flip to RUNNING -> hook with the previous state
    await registry.dispatch(envelope("RUNNING"), conn)
    expect(changes).toEqual([{ serverId: "game-1", state: "STARTING", previous: undefined }, { serverId: "game-1", state: "RUNNING", previous: "STARTING" }])

    // status frames without a state keep the last known state
    await registry.dispatch(envelope(undefined), conn)
    expect(directory.get("game-1")?.state).toBe("RUNNING")
  })

  test("directory keeps the detail alongside the state", async () => {
    const registry = new HandlerRegistry(silentLogger())
    const directory = new ServerDirectory()
    registerLifecycleHandlers(registry, directory, silentLogger())

    await registry.dispatch(
      { id: "msg_12345678", type: "SERVER_STATUS", timestamp: Date.now(), payload: { onlinePlayers: 0, maxPlayers: 0, state: "UPDATE_READY", detail: "build-42-abc" } },
      fakeConnection("game-1"),
    )
    const status = directory.get("game-1")
    expect(status?.state).toBe("UPDATE_READY")
    expect(status?.detail).toBe("build-42-abc")
  })
})
