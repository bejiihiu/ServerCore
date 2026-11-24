import { describe, expect, test } from "bun:test"
import { silentLogger } from "../src/websocket/handlers/registry.js"
import { ServerDirectory } from "../src/websocket/handlers/server-lifecycle.js"
import { OfflineWatcher } from "../src/services/offline-watcher.js"

function setup(thresholdMs: number, now: number) {
  let clock = now
  const directory = new ServerDirectory()
  let connected: string[] = []
  const watcher = new OfflineWatcher({
    directory,
    gateway: { get connectedServers() { return connected } },
    logger: silentLogger(),
    thresholdMs,
    now: () => clock,
  })
  const alerts: string[] = []
  const reconnected: string[] = []
  watcher.onAlert = (alert) => alerts.push(alert.serverId)
  watcher.onReconnect = (serverId) => reconnected.push(serverId)
  return {
    directory,
    watcher,
    alerts,
    reconnected,
    setConnected: (ids: string[]) => { connected = ids },
    advance: (ms: number) => { clock += ms },
    get now() { return clock },
  }
}

describe("OfflineWatcher", () => {
  test("no alert while the server is connected", () => {
    const s = setup(5 * 60_000, 1_000_000)
    s.directory.update({ serverId: "game-1", onlinePlayers: 0, maxPlayers: 0, lastSeenAt: 1_000_000 })
    s.setConnected(["game-1"])

    s.advance(10 * 60_000)
    expect(s.watcher.check()).toHaveLength(0)
    expect(s.alerts).toHaveLength(0)
  })

  test("silent before the threshold, alerted after it", () => {
    const s = setup(5 * 60_000, 1_000_000)
    s.directory.update({ serverId: "game-1", onlinePlayers: 0, maxPlayers: 0, lastSeenAt: 1_000_000 })
    s.setConnected([])

    s.advance(4 * 60_000 + 59_000)
    expect(s.watcher.check()).toHaveLength(0)

    s.advance(2000) // past 5 minutes of silence
    expect(s.watcher.check().map((a) => a.serverId)).toEqual(["game-1"])
    expect(s.alerts).toEqual(["game-1"])
  })

  test("alerts exactly once per outage", () => {
    const s = setup(60_000, 0)
    s.directory.update({ serverId: "game-1", onlinePlayers: 0, maxPlayers: 0, lastSeenAt: 0 })
    s.setConnected([])

    s.advance(120_000)
    expect(s.watcher.check()).toHaveLength(1)
    expect(s.watcher.check()).toHaveLength(0) // still alerted, no spam
    expect(s.watcher.alertedServers).toEqual(["game-1"])
  })

  test("reconnect re-arms the alert", () => {
    const s = setup(60_000, 0)
    s.directory.update({ serverId: "game-1", onlinePlayers: 0, maxPlayers: 0, lastSeenAt: 0 })
    s.setConnected([])

    s.advance(120_000)
    s.watcher.check()
    expect(s.alerts).toEqual(["game-1"])

    s.setConnected(["game-1"])
    s.watcher.check()
    expect(s.reconnected).toEqual(["game-1"])
    expect(s.watcher.alertedServers).toHaveLength(0)

    // drops again -> new outage gets its own alert
    s.setConnected([])
    s.watcher.check()
    expect(s.watcher.check()).toHaveLength(0) // silence timer restarted from lastSeenAt
  })

  test("a fresh reconnect resets silence even without a status update", () => {
    const s = setup(60_000, 0)
    s.directory.update({ serverId: "game-1", onlinePlayers: 0, maxPlayers: 0, lastSeenAt: 0 })
    s.setConnected([])

    s.advance(120_000)
    s.watcher.check()
    s.setConnected(["game-1"])
    s.watcher.check()

    // server updates its heartbeat while connected
    s.advance(30_000)
    s.directory.update({ serverId: "game-1", onlinePlayers: 0, maxPlayers: 0, lastSeenAt: s.now })
    s.setConnected([])

    s.advance(59_000)
    expect(s.watcher.check()).toHaveLength(0)
    s.advance(2000)
    expect(s.watcher.check()).toHaveLength(1)
  })

  test("multiple servers alert independently", () => {
    const s = setup(60_000, 0)
    s.directory.update({ serverId: "game-1", onlinePlayers: 0, maxPlayers: 0, lastSeenAt: 0 })
    s.directory.update({ serverId: "game-2", onlinePlayers: 0, maxPlayers: 0, lastSeenAt: 0 })
    s.setConnected(["game-2"])

    s.advance(120_000)
    expect(s.watcher.check().map((a) => a.serverId)).toEqual(["game-1"])
  })
})
