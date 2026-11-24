import { describe, expect, test } from "bun:test"
import { silentLogger } from "../src/websocket/handlers/registry.js"
import {
  NotificationService,
  type MessageSender,
  type RateLimitClock,
} from "../src/services/notification-service.js"
import { StaticRecipients } from "../src/services/recipients.js"

/** fake clock: sleep advances virtual time instantly, so rate windows are testable */
function fakeClock() {
  let now = 1_000_000
  const sleeps: number[] = []
  const clock: RateLimitClock = {
    now: () => now,
    sleep: (ms) => {
      sleeps.push(ms)
      now += ms
      return Promise.resolve()
    },
  }
  return { clock, sleeps, get now() { return now } }
}

/** records sends, optionally failing configured chat ids */
function fakeSender(failIds: Set<string> = new Set()) {
  const sent: { chatId: string; text: string }[] = []
  const sender: MessageSender = {
    send: async (chatId, text) => {
      if (failIds.has(chatId)) throw new Error(`boom ${chatId}`)
      sent.push({ chatId, text })
    },
  }
  return { sent, sender }
}

describe("NotificationService", () => {
  test("sends to every unique recipient exactly once", async () => {
    const { clock } = fakeClock()
    const { sent, sender } = fakeSender()
    const service = new NotificationService({
      sender,
      recipients: new StaticRecipients(["1", "2", "3", "2"]),
      logger: silentLogger(),
      options: { clock },
    })

    const result = await service.broadcast("hello", ["3", "9"])

    expect(result.recipients).toBe(4) // 1,2,3 deduped + admin 9
    expect(result.sent).toBe(4)
    expect(result.failed).toBe(0)
    expect(sent.map((s) => s.chatId).sort()).toEqual(["1", "2", "3", "9"])
    expect(sent.every((s) => s.text === "hello")).toBe(true)
  })

  test("throttles at the configured rate per second", async () => {
    const { clock, sleeps } = fakeClock()
    const { sender } = fakeSender()
    const service = new NotificationService({
      sender,
      recipients: new StaticRecipients(["1", "2", "3", "4", "5"]),
      logger: silentLogger(),
      options: { clock, ratePerSecond: 2 },
    })

    const result = await service.broadcast("ping")

    expect(result.sent).toBe(5)
    // 5 recipients at 2/s: two pauses (after 2nd and 4th message)
    expect(sleeps).toEqual([1000, 1000])
  })

  test("sleeps only the remainder of the current rate window", async () => {
    // each send burns 200ms of virtual time; with rate 2 a full window takes
    // 2 sends = 400ms, so the throttle must sleep 600, not 1000
    let now = 1_000_000
    const sleeps: number[] = []
    const clock: RateLimitClock = {
      now: () => now,
      sleep: (ms) => {
        sleeps.push(ms)
        now += ms
        return Promise.resolve()
      },
    }
    const sent: string[] = []
    const sender: MessageSender = {
      send: async (chatId) => {
        sent.push(chatId)
        now += 200
      },
    }
    const service = new NotificationService({
      sender,
      recipients: new StaticRecipients(["1", "2", "3", "4", "5"]),
      logger: silentLogger(),
      options: { clock, ratePerSecond: 2 },
    })

    const result = await service.broadcast("x")
    expect(result.sent).toBe(5)
    expect(sleeps).toEqual([600, 600])
  })

  test("continues past failing recipients and retries them once", async () => {
    const { clock } = fakeClock()
    let attemptsForTwo = 0
    const sent: string[] = []
    const sender: MessageSender = {
      send: async (chatId) => {
        if (chatId === "2") {
          attemptsForTwo += 1
          // only the very first attempt for this chat fails, the retry succeeds
          if (attemptsForTwo === 1) throw new Error("transient")
        }
        sent.push(chatId)
      },
    }
    const service = new NotificationService({
      sender,
      recipients: new StaticRecipients(["1", "2", "3"]),
      logger: silentLogger(),
      options: { clock },
    })

    const result = await service.broadcast("hi")
    expect(result.sent).toBe(3)
    expect(result.failed).toBe(0)
    // "2" appears exactly once: the main-pass attempt failed and the retry pass landed
    expect(sent.filter((id) => id === "2").length).toBe(1)
  })

  test("reports permanently failing recipients as failed", async () => {
    const { clock } = fakeClock()
    const { sender } = fakeSender(new Set(["7"]))
    const service = new NotificationService({
      sender,
      recipients: new StaticRecipients(["7", "8"]),
      logger: silentLogger(),
      options: { clock },
    })

    const result = await service.broadcast("hi")
    expect(result.sent).toBe(1)
    expect(result.failed).toBe(1)
  })

  test("without a sender the broadcast is a no-op but reports recipients", async () => {
    const service = new NotificationService({
      sender: null,
      recipients: new StaticRecipients(["1", "2"]),
      logger: silentLogger(),
    })

    const result = await service.broadcast("hi")
    expect(result).toEqual({ recipients: 2, sent: 0, failed: 0 })
  })

  test("empty recipient list short-circuits", async () => {
    const { clock } = fakeClock()
    const { sent, sender } = fakeSender()
    const service = new NotificationService({
      sender,
      recipients: new StaticRecipients([]),
      logger: silentLogger(),
      options: { clock },
    })

    const result = await service.broadcast("hi")
    expect(result).toEqual({ recipients: 0, sent: 0, failed: 0 })
    expect(sent).toHaveLength(0)
  })
})
