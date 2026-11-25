import type { UserStatus } from "./types.js"

/**
 * storage-facing record shapes. deliberately plain (no mongoose documents):
 * the service layer works against these interfaces, mongo implements them for
 * real, tests swap in an in-memory implementation of the same contract.
 */
export type UserRecord = {
  minecraftUuid: string
  username: string
  telegramId: string
  telegramUsername: string | null
  telegramLinkedAt: Date | null
  status: UserStatus
  language: "ru" | "en"
  lastApplicationAt: Date | null
  createdAt: Date
  updatedAt: Date
}

export type LinkRecord = {
  telegramId: string
  minecraftUuid: string
  status: "PENDING" | "LINKED" | "UNLINKED"
  linkedAt: Date | null
  unlinkedAt: Date | null
  linkedBy: string | null
}

export type SessionRecord = {
  minecraftUuid: string
  serverId: string
  startedAt: Date
  endedAt: Date | null
  status: "WAITING_AUTH" | "AUTHENTICATED" | "GUEST" | "DISCONNECTED"
}

export type AuthRequestRecord = {
  id: string
  minecraftUuid: string
  telegramId: string
  sessionId: string
  tokenHash: string
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED" | "CANCELLED"
  expiresAt: Date
  createdAt: Date
}

export type AuditRecord = {
  /**
   * deterministic row identity, for events that can be replayed. a second
   * append carrying the same id is a no-op instead of a twin row, which is
   * what makes a retried handler safe. absent means a plain append.
   */
  id?: string
  actorType: "PLAYER" | "ADMIN" | "TELEGRAM" | "SYSTEM"
  actorId: string
  action: string
  targetType: string | null
  targetId: string | null
  details: Record<string, unknown>
  serverId: string | null
  relatedSessionId: string | null
  createdAt: Date
}

/** thrown when a unique index would be violated (mirrors mongo E11000) */
export class DuplicateKeyError extends Error {
  constructor(readonly field: string) {
    super(`duplicate key on ${field}`)
    this.name = "DuplicateKeyError"
  }
}

export interface UserRepository {
  findByUuid(uuid: string): Promise<UserRecord | null>
  findByTelegramId(telegramId: string): Promise<UserRecord | null>
  findByUsername(username: string): Promise<UserRecord | null>
  /** insert; throws DuplicateKeyError on uuid/telegram collisions */
  create(user: UserRecord): Promise<void>
  /** overwrites the mutable fields of the record identified by uuid */
  update(user: UserRecord): Promise<void>
}

export interface TelegramLinkRepository {
  findByTelegramId(telegramId: string): Promise<LinkRecord | null>
  findByUuid(uuid: string): Promise<LinkRecord | null>
  /** insert; DuplicateKeyError when the telegram id or the uuid is taken */
  create(link: LinkRecord): Promise<void>
  update(link: LinkRecord): Promise<void>
  /** removes the record entirely (used when re-linking over a stale unlink) */
  deleteByTelegramId(telegramId: string): Promise<void>
}

export interface SessionRepository {
  findActiveByUuid(uuid: string): Promise<SessionRecord | null>
  create(session: SessionRecord): Promise<void>
  update(session: SessionRecord): Promise<void>
}

export interface AuthRequestRepository {
  /** cancels every pending request for the uuid; returns how many were cancelled */
  cancelPendingForUuid(uuid: string, now: Date): Promise<number>
}

export interface AuditRepository {
  append(record: AuditRecord): Promise<void>
}
