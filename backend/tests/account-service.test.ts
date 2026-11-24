import { describe, expect, test } from "bun:test"
import {
  AccountError,
  buildFixture,
  seedLinkedTarget,
  statusOf,
  viewOf,
  UUID_1,
  UUID_2,
  TG_1,
  TG_2,
} from "./helpers/account-fixture.js"

const NOW = new Date(1_700_000_000_000)

describe("account get-or-create", () => {
  test("creates an unregistered account on first sight", async () => {
    const fixture = buildFixture()
    const view = await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    expect(view.status).toBe("UNREGISTERED")
    expect(view.telegramLinked).toBe(false)
    expect(view.language).toBe("ru")
    expect(statusOf(fixture, UUID_1)).toBe("UNREGISTERED")
  })

  test("second create with the same uuid returns the existing account", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    const again = await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    expect(again.createdAt.getTime()).toBe(NOW.getTime())
  })

  test("create race resolves by reading back the winner's record", async () => {
    const fixture = buildFixture()
    await fixture.users.create({
      minecraftUuid: UUID_1,
      username: "steve",
      telegramId: "",
      telegramUsername: null,
      telegramLinkedAt: null,
      status: "UNREGISTERED",
      language: "ru",
      lastApplicationAt: null,
      createdAt: NOW,
      updatedAt: NOW,
    })
    const raced = await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    expect(raced.minecraftUuid).toBe(UUID_1)
  })
})

describe("link token issuance", () => {
  test("issues a token for an unlinked, live account", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    const issued = await fixture.accounts.createLinkToken(UUID_1, NOW)
    expect(issued).not.toBeNull()
    expect(issued?.token.length).toBe(16)
  })

  test("refuses tokens for already-linked accounts", async () => {
    const fixture = buildFixture()
    const token = await seedLinkedTarget(fixture)
    const outcome = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW)
    expect(outcome.linked).toBe(true)
    const second = await fixture.accounts.createLinkToken(UUID_1, NOW)
    expect(second).toBeNull()
  })

  test("rate limits token re-issuance to one per minute per uuid", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    const first = await fixture.accounts.createLinkToken(UUID_1, NOW)
    expect(first).not.toBeNull()
    const burst = await fixture.accounts.createLinkToken(UUID_1, new Date(NOW.getTime() + 1000))
    expect(burst).toBeNull()
    const later = await fixture.accounts.createLinkToken(UUID_1, new Date(NOW.getTime() + 61_000))
    expect(later).not.toBeNull()
  })
})

