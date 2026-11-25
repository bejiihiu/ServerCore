import { describe, expect, test } from "bun:test"
import { buildNetworkSnapshot, NetworkSnapshotBroadcaster } from "../src/services/network-snapshot.js"
import { ServerDirectory } from "../src/websocket/handlers/server-lifecycle.js"
import type { Gateway } from "../src/websocket/gateway.js"
import { silentLogger } from "../src/websocket/handlers/registry.js"

/**
 * the network view the plugin's /netbar renders. it is built from the
 * directory the heartbeat handler already maintains, so these tests pin the
 * two properties that matter: per-server age is honest, and the totals are the
 * sum the bar prints.
 */
function directoryWith(entries: { serverId: string; onlinePlayers: number; maxPlayers?: number; lastSeenAt: number }[]): ServerDirectory {
  const directory = new ServerDirectory()
  for (const entry of entries) {
    directory.update({
      serverId: entry.serverId,
      onlinePlayers: entry.onlinePlayers,
      maxPlayers: entry.maxPlayers ?? 60,
      lastSeenAt: entry.lastSeenAt,
    })
  }
  return directory
}

describe("network snapshot", () => {
  test("carries one row per known server with its heartbeat age", () => {
    const now = 1_000_000
    const directory = directoryWith([
      { serverId: "game-1", onlinePlayers: 5, lastSeenAt: now - 1_200 },
      { serverId: "game-2", onlinePlayers: 2, lastSeenAt: now - 61_000 },
    ])

    const snapshot = buildNetworkSnapshot(directory, now)

    expect(snapshot.generatedAt).toBe(now)
    expect(snapshot.playersOnline).toBe(7)
    expect(snapshot.servers).toEqual([
      { serverId: "game-1", onlinePlayers: 5, maxPlayers: 60, lastSeenAgeMs: 1_200 },
      { serverId: "game-2", onlinePlayers: 2, maxPlayers: 60, lastSeenAgeMs: 61_000 },
    ])
  })

  test("orders rows by id so an identical set never looks like a change", () => {
    const directory = directoryWith([
      { serverId: "srv-c", onlinePlayers: 1, lastSeenAt: 10 },
      { serverId: "srv-a", onlinePlayers: 1, lastSeenAt: 10 },
      { serverId: "srv-b", onlinePlayers: 1, lastSeenAt: 10 },
    ])

    expect(buildNetworkSnapshot(directory, 10).servers.map((server) => server.serverId)).toEqual([
      "srv-a",
      "srv-b",
      "srv-c",
    ])
  })

  test("an empty directory is an empty snapshot, not a crash", () => {
    const snapshot = buildNetworkSnapshot(new ServerDirectory(), 42)

    expect(snapshot.servers).toEqual([])
    expect(snapshot.playersOnline).toBe(0)
  })

  test("a clock behind the frame never yields a negative age", () => {
    const directory = directoryWith([{ serverId: "game-1", onlinePlayers: 0, lastSeenAt: 5_000 }])

    expect(buildNetworkSnapshot(directory, 1_000).servers[0]?.lastSeenAgeMs).toBe(0)
  })

  test("every connected server receives the push", async () => {
    const now = 500_000
    const sent: { serverId: string; type: string; payload: unknown }[] = []
    const gateway = {
      connectedServers: ["game-1", "game-2"],
      sendTo: async (serverId: string, type: string, payload: unknown) => {
        sent.push({ serverId, type, payload })
      },
    } as unknown as Gateway
    const directory = directoryWith([{ serverId: "game-1", onlinePlayers: 3, lastSeenAt: now }])
    const broadcaster = new NetworkSnapshotBroadcaster(gateway, directory, silentLogger(), 1_000, () => now)

    broadcaster.onHeartbeat("game-1")
    await Promise.resolve()

    expect(sent).toHaveLength(2)
    expect(sent.map((frame) => frame.serverId)).toEqual(["game-1", "game-2"])
    expect(sent[0]?.type).toBe("NETWORK_SNAPSHOT")
    expect((sent[0]?.payload as { playersOnline: number }).playersOnline).toBe(3)
  })

  test("simultaneous heartbeats collapse into one push per window", async () => {
    let now = 100_000
    const sent: string[] = []
    const gateway = {
      connectedServers: ["game-1"],
      sendTo: async () => {
        sent.push("push")
      },
    } as unknown as Gateway
    const broadcaster = new NetworkSnapshotBroadcaster(
      gateway,
      directoryWith([{ serverId: "game-1", onlinePlayers: 1, lastSeenAt: now }]),
      silentLogger(),
      1_000,
      () => now,
    )

    broadcaster.onHeartbeat("game-1")
    broadcaster.onHeartbeat("game-2") // same instant, dropped
    now += 500
    broadcaster.onHeartbeat("game-1") // still inside the window
    now += 600
    broadcaster.onHeartbeat("game-1") // the next window opens
    await Promise.resolve()

    expect(sent).toHaveLength(2)
  })
})
