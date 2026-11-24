import type { AuthRequestStatus } from "../../database/models/auth-request.js"
import type { SessionStatus } from "../../database/models/session.js"

export type { AuthRequestStatus, SessionStatus }

export const ACTIVE_USER_STATUS = "ACTIVE"

export type AuthRequestRecord = {
  id: string
  minecraftUuid: string
  telegramId: string
  sessionId: string
  /** sha-256 of the one-time token, the raw token never touches storage */
  tokenHash: string
  status: AuthRequestStatus
  /** first successful prompt delivery, null while telegram retries are due */
  deliveredAt: number | null
  expiresAt: number
  createdAt: number
}

export type SessionRecord = {
  minecraftUuid: string
  /** backend-generated live session id, travels to the plugin in auth messages */
  sessionId: string
  serverId: string
  status: SessionStatus
  startedAt: number
  endedAt: number | null
}

export type UserRecord = {
  minecraftUuid: string
  username: string
  telegramId: string
  status: string
}

export type TerminalAuthStatus = Exclude<AuthRequestStatus, "PENDING">

/**
 * durable copy of what the plugin captured before it degraded a player to
 * guest. one document per plugin session id; the first capture wins.
 */
export type GuestSnapshotRecord = {
  sessionId: string
  minecraftUuid: string
  world: string
  location: {
    x: number
    y: number
    z: number
    yaw: number
    pitch: number
  }
  gameMode: string
  flying: boolean
  flySpeed: number
  walkSpeed: number
}

/**
 * storage seam for the authentication module. the mongo implementation is the
 * production path; tests run against the in-memory twin so the whole
 * protection matrix stays hermetic.
 */
export interface AuthenticationStore {
  // ---- sessions ----

  /** the one session without an end timestamp, null when the player is offline */
  findOpenSession(minecraftUuid: string): Promise<SessionRecord | null>

  createSession(session: SessionRecord): Promise<SessionRecord>

  /** unconditional status patch on the open session, null when none is open */
  updateOpenSessionStatus(minecraftUuid: string, status: SessionStatus): Promise<SessionRecord | null>

  /** closes the open session as DISCONNECTED, idempotent, null when already closed */
  closeOpenSession(minecraftUuid: string): Promise<SessionRecord | null>

  // ---- guest snapshots ----

  /**
   * stores the pre-guest state for a plugin session. insert-only: a redelivered
   * durable frame must never overwrite the original capture, only confirm it.
   */
  saveGuestSnapshot(snapshot: GuestSnapshotRecord): Promise<void>

  /** drops the snapshot of a session that is over, returns how many rows died */
  deleteGuestSnapshot(sessionId: string): Promise<number>

  // ---- auth requests ----

  insertAuthRequest(request: AuthRequestRecord): Promise<void>

  findAuthRequestByTokenHash(tokenHash: string): Promise<AuthRequestRecord | null>

  /**
   * atomic one-time consume: PENDING -> the requested terminal status.
   * returns the updated record, or null when the request was not in PENDING
   * (lost race, already used, already cancelled) — that is the replay guard.
   */
  claimPendingAuthRequest(id: string, status: TerminalAuthStatus, now: number): Promise<AuthRequestRecord | null>

  markAuthRequestDelivered(id: string, deliveredAt: number): Promise<void>

  /** cancels every PENDING request of the player, returns how many died */
  cancelPendingForPlayer(minecraftUuid: string, now: number): Promise<number>

  /** PENDING requests past their expiry, for the sweeper */
  findPendingExpired(now: number): Promise<AuthRequestRecord[]>

  /** PENDING requests whose prompt never reached telegram, for retries */
  findPendingUndelivered(): Promise<AuthRequestRecord[]>

  // ---- users ----

  findUserByUuid(minecraftUuid: string): Promise<UserRecord | null>
}