describe("telegram linking", () => {
  test("happy path: token + confirmation + live session -> linked", async () => {
    const fixture = buildFixture()
    const token = await seedLinkedTarget(fixture)
    const outcome = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: "tg_user", token }, NOW)
    expect(outcome.linked).toBe(true)
    const view = viewOf(fixture, UUID_1)
    expect(view.telegramLinked).toBe(true)
    expect(view.telegramId).toBe(TG_1)
    // the ownership probe ran exactly once
    expect(fixture.ownership.calls).toEqual([UUID_1])
  })

  test("reuse of a consumed token is rejected", async () => {
    const fixture = buildFixture()
    const token = await seedLinkedTarget(fixture)
    const first = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW)
    expect(first.linked).toBe(true)
    const second = await fixture.accounts.linkTelegram({ telegramId: TG_2, telegramUsername: null, token }, NOW)
    expect(second).toEqual({ linked: false, reason: "TOKEN_INVALID" })
  })

  test("fail closed: no live server session -> no link", async () => {
    const fixture = buildFixture()
    const token = await seedLinkedTarget(fixture)
    fixture.ownership.result = { ok: false, reason: "NOT_ONLINE" }
    const outcome = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW)
    expect(outcome).toEqual({ linked: false, reason: "OWNERSHIP_NOT_PROVEN" })
    // the token must survive a failed probe: verification happens before redeem
    fixture.ownership.result = { ok: true, sessionId: "s1" }
    const retry = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW)
    expect(retry.linked).toBe(true)
  })

  test("server offline blocks linking", async () => {
    const fixture = buildFixture()
    const token = await seedLinkedTarget(fixture)
    fixture.ownership.result = { ok: false, reason: "SERVER_OFFLINE" }
    const outcome = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW)
    expect(outcome).toEqual({ linked: false, reason: "SERVER_OFFLINE" })
  })

  test("one telegram cannot bind to a second uuid", async () => {
    const fixture = buildFixture()
    const token1 = await seedLinkedTarget(fixture, UUID_1, "steve")
    const outcome1 = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token: token1 }, NOW)
    expect(outcome1.linked).toBe(true)

    await fixture.accounts.getOrCreate(UUID_2, "alex", NOW)
    const token2 = await fixture.accounts.createLinkToken(UUID_2, NOW)
    expect(token2).not.toBeNull()
    const outcome2 = await fixture.accounts.linkTelegram(
      { telegramId: TG_1, telegramUsername: null, token: token2!.token },
      NOW,
    )
    expect(outcome2).toEqual({ linked: false, reason: "ALREADY_LINKED" })
    // the first link is untouched
    expect(viewOf(fixture, UUID_1).telegramId).toBe(TG_1)
  })

  test("one uuid cannot bind a second telegram", async () => {
    const fixture = buildFixture()
    const token1 = await seedLinkedTarget(fixture)
    await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token: token1 }, NOW)

    // new token for the same uuid won't be issued (already linked) — simulate
    // the race window by issuing before the link lands
    const fixture2 = buildFixture()
    const token2 = await seedLinkedTarget(fixture2)
    const outcomeA = await fixture2.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token: token2 }, NOW)
    expect(outcomeA.linked).toBe(true)
    // second telegram redeems... but the token is gone. issue via raw service
    const rawToken = await fixture2.tokens.issue({ minecraftUuid: UUID_1, username: "steve" }, NOW.getTime())
    const outcomeB = await fixture2.accounts.linkTelegram(
      { telegramId: TG_2, telegramUsername: null, token: rawToken.token },
      NOW,
    )
    expect(outcomeB).toEqual({ linked: false, reason: "ALREADY_LINKED" })
    expect(viewOf(fixture2, UUID_1).telegramId).toBe(TG_1)
  })

  test("second /start for the same pair is an idempotent no-op", async () => {
    const fixture = buildFixture()
    const token1 = await seedLinkedTarget(fixture)
    await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token: token1 }, NOW)
    const rawToken = await fixture.tokens.issue({ minecraftUuid: UUID_1, username: "steve" }, NOW.getTime())
    const outcome = await fixture.accounts.linkTelegram(
      { telegramId: TG_1, telegramUsername: null, token: rawToken.token },
      NOW,
    )
    expect(outcome).toEqual({ linked: true, username: "steve", idempotent: true })
  })

  test("concurrent links with the same token: exactly one wins", async () => {
    const fixture = buildFixture()
    const token = await seedLinkedTarget(fixture)
    const [a, b] = await Promise.all([
      fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW),
      fixture.accounts.linkTelegram({ telegramId: TG_2, telegramUsername: null, token }, NOW),
    ])
    const winners = [a, b].filter((outcome) => outcome.linked)
    expect(winners.length).toBe(1)
  })

  test("create-race on the link insert maps to ALREADY_LINKED", async () => {
    const fixture = buildFixture()
    const token = await seedLinkedTarget(fixture)
    // a concurrent writer sneaks the link in after our checks ran
    const originalVerify = fixture.links.findByTelegramId.bind(fixture.links)
    fixture.links.findByTelegramId = async (telegramId: string) => {
      await fixture.links.create({
        telegramId: "99999",
        minecraftUuid: UUID_1,
        status: "LINKED",
        linkedAt: NOW,
        unlinkedAt: null,
        linkedBy: "bot",
      })
      return originalVerify(telegramId)
    }
    const outcome = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW)
    expect(outcome).toEqual({ linked: false, reason: "ALREADY_LINKED" })
  })

  test("frozen accounts refuse linking", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    const user = fixture.users.users.get(UUID_1)
    if (!user) throw new Error("setup user missing")
    user.status = "BANNED"
    const token = await fixture.accounts.createLinkToken(UUID_1, NOW)
    expect(token).toBeNull()
    const rawToken = await fixture.tokens.issue({ minecraftUuid: UUID_1, username: "steve" }, NOW.getTime())
    const outcome = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token: rawToken.token }, NOW)
    expect(outcome).toEqual({ linked: false, reason: "ACCOUNT_FROZEN" })
  })

  test("rate limits link attempts per telegram id", async () => {
    const fixture = buildFixture()
    // hammer with garbage tokens: 5 allowed, 6th is rate limited
    for (let i = 0; i < 5; i += 1) {
      const outcome = await fixture.accounts.linkTelegram(
        { telegramId: TG_1, telegramUsername: null, token: `g${i}arbagegarbage${i}`.slice(0, 16).padEnd(16, "x") },
        NOW,
      )
      expect(outcome).toEqual({ linked: false, reason: "TOKEN_INVALID" })
    }
    const blocked = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token: "zzzzzzzzzzzzzzzz" }, NOW)
    expect(blocked).toEqual({ linked: false, reason: "RATE_LIMITED" })
  })

  test("notifications fire on success for user and admins", async () => {
    const fixture = buildFixture()
    const token = await seedLinkedTarget(fixture)
    await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW)
    expect(fixture.notifier.userMessages.length).toBe(1)
    expect(fixture.notifier.userMessages[0]?.chatId).toBe(TG_1)
    expect(fixture.notifier.adminMessages.length).toBe(1)
  })

  test("notification failure does not break the link result", async () => {
    const fixture = buildFixture({ notificationsFail: true })
    const token = await seedLinkedTarget(fixture)
    const outcome = await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW)
    expect(outcome.linked).toBe(true)
    expect(viewOf(fixture, UUID_1).telegramLinked).toBe(true)
  })
})

