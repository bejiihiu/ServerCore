import { randomHex, sha256Hex } from "../../utils/crypto.js"
import { LINK_TOKEN_LENGTH, isValidLinkTokenFormat } from "../../services/token-service.js"
import { randomToken } from "../../utils/crypto.js"
import type { Logger } from "../../logging/logger.js"
import { SlidingWindowLimiter } from "./rate-limiter.js"
import type { AuthRequestRecord, AuthenticationStore, SessionRecord, SessionStatus } from "./persistence.js"
import type { PayloadOf } from "../../websocket/protocol/message-types.js"

export type AuthModuleConfig = {
  /** one-time token lifetime, the 60-90s band from the wire spec */
  requestTtlSeconds: number
  serverName: string
  rateLimitWindowMs: number
  maxRequestsPerPlayer: number
  maxRequestsPerTelegram: number
  maxApprovalsPerTelegram: number
}

/** auth message types the backend pushes to the plugin over the gateway */
export type BackendAuthType = "AUTH_REQUEST" | "AUTH_APPROVED" | "AUTH_REJECTED" | "AUTH_EXPIRED"

/** pushes a backend->plugin message; false means the server link was down */
export type BackendPush = <T extends BackendAuthType>(
  serverId: string,
  type: T,
  payload: PayloadOf<T>,
  correlationId: string,
) => Promise<boolean>

/**
 * delivers the telegram prompt. boolean false or a throw both mean telegram
 * was unreachable — the request stays pending and the sweeper retries.
 */
export type PromptSender = (request: PromptRequest) => Promise<boolean>

export type PromptRequest = {
  id: string
  minecraftUuid: string
  telegramId: string
  username: string
  /** raw one-time token, lives only in memory and inside the callback data */
  token: string
  expiresInSeconds: number
}

export type JoinDecision =
  | { kind: "AUTH_FLOW_STARTED" }
  | { kind: "GUEST_ONLY"; reason: "NOT_ACTIVE" | "RATE_LIMITED" }

export type CallbackOutcome =
  | { kind: "APPROVED"; minecraftUuid: string; sessionId: string }
  | { kind: "REJECTED"; minecraftUuid: string; sessionId: string }
  | { kind: "ALREADY_PROCESSED" }
  | { kind: "EXPIRED" }
  | { kind: "NOT_YOURS" }
  | { kind: "UNKNOWN" }
  | { kind: "NO_ACTIVE_SESSION" }
  | { kind: "STALE_SESSION" }
  | { kind: "RATE_LIMITED" }

type QueuedPush = {
  serverId: string
  type: BackendAuthType
  payload: PayloadOf<BackendAuthType>
  correlationId: string
  queuedAt: number
}

/** how long a queued backend->plugin push waits for the link to come back */
const OUTBOX_GIVE_UP_MS = 10 * 60_000

export type AuthSessionStats = {
  activeSessions: number
  guestSessions: number
  pendingAuths: number
}

const MAX_TOKEN_ISSUE_ATTEMPTS = 5

/**
 * the whole telegram login flow: session lifecycle plus one-time auth
 * requests with full replay/expiry/binding protection.
 *
 * fail-closed posture: nothing here ever grants access on its own. approval
 * requires a live PENDING request whose token hash, telegram id, expiry and
 * session binding all check out; a dead telegram or backend link just leaves
 * the player in guest mode.
 */
export class AuthenticationService {
  private readonly store: AuthenticationStore
  private readonly config: AuthModuleConfig
  private readonly logger: Logger
  private readonly push: BackendPush
  private readonly sendPrompt: PromptSender | null

  private readonly joinLimiter: SlidingWindowLimiter
  private readonly promptLimiter: SlidingWindowLimiter
  private readonly approvalLimiter: SlidingWindowLimiter

  /** pushes that failed because the game server link was down, retried by sweep */
  private readonly outbox = new Map<string, QueuedPush>()

  /** raw tokens live only here, keyed by request id, for prompt building */
  private readonly promptTokens = new Map<string, string>()

  /** local mirror of open sessions, feeds the /stats counters */
  private readonly openSessions = new Map<string, SessionStatus>()

