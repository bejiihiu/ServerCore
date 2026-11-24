import { describe, expect, test } from "bun:test"
import { summarizeServer } from "../src/bot/status-summary.js"

describe("status screen server verdict", () => {
  test("no stats source at all: unknown, not down", () => {
    expect(summarizeServer(null)).toEqual({ online: null, players: 0 })
  })

  test("gateway down and nothing connected: still unknown", () => {
    // the backend cannot reach its own gateway, so it has no opinion on the server
    expect(summarizeServer({ wsRunning: false, servers: [] })).toEqual({ online: null, players: 0 })
  })

  test("gateway up, no server connected: the server is down", () => {
    expect(summarizeServer({ wsRunning: true, servers: [] })).toEqual({ online: false, players: 0 })
  })

  test("one running server: online, players summed across servers", () => {
    const summary = summarizeServer({
      wsRunning: true,
      servers: [
        { state: "RUNNING", onlinePlayers: 3 },
        { state: "OFFLINE", onlinePlayers: 4 },
      ],
    })
    expect(summary).toEqual({ online: true, players: 7 })
  })

  test("connected but not running: down", () => {
    expect(summarizeServer({ wsRunning: true, servers: [{ state: "STOPPING", onlinePlayers: 0 }] })).toEqual({
      online: false,
      players: 0,
    })
  })

  test("a server that never reported a state is not assumed running", () => {
    expect(summarizeServer({ wsRunning: true, servers: [{ onlinePlayers: 2 }] })).toEqual({ online: false, players: 2 })
  })
})
