import { AccountDeletionService, anonymizedUsername, type DeletionStep, type SessionRevoker, type TelegramGroupPort } from "../../src/modules/account/deletion.js"
import { AccountService } from "../../src/modules/account/account-service.js"
import { AccountError, type UserStatus } from "../../src/modules/account/types.js"
import type { AuditRecord, AuditRepository, AuthRequestRecord, AuthRequestRepository, LinkRecord, SessionRecord, SessionRepository, TelegramLinkRepository, UserRecord, UserRepository } from "../../src/modules/account/repository.js"
import { InMemoryTokenStore, TokenService } from "../../src/services/token-service.js"
import { silentLogger } from "../../src/websocket/handlers/registry.js"
import type { NotificationFanout } from "../../src/modules/notification/notification-service.js"
import type { OwnershipVerifier } from "../../src/modules/account/session-probe.js"
import type { AccountView } from "../../src/modules/account/account-service.js"

/** silent notification service recording every delivery attempt; mirrors the
 * real NotificationFanout swallow-on-failure semantics */
export class RecordingNotifications implements NotificationFanout {
  readonly userMessages: { chatId: string; text: string }[] = []
  readonly adminMessages: string[] = []
  failures = 0

  constructor(readonly adminChatIds: readonly string[] = [], private readonly fail: boolean = false) {}

  async notifyUser(chatId: string, text: string): Promise<void> {
    if (this.fail) {
      this.failures += 1
      return
    }
    this.userMessages.push({ chatId, text })
  }

  async notifyAdmins(text: string): Promise<void> {
    if (this.fail) {
      this.failures += 1
      return
    }
    this.adminMessages.push(text)
  }
}

/** deterministic ownership verdict the tests flip per scenario */
export class FakeOwnershipVerifier implements OwnershipVerifier {
  result: { ok: true; sessionId: string | null } | { ok: false; reason: "SERVER_OFFLINE" | "NOT_ONLINE" | "TIMEOUT" } = { ok: true, sessionId: "session-1" }
  calls: string[] = []

  async verify(minecraftUuid: string): Promise<{ ok: true; sessionId: string | null } | { ok: false; reason: "SERVER_OFFLINE" | "NOT_ONLINE" | "TIMEOUT" }> {
    this.calls.push(minecraftUuid)
    return this.result
  }
}

export class InMemoryUserRepository implements UserRepository {
  readonly users = new Map<string, UserRecord>()

  async findByUuid(uuid: string): Promise<UserRecord | null> {
    return this.users.get(uuid) ?? null
  }

  async findByTelegramId(telegramId: string): Promise<UserRecord | null> {
    if (telegramId === "") return null
    for (const [, user] of this.users) {
      if (user.telegramId === telegramId) return user
    }
    return null
  }

  async findByUsername(username: string): Promise<UserRecord | null> {
    for (const [, user] of this.users) {
      if (user.username === username) return user
    }
    return null
  }

  async create(user: UserRecord): Promise<void> {
    for (const [, existing] of this.users) {
      if (existing.minecraftUuid === user.minecraftUuid) throw new (await import("../../src/modules/account/repository.js")).DuplicateKeyError("user")
      if (user.telegramId !== "" && existing.telegramId === user.telegramId) throw new (await import("../../src/modules/account/repository.js")).DuplicateKeyError("user")
    }
    this.users.set(user.minecraftUuid, { ...user })
  }

  async update(user: UserRecord): Promise<void> {
    const existing = this.users.get(user.minecraftUuid)
    if (existing) this.users.set(user.minecraftUuid, { ...user })
  }
}

export class InMemoryLinkRepository implements TelegramLinkRepository {
  readonly links = new Map<string, LinkRecord>() // by telegram id

  async findByTelegramId(telegramId: string): Promise<LinkRecord | null> {
    return this.links.get(telegramId) ?? null
  }

  async findByUuid(uuid: string): Promise<LinkRecord | null> {
    for (const [, link] of this.links) {
      if (link.minecraftUuid === uuid) return link
    }
    return null
  }

  async create(link: LinkRecord): Promise<void> {
    if (this.links.has(link.telegramId)) throw new (await import("../../src/modules/account/repository.js")).DuplicateKeyError("telegram_link")
    for (const [, existing] of this.links) {
      if (existing.minecraftUuid === link.minecraftUuid) throw new (await import("../../src/modules/account/repository.js")).DuplicateKeyError("telegram_link")
    }
    this.links.set(link.telegramId, { ...link })
  }

  async update(link: LinkRecord): Promise<void> {
    if (this.links.has(link.telegramId)) this.links.set(link.telegramId, { ...link })
  }

  async deleteByTelegramId(telegramId: string): Promise<void> {
    this.links.delete(telegramId)
  }
}

export class InMemorySessionRepository implements SessionRepository {
  readonly sessions = new Map<string, SessionRecord>() // by uuid

  async findActiveByUuid(uuid: string): Promise<SessionRecord | null> {
    const session = this.sessions.get(uuid)
    return session && session.endedAt === null ? session : null
  }