  // the sweeper runs on a fixed interval but a pass (mongo + telegram fan-out)
  // can take longer than that; overlapping passes would re-deliver prompts to
  // the same players, so a tick that arrives mid-pass is skipped
  private sweeping = false

  constructor(deps: {
    store: AuthenticationStore
    config: AuthModuleConfig
    logger: Logger
    push: BackendPush
    sendPrompt: PromptSender | null
  }) {
    this.store = deps.store
    this.config = deps.config
    this.logger = deps.logger
    this.push = deps.push
    this.sendPrompt = deps.sendPrompt
    this.joinLimiter = new SlidingWindowLimiter({
      windowMs: deps.config.rateLimitWindowMs,
      maxEvents: deps.config.maxRequestsPerPlayer,
    })
    this.promptLimiter = new SlidingWindowLimiter({
      windowMs: deps.config.rateLimitWindowMs,
      maxEvents: deps.config.maxRequestsPerTelegram,
    })
    this.approvalLimiter = new SlidingWindowLimiter({
      windowMs: deps.config.rateLimitWindowMs,
      maxEvents: deps.config.maxApprovalsPerTelegram,
    })
  }

  /** live counters for the /stats dashboard, all derived from tracked events */
  stats(): AuthSessionStats {
    let active = 0
    let guest = 0
    let pending = 0
    for (const status of this.openSessions.values()) {
      if (status === "AUTHENTICATED") active += 1
      else if (status === "GUEST") guest += 1
      else if (status === "WAITING_AUTH") pending += 1
    }
    return { activeSessions: active, guestSessions: guest, pendingAuths: pending }
  }

  // ---- session lifecycle, driven by plugin events ----

  async handlePlayerJoined(
    event: { minecraftUuid: string; username: string; serverId: string },
    now: number,
  ): Promise<JoinDecision> {
    const user = await this.store.findUserByUuid(event.minecraftUuid)
    if (!user) {
      // unknown or not-yet-approved account: the account module owns that
      // answer to the plugin, this module only does session bookkeeping
      return { kind: "GUEST_ONLY", reason: "NOT_ACTIVE" }
    }

    // a join always starts a fresh game session: whatever open session
    // survived (a PLAYER_LEFT lost during a link blip) is stale by definition
    await this.store.closeOpenSession(event.minecraftUuid)
    this.openSessions.delete(event.minecraftUuid)
    // cancel-on-new: any pending request from a previous session dies here
    await this.store.cancelPendingForPlayer(event.minecraftUuid, now)

    const allowed =
      this.joinLimiter.tryAcquire(event.minecraftUuid, now) &&
      this.promptLimiter.tryAcquire(user.telegramId, now)
    if (!allowed) {
      this.logger.warn({ uuid: event.minecraftUuid }, "auth request rate limited, player stays guest")
      return { kind: "GUEST_ONLY", reason: "RATE_LIMITED" }
    }

    const session = await this.store.createSession({
      minecraftUuid: event.minecraftUuid,
      sessionId: newSessionId("auth"),
      serverId: event.serverId,
      status: "WAITING_AUTH",
      startedAt: now,
      endedAt: null,
    })
    this.openSessions.set(event.minecraftUuid, "WAITING_AUTH")

    const request = await this.issueRequest(user, session, now)
    await this.deliver(request, now)
    await this.enqueue(
      event.serverId,
      "AUTH_REQUEST",
      {
        minecraftUuid: event.minecraftUuid,
        username: event.username,
        sessionId: session.sessionId,
        expiresInSeconds: this.config.requestTtlSeconds,
      },
      request.id,
      now,
    )
    return { kind: "AUTH_FLOW_STARTED" }
  }

  async handlePlayerLeft(minecraftUuid: string, now: number): Promise<void> {
    await this.store.closeOpenSession(minecraftUuid)
    this.openSessions.delete(minecraftUuid)
    const cancelled = await this.store.cancelPendingForPlayer(minecraftUuid, now)
    if (cancelled > 0) {
      this.logger.info({ uuid: minecraftUuid, cancelled }, "pending auth requests cancelled on disconnect")
    }
  }

