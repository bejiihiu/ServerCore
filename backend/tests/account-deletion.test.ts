import { describe, expect, test } from "bun:test"
import {
  anonymizedUsername,
  AccountError,
  buildFixture,
  seedLinkedTarget,
  statusOf,
  UUID_1,
  TG_1,
  TG_2,
  type DeletionStep,
} from "./helpers/account-fixture.js"

const NOW = new Date(1_700_000_000_000)

async function seedActiveLinkedAccount(): Promise<ReturnType<typeof buildFixture>> {
  const fixture = buildFixture()
  await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
  const token = await seedLinkedTarget(fixture)
  await fixture.accounts.linkTelegram({ telegramId: TG_1, telegramUsername: "tg_user", token }, NOW)
  // walk the ladder to ACTIVE
  for (const status of ["APPLICATION_PENDING", "VOTING", "APPROVED", "ACTIVE"] as const) {
    await fixture.accounts.changeStatus(UUID_1, status, "SYSTEM", "system", NOW)
  }
  return fixture
}

describe("account deletion pipeline", () => {
  test("runs every step in the spec order and ends anonymized", async () => {
    const fixture = await seedActiveLinkedAccount()
    fixture.sessions.create({
      minecraftUuid: UUID_1,
      serverId: "game-1",
      startedAt: NOW,
      endedAt: null,
      status: "AUTHENTICATED",
    })
    fixture.authRequests.add({
      id: "req-1",
      minecraftUuid: UUID_1,
      telegramId: TG_1,
      sessionId: "session-1",
      tokenHash: "hash",
      status: "PENDING",
      expiresAt: NOW,
      createdAt: NOW,
    })

    const outcome = await fixture.deletion.deleteAccount(UUID_1, TG_1, NOW)

    const expected: DeletionStep[] = [
      "STATUS_SET",
      "AUTH_REQUESTS_CANCELLED",
      "SESSION_REVOKED",
      "GROUP_KICK",
      "INVITES_REVOKED",
      "UNLINKED",
      "ANONYMIZED",
      "AUDIT",
    ]
    expect(outcome.steps).toEqual(expected)
    expect(outcome.cancelledAuthRequests).toBe(1)
    expect(outcome.revokedInvites).toBe(2)
    expect(outcome.deletedUsernameHash).toBe(anonymizedUsername(UUID_1))

    // record survives as a tombstone, personal data gone
    const user = fixture.users.users.get(UUID_1)
    expect(user).not.toBeUndefined()
    expect(user?.username).toBe(anonymizedUsername(UUID_1))
    expect(user?.telegramId).toBe("")
    expect(user?.status).toBe("DELETION_PENDING")
    // link ledger is unlinked, not deleted
    const link = await fixture.links.findByTelegramId(TG_1)
    expect(link?.status).toBe("UNLINKED")
    // session record closed
    const session = fixture.sessions.sessions.get(UUID_1)
    expect(session?.endedAt).toEqual(NOW)
    expect(session?.status).toBe("DISCONNECTED")
    // audit has no personal data
    const audit = fixture.audit.entries.find((entry) => entry.action === "account.deleted")
    expect(audit?.actorId).toBe(anonymizedUsername(UUID_1))
    expect(JSON.stringify(audit)).not.toContain("steve")
    // group kick happened with the right id
    expect(fixture.group.kicked).toEqual([TG_1])
  })

  test("aborts before any personal data is touched when session revoke fails", async () => {
    const fixture = await seedActiveLinkedAccount()
    fixture.revoker.error = new Error("gateway down")

    let thrown: unknown = null
    try {
      await fixture.deletion.deleteAccount(UUID_1, TG_1, NOW)
    } catch (err) {
      thrown = err
    }
    expect(thrown).toBeInstanceOf(AccountError)
    expect((thrown as AccountError).code).toBe("DELETION_FAILED")

    // frozen, but fully recoverable: username and link intact
    expect(statusOf(fixture, UUID_1)).toBe("DELETION_PENDING")
    const user = fixture.users.users.get(UUID_1)
    expect(user?.username).toBe("steve")
    expect(user?.telegramId).toBe(TG_1)
    // the failure notification went out
    expect(fixture.notifier.userMessages.some((message) => message.chatId === TG_1)).toBe(true)
  })

  test("aborts when the group kick fails, username still intact", async () => {
    const fixture = await seedActiveLinkedAccount()
    fixture.group.kickError = new Error("bot lacks admin rights")

    let thrown: unknown = null
    try {
      await fixture.deletion.deleteAccount(UUID_1, TG_1, NOW)
    } catch (err) {
      thrown = err
    }
    expect(thrown).toBeInstanceOf(AccountError)
    const user = fixture.users.users.get(UUID_1)
    expect(user?.username).toBe("steve")
    expect(statusOf(fixture, UUID_1)).toBe("DELETION_PENDING")
  })

  test("wrong telegram id cannot delete someone else's account", async () => {
    const fixture = await seedActiveLinkedAccount()
    let thrown: unknown = null
    try {
      await fixture.deletion.deleteAccount(UUID_1, TG_2, NOW)
    } catch (err) {
      thrown = err
    }
    expect(thrown).toBeInstanceOf(AccountError)
    expect(statusOf(fixture, UUID_1)).toBe("ACTIVE")
    expect(fixture.revoker.revoked.length).toBe(0)
  })

  test("retry after a mid-pipeline failure completes the deletion", async () => {
    const fixture = await seedActiveLinkedAccount()
    fixture.group.kickError = new Error("bot lacks admin rights")
    await fixture.deletion.deleteAccount(UUID_1, TG_1, NOW).catch(() => undefined)
    expect(statusOf(fixture, UUID_1)).toBe("DELETION_PENDING")

    // admin fixes the bot rights, user retries
    fixture.group.kickError = null
    const outcome = await fixture.deletion.deleteAccount(UUID_1, TG_1, NOW)
    // STATUS_SET is skipped: already pending from the first attempt
    expect(outcome.steps).not.toContain("STATUS_SET")
    expect(outcome.steps).toContain("ANONYMIZED")
    expect(outcome.steps).toContain("AUDIT")
    expect(statusOf(fixture, UUID_1)).toBe("DELETION_PENDING")
    expect(fixture.users.users.get(UUID_1)?.username).toBe(anonymizedUsername(UUID_1))
  })

  test("unlinked account can still delete (identity via the requesting chat)", async () => {
    const fixture = buildFixture()
    await fixture.accounts.getOrCreate(UUID_1, "steve", NOW)
    const outcome = await fixture.deletion.deleteAccount(UUID_1, TG_1, NOW)
    expect(outcome.steps).toContain("ANONYMIZED")
    // no group steps: nothing to kick
    expect(outcome.steps).not.toContain("GROUP_KICK")
    expect(fixture.group.kicked.length).toBe(0)
  })

  test("unknown account is a hard error", async () => {
    const fixture = buildFixture()
    let thrown: unknown = null
    try {
      await fixture.deletion.deleteAccount(UUID_1, TG_1, NOW)
    } catch (err) {
      thrown = err
    }
    expect(thrown).toBeInstanceOf(AccountError)
    expect((thrown as AccountError).code).toBe("ACCOUNT_NOT_FOUND")
  })

  test("no connected servers is not a deletion blocker", async () => {
    const fixture = await seedActiveLinkedAccount()
    // FakeSessionRevoker never throws when idle -> empty server list path
    const outcome = await fixture.deletion.deleteAccount(UUID_1, TG_1, NOW)
    expect(outcome.steps).toContain("SESSION_REVOKED")
    expect(outcome.steps).toContain("AUDIT")
  })

  test("anonymized username is stable and never equals the original", () => {
    const hash1 = anonymizedUsername(UUID_1)
    const hash2 = anonymizedUsername(UUID_1)
    expect(hash1).toBe(hash2)
    expect(hash1).not.toBe("steve")
    expect(hash1).toMatch(/^deleted-[0-9a-f]{12}$/)
  })
})