  async create(session: SessionRecord): Promise<void> {
    this.sessions.set(session.minecraftUuid, { ...session })
  }

  async update(session: SessionRecord): Promise<void> {
    if (this.sessions.has(session.minecraftUuid)) this.sessions.set(session.minecraftUuid, { ...session })
  }
}

export class InMemoryAuthRequestRepository implements AuthRequestRepository {
  readonly requests: AuthRequestRecord[] = []

  async cancelPendingForUuid(uuid: string): Promise<number> {
    let cancelled = 0
    for (const request of this.requests) {
      if (request.minecraftUuid === uuid && request.status === "PENDING") {
        request.status = "CANCELLED"
        cancelled += 1
      }
    }
    return cancelled
  }

  add(request: AuthRequestRecord): void {
    this.requests.push(request)
  }
}

export class InMemoryAuditRepository implements AuditRepository {
  readonly entries: AuditRecord[] = []

  async append(record: AuditRecord): Promise<void> {
    this.entries.push({ ...record, details: { ...record.details } })
  }
}

/** controllable group + revoker doubles */
export class FakeTelegramGroupPort implements TelegramGroupPort {
  kicked: string[] = []
  kickError: Error | null = null
  inviteRevocations = 0

  async kickFromGroup(telegramId: string): Promise<void> {
    if (this.kickError) throw this.kickError
    this.kicked.push(telegramId)
  }

  async revokeUnusedInvites(): Promise<number> {
    this.inviteRevocations += 1
    return 2
  }
}

export class FakeSessionRevoker implements SessionRevoker {
  revoked: string[] = []
  error: Error | null = null

  async revokeLiveSessions(minecraftUuid: string): Promise<void> {
    if (this.error) throw this.error
    this.revoked.push(minecraftUuid)
  }
}

export type AccountTestFixture = {
  accounts: AccountService
  deletion: AccountDeletionService
  users: InMemoryUserRepository
  links: InMemoryLinkRepository
  sessions: InMemorySessionRepository
  authRequests: InMemoryAuthRequestRepository
  audit: InMemoryAuditRepository
  tokens: TokenService
  ownership: FakeOwnershipVerifier
  notifier: RecordingNotifications
  group: FakeTelegramGroupPort
  revoker: FakeSessionRevoker
}

export function buildFixture(options: { notificationsFail?: boolean } = {}): AccountTestFixture {
  const users = new InMemoryUserRepository()
  const links = new InMemoryLinkRepository()
  const sessions = new InMemorySessionRepository()
  const authRequests = new InMemoryAuthRequestRepository()
  const audit = new InMemoryAuditRepository()
  const tokens = new TokenService(new InMemoryTokenStore(), 60)
  const ownership = new FakeOwnershipVerifier()
  const notifier = new RecordingNotifications(["-100admin"], options.notificationsFail ?? false)
  const group = new FakeTelegramGroupPort()
  const revoker = new FakeSessionRevoker()

  const accounts = new AccountService({
    users,
    links,
    audit,
    tokens,
    ownership,
    notifier,
    logger: silentLogger(),
    defaultLanguage: "ru",
  })

  const deletion = new AccountDeletionService({
    users,
    links,
    sessions,
    authRequests,
    audit,
    group,
    sessionRevoker: revoker,
    notifier,
    logger: silentLogger(),
  })

  return { accounts, deletion, users, links, sessions, authRequests, audit, tokens, ownership, notifier, group, revoker }
}

export const UUID_1 = "11111111-1111-4111-8111-111111111111"
export const UUID_2 = "22222222-2222-4222-8222-222222222222"
export const TG_1 = "10001"
export const TG_2 = "10002"

/** seeds one unregistered user and a valid link token for it */
export async function seedLinkedTarget(fixture: AccountTestFixture, uuid = UUID_1, username = "steve", now = new Date(1_700_000_000_000)): Promise<string> {
  await fixture.accounts.getOrCreate(uuid, username, new Date(0))
  const token = await fixture.accounts.createLinkToken(uuid, now)
  if (token === null) throw new Error("token issue failed in test setup")
  return token.token
}

export function statusOf(fixture: AccountTestFixture, uuid: string): UserStatus {
  const user = fixture.users.users.get(uuid)
  if (!user) throw new Error(`no user ${uuid}`)
  return user.status
}

export function viewOf(fixture: AccountTestFixture, uuid: string): AccountView {
  const user = fixture.users.users.get(uuid)
  if (!user) throw new Error(`no user ${uuid}`)
  return {
    minecraftUuid: user.minecraftUuid,
    username: user.username,
    telegramId: user.telegramId,
    telegramUsername: user.telegramUsername,
    telegramLinked: user.telegramId !== "",
    status: user.status,
    language: user.language,
    lastApplicationAt: user.lastApplicationAt,
    createdAt: user.createdAt,
  }
}

export { AccountError, anonymizedUsername, type DeletionStep }