  /** plugin announced a live guest session: record it when we have nothing on file */
  async handleGuestStarted(
    event: { minecraftUuid: string; sessionId: string },
    serverId: string,
    now: number,
  ): Promise<void> {
    const existing = await this.store.findOpenSession(event.minecraftUuid)
    if (existing !== null) {
      // same session id = a redelivered GUEST_STARTED (reconnect replay):
      // the session is already on file, exactly once, no duplicate row
      if (existing.sessionId === event.sessionId) {
        this.openSessions.set(event.minecraftUuid, existing.status)
        return
      }
      // a waiting-auth session keeps its own binding untouched; the plugin's
      // guest id is plugin-local bookkeeping and never overrides it
      this.openSessions.set(event.minecraftUuid, existing.status)
      return
    }
    await this.store.createSession({
      minecraftUuid: event.minecraftUuid,
      sessionId: event.sessionId,
      serverId,
      status: "GUEST",
      startedAt: now,
      endedAt: null,
    })
    this.openSessions.set(event.minecraftUuid, "GUEST")
  }

  async handleGuestEnded(event: { minecraftUuid: string; sessionId?: string; reason: string }): Promise<void> {
    if (event.reason === "AUTHENTICATED") {
      // approval already flipped the status; this is the plugin's confirmation
      await this.store.updateOpenSessionStatus(event.minecraftUuid, "AUTHENTICATED")
      this.openSessions.set(event.minecraftUuid, "AUTHENTICATED")
      return
    }
    await this.store.closeOpenSession(event.minecraftUuid)
    this.openSessions.delete(event.minecraftUuid)
  }

  // ---- telegram decisions ----

  async approve(rawToken: string, telegramId: string, now: number): Promise<CallbackOutcome> {
    if (!isValidAuthCallbackToken(rawToken)) return { kind: "UNKNOWN" }
    if (!this.approvalLimiter.tryAcquire(telegramId, now)) {
      this.logger.warn({ telegramId }, "auth approval rate limited")
      return { kind: "RATE_LIMITED" }
    }

    const request = await this.store.findAuthRequestByTokenHash(sha256Hex(rawToken))
    if (!request) return { kind: "UNKNOWN" }
    if (request.telegramId !== telegramId) return { kind: "NOT_YOURS" }
    if (request.status !== "PENDING") return { kind: "ALREADY_PROCESSED" }
    if (request.expiresAt <= now) {
      await this.store.claimPendingAuthRequest(request.id, "EXPIRED", now)
      return { kind: "EXPIRED" }
    }

    // no old-session approval: the request must point at the live open session
    const session = await this.store.findOpenSession(request.minecraftUuid)
    if (!session) {
      await this.store.claimPendingAuthRequest(request.id, "CANCELLED", now)
      return { kind: "NO_ACTIVE_SESSION" }
    }
    if (session.status !== "WAITING_AUTH" || session.sessionId !== request.sessionId) {
      await this.store.claimPendingAuthRequest(request.id, "CANCELLED", now)
      return { kind: "STALE_SESSION" }
    }

    // atomic one-time consume: exactly one press in the whole universe wins
    const claimed = await this.store.claimPendingAuthRequest(request.id, "APPROVED", now)
    if (!claimed) return { kind: "ALREADY_PROCESSED" }

    await this.store.updateOpenSessionStatus(request.minecraftUuid, "AUTHENTICATED")
    this.openSessions.set(request.minecraftUuid, "AUTHENTICATED")
    await this.enqueue(
      session.serverId,
      "AUTH_APPROVED",
      { minecraftUuid: request.minecraftUuid, sessionId: request.sessionId },
      request.id,
      now,
    )
    this.logger.info({ uuid: request.minecraftUuid, sessionId: request.sessionId }, "login approved via telegram")
    return { kind: "APPROVED", minecraftUuid: request.minecraftUuid, sessionId: request.sessionId }
  }

