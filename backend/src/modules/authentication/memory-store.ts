import { ACTIVE_USER_STATUS, type SessionStatus } from "./persistence.js"
import type { AuthRequestRecord, AuthenticationStore, SessionRecord, TerminalAuthStatus, UserRecord } from "./persistence.js"

/**
 * process-local twin of the mongo store, byte-compatible semantics. serves
 * the test suite and keeps the module runnable with the database down.
 */
export class InMemoryAuthenticationStore implements AuthenticationStore {
  private readonly sessions = new Map<string, SessionRecord>() // key: uuid, one open session per player
  private readonly requests = new Map<string, AuthRequestRecord>() // key: id
  private readonly tokenIndex = new Map<string, string>() // tokenHash -> id
  private readonly users = new Map<string, UserRecord>() // key: uuid

  putUser(user: UserRecord): void {
    this.users.set(user.minecraftUuid, user)
  }

  /** open sessions only, closed ones are removed from the map */
  get sessionCount(): number {
    return this.sessions.size
  }

  get requestCount(): number {
    return this.requests.size
  }

  async findOpenSession(minecraftUuid: string): Promise<SessionRecord | null> {
    return this.sessions.get(minecraftUuid) ?? null
  }

  async createSession(session: SessionRecord): Promise<SessionRecord> {
    const existing = this.sessions.get(session.minecraftUuid)
    // mirrors the partial unique index: one open session per player
    if (existing) return existing
    this.sessions.set(session.minecraftUuid, { ...session })
    return { ...session }
  }

  async updateOpenSessionStatus(minecraftUuid: string, status: SessionStatus): Promise<SessionRecord | null> {
    const session = this.sessions.get(minecraftUuid)
    if (!session) return null
    session.status = status
    return { ...session }
  }

  async closeOpenSession(minecraftUuid: string): Promise<SessionRecord | null> {
    const session = this.sessions.get(minecraftUuid)
    if (!session) return null
    session.status = "DISCONNECTED"
    session.endedAt = Date.now()
    this.sessions.delete(minecraftUuid)
    return { ...session }
  }

  async insertAuthRequest(request: AuthRequestRecord): Promise<void> {
    if (this.tokenIndex.has(request.tokenHash)) {
      throw new Error("duplicate token hash")
    }
    this.requests.set(request.id, { ...request })
    this.tokenIndex.set(request.tokenHash, request.id)
  }

  async findAuthRequestByTokenHash(tokenHash: string): Promise<AuthRequestRecord | null> {
    const id = this.tokenIndex.get(tokenHash)
    if (!id) return null
    return this.requests.get(id) ?? null
  }

  async claimPendingAuthRequest(
    id: string,
    status: TerminalAuthStatus,
    _now: number,
  ): Promise<AuthRequestRecord | null> {
    const request = this.requests.get(id)
    if (!request || request.status !== "PENDING") return null
    request.status = status
    return { ...request }
  }

  async markAuthRequestDelivered(id: string, deliveredAt: number): Promise<void> {
    const request = this.requests.get(id)
    if (request) request.deliveredAt = deliveredAt
  }

  async cancelPendingForPlayer(minecraftUuid: string, _now: number): Promise<number> {
    let cancelled = 0
    for (const request of this.requests.values()) {
      if (request.minecraftUuid === minecraftUuid && request.status === "PENDING") {
        request.status = "CANCELLED"
        cancelled += 1
      }
    }
    return cancelled
  }

  async findPendingExpired(now: number): Promise<AuthRequestRecord[]> {
    return [...this.requests.values()].filter(
      (r) => r.status === "PENDING" && r.expiresAt <= now,
    )
  }

  async findPendingUndelivered(): Promise<AuthRequestRecord[]> {
    return [...this.requests.values()].filter(
      (r) => r.status === "PENDING" && r.deliveredAt === null,
    )
  }

  async findUserByUuid(minecraftUuid: string): Promise<UserRecord | null> {
    const user = this.users.get(minecraftUuid)
    if (!user) return null
    // only accounts that cleared the whole admission path may authenticate
    return user.status === ACTIVE_USER_STATUS && user.telegramId !== "" ? user : null
  }

  async purgeSessionsOlderThan(cutoff: number): Promise<number> {
    let purged = 0
    for (const [uuid, session] of this.sessions) {
      if (session.startedAt <= cutoff) {
        this.sessions.delete(uuid)
        purged += 1
      }
    }
    return purged
  }
}
