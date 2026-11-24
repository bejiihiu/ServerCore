import { describe, expect, test } from "bun:test"
import { ReplayGuard } from "../src/websocket/security/replay-guard.js"

describe("replay guard", () => {
  test("accepts a fresh id once", () => {
    const guard = new ReplayGuard(30_000)
    expect(guard.checkAndRecord("msg-1", 1000)).toBe(true)
    expect(guard.has("msg-1")).toBe(true)
  })

  test("rejects the same id twice", () => {
    const guard = new ReplayGuard(30_000)
    expect(guard.checkAndRecord("msg-1", 1000)).toBe(true)
    expect(guard.checkAndRecord("msg-1", 1001)).toBe(false)
  })

  test("rejects duplicates across interleaved messages", () => {
    const guard = new ReplayGuard(30_000)
    expect(guard.checkAndRecord("a", 1)).toBe(true)
    expect(guard.checkAndRecord("b", 2)).toBe(true)
    expect(guard.checkAndRecord("a", 3)).toBe(false)
    expect(guard.checkAndRecord("b", 4)).toBe(false)
  })

  test("entries expire after the ttl", () => {
    const guard = new ReplayGuard(1000)
    expect(guard.checkAndRecord("msg-1", 1000)).toBe(true)
    // ttl passed, the entry is gone and the id is acceptable again
    expect(guard.checkAndRecord("msg-1", 3000)).toBe(true)
  })

  test("entries within the ttl stay blocked", () => {
    const guard = new ReplayGuard(1000)
    expect(guard.checkAndRecord("msg-1", 1000)).toBe(true)
    expect(guard.checkAndRecord("msg-1", 1500)).toBe(false)
  })

  test("purging keeps the map bounded under churn", () => {
    const guard = new ReplayGuard(1000)
    for (let i = 0; i < 500; i += 1) {
      guard.checkAndRecord(`msg-${i}`, i * 10)
    }
    // by t=10_000 everything is expired and purged on next call
    expect(guard.checkAndRecord("fresh", 10_000)).toBe(true)
    expect(guard.size).toBe(1)
  })
})