  async reject(rawToken: string, telegramId: string, now: number): Promise<CallbackOutcome> {
    if (!isValidAuthCallbackToken(rawToken)) return { kind: "UNKNOWN" }
    if (!this.approvalLimiter.tryAcquire(telegramId, now)) {
      return { kind: "RATE_LIMITED" }
    }

    const request = await this.store.findAuthRequestByTokenHash(sha256Hex(rawToken))
    if (!request) return { kind: "UNKNOWN" }
    if (request.telegramId !== telegramId) return { kind: "NOT_YOURS" }
    if (request.status !== "PENDING") return { kind: "ALREADY_PROCESSED" }
    if (request.expiresAt <= now) {
      await this.store.claimPendingAuthRequest(request.id, "EXPIRED", now)
      return { kind: "EXPIRED" }
    }

    const session = await this.store.findOpenSession(request.minecraftUuid)
    if (!session || session.status !== "WAITING_AUTH" || session.sessionId !== request.sessionId) {
      await this.store.claimPendingAuthRequest(request.id, "CANCELLED", now)
      return { kind: "STALE_SESSION" }
    }

    const claimed = await this.store.claimPendingAuthRequest(request.id, "REJECTED", now)
    if (!claimed) return { kind: "ALREADY_PROCESSED" }

    // the player stays in the game as a guest, the plugin decides the UX
    await this.store.updateOpenSessionStatus(request.minecraftUuid, "GUEST")
    this.openSessions.set(request.minecraftUuid, "GUEST")
    await this.enqueue(
      session.serverId,
      "AUTH_REJECTED",
      { minecraftUuid: request.minecraftUuid, sessionId: request.sessionId, reason: "DECLINED" },
      request.id,
      now,
    )
    this.logger.info({ uuid: request.minecraftUuid }, "login rejected via telegram")
    return { kind: "REJECTED", minecraftUuid: request.minecraftUuid, sessionId: request.sessionId }
  }

  // ---- background sweep ----

  /**
   * expires dead requests, retries undelivered prompts, flushes the outbox.
   * called on a timer by the wiring; safe to call at any rate — a pass that is
   * still running makes the next call a no-op instead of stacking overlaps.
   */
  async sweep(now: number): Promise<void> {
    if (this.sweeping) return
    this.sweeping = true
    try {
      for (const request of await this.store.findPendingExpired(now)) {
        const claimed = await this.store.claimPendingAuthRequest(request.id, "EXPIRED", now)
        if (!claimed) continue
        // the player keeps playing as guest after the window closes
        const session = await this.store.findOpenSession(request.minecraftUuid)
        if (session !== null && session.status === "WAITING_AUTH" && session.sessionId === request.sessionId) {
          await this.store.updateOpenSessionStatus(request.minecraftUuid, "GUEST")
          this.openSessions.set(request.minecraftUuid, "GUEST")
          await this.enqueue(
            session.serverId,
            "AUTH_EXPIRED",
            { minecraftUuid: request.minecraftUuid, sessionId: request.sessionId },
            request.id,
            now,
          )
        }
        this.logger.info({ uuid: request.minecraftUuid }, "auth request expired")
      }

      if (this.sendPrompt !== null) {
        for (const request of await this.store.findPendingUndelivered()) {
          await this.deliver(request, now)
        }
      }

      await this.flushOutbox(now)

      this.joinLimiter.prune(now)
      this.promptLimiter.prune(now)
      this.approvalLimiter.prune(now)
    } finally {
      // a throwing pass must release the guard, or the sweeper dies for good
      this.sweeping = false
    }
  }

  /** drops queued pushes that never got a live connection back */
  stop(): void {
    this.outbox.clear()
    this.promptTokens.clear()
  }

  /**
   * read-only lookup used by the telegram ui to find which request a decided
   * token belongs to (for the message rewrite). verifies ownership, returns
   * null for foreign or unknown tokens — never leaks request ids.
   */
  async locate(token: string, telegramId: string): Promise<{ id: string } | null> {
    if (!isValidAuthCallbackToken(token)) return null
    const request = await this.store.findAuthRequestByTokenHash(sha256Hex(token))
    if (!request || request.telegramId !== telegramId) return null
    return { id: request.id }
  }

  /** display name of the account a token was issued for, null when unknown */
  async usernameForToken(token: string): Promise<string | null> {
    if (!isValidAuthCallbackToken(token)) return null
    const request = await this.store.findAuthRequestByTokenHash(sha256Hex(token))
    if (!request) return null
    const user = await this.store.findUserByUuid(request.minecraftUuid)
    return user?.username ?? null
  }

