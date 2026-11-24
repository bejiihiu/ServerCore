import { describe, expect, test } from "bun:test"
import { RateLimiter } from "../src/utils/rate-limiter.js"
import { zonedIso, pinoTimestampFor } from "../src/utils/time.js"
import { t, statusLabel } from "../src/services/i18n.js"

describe("rate limiter", () => {
  test("allows up to the limit inside the window", () => {
    const limiter = new RateLimiter({ limit: 3, windowMs: 1000 })
    expect(limiter.hit("k", 0).allowed).toBe(true)
    expect(limiter.hit("k", 1).allowed).toBe(true)
    expect(limiter.hit("k", 2).allowed).toBe(true)
    expect(limiter.hit("k", 3)).toMatchObject({ allowed: false })
  })

  test("window reset restores capacity", () => {
    const limiter = new RateLimiter({ limit: 1, windowMs: 1000 })
    expect(limiter.hit("k", 0).allowed).toBe(true)
    expect(limiter.hit("k", 500).allowed).toBe(false)
    expect(limiter.hit("k", 1000).allowed).toBe(true)
  })

  test("keys are isolated", () => {
    const limiter = new RateLimiter({ limit: 1, windowMs: 1000 })
    expect(limiter.hit("a", 0).allowed).toBe(true)
    expect(limiter.hit("b", 0).allowed).toBe(true)
  })

  test("retryAfterMs points at the window reset", () => {
    const limiter = new RateLimiter({ limit: 1, windowMs: 1000 })
    limiter.hit("k", 0)
    const blocked = limiter.hit("k", 400)
    expect(blocked.allowed).toBe(false)
    expect(blocked.retryAfterMs).toBe(600)
  })

  test("old windows are purged", () => {
    const limiter = new RateLimiter({ limit: 1, windowMs: 1000 })
    limiter.hit("a", 0)
    limiter.hit("b", 0)
    limiter.hit("c", 5000)
    expect(limiter.size).toBe(1)
  })
})

describe("zoned iso timestamps", () => {
  test("renders europe/moscow wall clock with the right offset", () => {
    // 2026-11-24T11:30:00Z == 14:30 Moscow (UTC+3, no dst)
    const date = new Date("2026-11-24T11:30:00Z")
    expect(zonedIso(date, "Europe/Moscow")).toBe("2026-11-24T14:30:00+03:00")
  })

  test("handles dst zones correctly", () => {
    // 2026-07-01T12:00:00Z == 14:00 in Europe/Berlin (CEST, UTC+2)
    const summer = new Date("2026-07-01T12:00:00Z")
    expect(zonedIso(summer, "Europe/Berlin")).toBe("2026-07-01T14:00:00+02:00")
    // 2026-01-01T12:00:00Z == 13:00 in Europe/Berlin (CET, UTC+1)
    const winter = new Date("2026-01-01T12:00:00Z")
    expect(zonedIso(winter, "Europe/Berlin")).toBe("2026-01-01T13:00:00+01:00")
  })

  test("pino timestamp produces the pino-shaped json fragment", () => {
    // pino's timestamp contract: the function returns the full
    // `,"time":"..."` fragment including the leading comma
    const fragment = pinoTimestampFor("Europe/Moscow")()
    expect(fragment).toMatch(/^,"time":"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+03:00"$/)
  })
})

describe("i18n", () => {
  test("parameter substitution works in both locales", () => {
    const ru = t("ru", "account.linked", { username: "steve" })
    const en = t("en", "account.linked", { username: "steve" })
    expect(ru).toContain("steve")
    expect(en).toContain("steve")
    expect(ru).not.toBe(en)
  })

  test("status labels resolve for the full ladder in both locales", () => {
    for (const status of [
      "UNREGISTERED",
      "APPLICATION_PENDING",
      "VOTING",
      "APPROVED",
      "ACTIVE",
      "SUSPENDED",
      "BANNED",
      "DELETION_PENDING",
    ]) {
      for (const locale of ["ru", "en"] as const) {
        // a missing key returns the key itself, so a real label never contains a dot
        expect(statusLabel(locale, status)).not.toContain(".")
      }
    }
  })

  test("status change notice leaves no placeholders behind", () => {
    for (const locale of ["ru", "en"] as const) {
      const notice = t(locale, "account.status_changed", {
        from: statusLabel(locale, "VOTING"),
        to: statusLabel(locale, "ACTIVE"),
      })
      expect(notice).not.toMatch(/\{\{?\w+\}?\}/)
    }
  })
})