describe("status transitions", () => {
  test("the happy ladder unregistered -> application -> voting -> approved -> active", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    await fixture.accounts.changeStatus(UUID_1, "APPLICATION_PENDING", "SYSTEM", "system", NOW)
    await fixture.accounts.changeStatus(UUID_1, "VOTING", "SYSTEM", "system", NOW)
    await fixture.accounts.changeStatus(UUID_1, "APPROVED", "SYSTEM", "system", NOW)
    const view = await fixture.accounts.changeStatus(UUID_1, "ACTIVE", "SYSTEM", "system", NOW)
    expect(view.status).toBe("ACTIVE")
  })

  test("illegal jumps are rejected and leave the status unchanged", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    let thrown: unknown = null
    try {
      await fixture.accounts.changeStatus(UUID_1, "ACTIVE", "SYSTEM", "system", NOW)
    } catch (err) {
      thrown = err
    }
    expect(thrown).toBeInstanceOf(AccountError)
    expect((thrown as AccountError).code).toBe("INVALID_TRANSITION")
    expect(statusOf(fixture, UUID_1)).toBe("UNREGISTERED")
  })

  test("banned is terminal for gameplay but allows deletion", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    await fixture.accounts.changeStatus(UUID_1, "APPLICATION_PENDING", "SYSTEM", "system", NOW)
    await fixture.accounts.changeStatus(UUID_1, "VOTING", "SYSTEM", "system", NOW)
    await fixture.accounts.changeStatus(UUID_1, "APPROVED", "SYSTEM", "system", NOW)
    await fixture.accounts.changeStatus(UUID_1, "ACTIVE", "SYSTEM", "system", NOW)
    await fixture.accounts.changeStatus(UUID_1, "BANNED", "ADMIN", "admin-1", NOW)
    expect(statusOf(fixture, UUID_1)).toBe("BANNED")
    let thrown: unknown = null
    try {
      await fixture.accounts.changeStatus(UUID_1, "ACTIVE", "ADMIN", "admin-1", NOW)
    } catch (err) {
      thrown = err
    }
    expect(thrown).toBeInstanceOf(AccountError)
    // banned -> deletion pending is allowed
    await fixture.accounts.changeStatus(UUID_1, "DELETION_PENDING", "PLAYER", UUID_1, NOW)
    expect(statusOf(fixture, UUID_1)).toBe("DELETION_PENDING")
  })

  test("suspended can return to active", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    for (const status of ["APPLICATION_PENDING", "VOTING", "APPROVED", "ACTIVE"] as const) {
      await fixture.accounts.changeStatus(UUID_1, status, "SYSTEM", "system", NOW)
    }
    await fixture.accounts.changeStatus(UUID_1, "SUSPENDED", "ADMIN", "admin-1", NOW)
    await fixture.accounts.changeStatus(UUID_1, "ACTIVE", "ADMIN", "admin-1", NOW)
    expect(statusOf(fixture, UUID_1)).toBe("ACTIVE")
  })

  test("every status change is audited and notified", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    const token = await seedLinkedTarget(fixture)
    await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: null, token }, NOW)
    fixture.notifier.userMessages.length = 0
    await fixture.accounts.changeStatus(UUID_1, "APPLICATION_PENDING", "SYSTEM", "system", NOW)
    const statusAudit = fixture.audit.entries.filter((entry) => entry.action === "account.status_changed")
    expect(statusAudit.length).toBe(1)
    expect(statusAudit[0]?.details).toEqual({ from: "UNREGISTERED", to: "APPLICATION_PENDING" })
    expect(fixture.notifier.userMessages.length).toBe(1)
  })
})

describe("language preference", () => {
  test("persists the per-user language", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    await fixture.accounts.setLanguage(UUID_1, "en")
    expect(viewOf(fixture, UUID_1).language).toBe("en")
  })
})