  // ---- internals ----

  private async issueRequest(
    user: { minecraftUuid: string; telegramId: string },
    session: SessionRecord,
    now: number,
  ): Promise<AuthRequestRecord> {
    const expiresAt = now + this.config.requestTtlSeconds * 1000
    for (let attempt = 0; attempt < MAX_TOKEN_ISSUE_ATTEMPTS; attempt += 1) {
      const token = randomToken(LINK_TOKEN_LENGTH)
      const record: AuthRequestRecord = {
        id: randomHex(12),
        minecraftUuid: user.minecraftUuid,
        telegramId: user.telegramId,
        sessionId: session.sessionId,
        tokenHash: sha256Hex(token),
        status: "PENDING",
        deliveredAt: null,
        expiresAt,
        createdAt: now,
      }
      try {
        await this.store.insertAuthRequest(record)
        // the raw token exists exactly here: it rides to telegram inside the
        // callback data and is never persisted anywhere
        this.promptTokens.set(record.id, token)
        return record
      } catch (error) {
        // duplicate token hash: vanishingly rare, regenerate and move on
        this.logger.warn({ err: error, attempt }, "token hash collision, regenerating")
      }
    }
    throw new Error("could not issue a unique auth token")
  }

  private async deliver(request: AuthRequestRecord, now: number): Promise<void> {
    const token = this.promptTokens.get(request.id)
    if (token === undefined) return // already delivered once, or lost with a restart

    if (this.sendPrompt === null) {
      // no telegram bot configured: nothing can ever be approved, the request
      // just expires. no auto-approval, exactly the fail-closed posture
      this.promptTokens.delete(request.id)
      return
    }

    if (request.expiresAt <= now) {
      this.promptTokens.delete(request.id)
      return
    }

    const username = await this.usernameFor(request)
    let delivered = false
    try {
      delivered = await this.sendPrompt({
        id: request.id,
        minecraftUuid: request.minecraftUuid,
        telegramId: request.telegramId,
        username,
        token,
        expiresInSeconds: Math.max(1, Math.ceil((request.expiresAt - now) / 1000)),
      })
    } catch (error) {
      this.logger.warn({ err: error, requestId: request.id }, "telegram prompt delivery failed, will retry")
    }
    if (delivered) {
      await this.store.markAuthRequestDelivered(request.id, now)
      this.promptTokens.delete(request.id)
    }
  }

  private async usernameFor(request: AuthRequestRecord): Promise<string> {
    const user = await this.store.findUserByUuid(request.minecraftUuid)
    return user?.username ?? request.minecraftUuid
  }

  /** push now when the link is alive, otherwise park it for the sweeper */
  private async enqueue<T extends BackendAuthType>(
    serverId: string,
    type: T,
    payload: PayloadOf<T>,
    correlationId: string,
    now: number,
  ): Promise<void> {
    const sent = await this.push(serverId, type, payload, correlationId)
    if (sent) return
    this.outbox.set(`${serverId}|${type}|${correlationId}`, {
      serverId,
      type,
      payload,
      correlationId,
      queuedAt: now,
    })
    this.logger.info({ serverId, type }, "game server link down, push queued for retry")
  }

  private async flushOutbox(now: number): Promise<void> {
    if (this.outbox.size === 0) return
    for (const [key, queued] of this.outbox) {
      // a push older than the give-up window is dead weight: the next player
      // event re-syncs the state anyway (idempotent join/guest reconciliation)
      if (now - queued.queuedAt > OUTBOX_GIVE_UP_MS) {
        this.outbox.delete(key)
        this.logger.warn({ type: queued.type, serverId: queued.serverId }, "queued push expired unsent")
        continue
      }
      const sent = await this.push(queued.serverId, queued.type, queued.payload, queued.correlationId)
      if (sent) this.outbox.delete(key)
    }
  }
}

/** callback data carries the same 16-char token material as deep links */
function isValidAuthCallbackToken(raw: string): boolean {
  return isValidLinkTokenFormat(raw)
}

/** session ids must satisfy the wire pattern [a-z0-9._-]{1,64} */
function newSessionId(prefix: "auth" | "guest"): string {
  return `${prefix}-${randomHex(6)}`
}
