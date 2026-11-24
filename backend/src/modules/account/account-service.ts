import type { TokenService } from "../../services/token-service.js"
import { AccountError, assertTransition, isFrozen, type UserStatus } from "./types.js"
import type { AuditRepository, TelegramLinkRepository, UserRepository } from "./repository.js"
import type { OwnershipVerifier } from "./session-probe.js"
import { t, statusLabel, type Locale } from "../../services/i18n.js"
import type { NotificationFanout } from "../notification/notification-port.js"
import type { Logger } from "../../logging/logger.js"
import { RateLimiter } from "../../utils/rate-limiter.js"

/** how a status change came about, mirrors the audit actor types we care about */
export type StatusActor = "PLAYER" | "ADMIN" | "TELEGRAM" | "SYSTEM"

export type AccountView = {
  minecraftUuid: string
  username: string
  telegramId: string
  telegramUsername: string | null
  telegramLinked: boolean
  status: UserStatus
  language: Locale
  lastApplicationAt: Date | null
  createdAt: Date
}

export type LinkFailureReason =
  | "TOKEN_INVALID"
  | "ACCOUNT_FROZEN"
  | "ALREADY_LINKED"
  | "OWNERSHIP_NOT_PROVEN"
  | "SERVER_OFFLINE"
  | "RATE_LIMITED"
  | "ACCOUNT_NOT_FOUND"

export type LinkOutcome =
  | { linked: true; username: string; idempotent: boolean }
  | { linked: false; reason: LinkFailureReason }

const LINK_ATTEMPT_LIMIT = 5
const LINK_ATTEMPT_WINDOW_MS = 10 * 60 * 1000
const TOKEN_ISSUE_WINDOW_MS = 60 * 1000

/**
 * the account lifecycle: get-or-create by uuid, telegram linking through
 * one-time tokens plus an in-game ownership probe, and status transitions
 * with ladder validation. deletion lives in its own service next door.
 *
 * offline-mode reality check: the uuid is derived from the nickname and is
 * NOT a security anchor — anyone can claim any nickname. the telegram link
 * is the real identity anchor, and ownership of the minecraft account is
 * proven exclusively through the live-server session challenge. linking
 * fails closed without it: no probe answer, no link. there is no override.
 *
 * nickname-change policy: deliberately NOT supported. a nickname change
 * produces a different (offline-mode) uuid, i.e. a brand-new unregistered
 * account; re-binding the telegram id to the new uuid is a policy
 * prohibition, so old accounts are never migrated to new nicknames.
 */
export class AccountService {
  private readonly users: UserRepository
  private readonly links: TelegramLinkRepository
  private readonly audit: AuditRepository
  private readonly tokens: TokenService
  private readonly ownership: OwnershipVerifier
  private readonly notifier: NotificationFanout
  private readonly logger: Logger
  private readonly defaultLanguage: Locale
  private readonly linkAttempts: RateLimiter
  private readonly tokenIssues: RateLimiter

  constructor(deps: {
    users: UserRepository
    links: TelegramLinkRepository
    audit: AuditRepository
    tokens: TokenService
    ownership: OwnershipVerifier
    notifier: NotificationFanout
    logger: Logger
    defaultLanguage: Locale
  }) {
    this.users = deps.users
    this.links = deps.links
    this.audit = deps.audit
    this.tokens = deps.tokens
    this.ownership = deps.ownership
    this.notifier = deps.notifier
    this.logger = deps.logger
    this.defaultLanguage = deps.defaultLanguage
    this.linkAttempts = new RateLimiter({ limit: LINK_ATTEMPT_LIMIT, windowMs: LINK_ATTEMPT_WINDOW_MS })
    this.tokenIssues = new RateLimiter({ limit: 1, windowMs: TOKEN_ISSUE_WINDOW_MS })
  }

  /** finds the account or creates it in the unregistered state; race-safe */
  async getOrCreate(minecraftUuid: string, username: string, now = new Date()): Promise<AccountView> {
    const existing = await this.users.findByUuid(minecraftUuid)
    if (existing) return this.toView(existing)

    const record = {
      minecraftUuid,
      username,
      telegramId: "",
      telegramUsername: null,
      telegramLinkedAt: null,
      status: "UNREGISTERED" as UserStatus,
      language: this.defaultLanguage,
      lastApplicationAt: null,
      createdAt: now,
      updatedAt: now,
    }
    try {
      await this.users.create(record)
    } catch {
      // lost a create race (same uuid joined twice in parallel) — the winner
      // made the account, just read it back
      const raced = await this.users.findByUuid(minecraftUuid)
      if (!raced) throw new AccountError("ACCOUNT_NOT_FOUND", `account ${minecraftUuid} vanished after create race`)
      return this.toView(raced)
    }
    await this.audit.append({
      actorType: "SYSTEM",
      actorId: minecraftUuid,
      action: "account.created",
      targetType: "user",
      targetId: minecraftUuid,
      details: { username },
      serverId: null,
      relatedSessionId: null,
      createdAt: now,
    })
    return this.toView(record)
  }

  async getByUuid(uuid: string): Promise<AccountView | null> {
    const user = await this.users.findByUuid(uuid)
    return user ? this.toView(user) : null
  }

  async getByTelegramId(telegramId: string): Promise<AccountView | null> {
    const user = await this.users.findByTelegramId(telegramId)
    return user ? this.toView(user) : null
  }

  /** persists the bot language preference on the account */
  async setLanguage(minecraftUuid: string, language: Locale): Promise<void> {
    const user = await this.users.findByUuid(minecraftUuid)
    if (!user) throw new AccountError("ACCOUNT_NOT_FOUND", `account ${minecraftUuid} not found`)
    user.language = language
    user.updatedAt = new Date()
    await this.users.update(user)
  }

  /**
   * issues a fresh deep-link token for an unlinked account. called when a
   * player joins; the backend pushes the token to the plugin (LINK_TOKEN)
   * and the game chat shows the personal deep link.
   */
  async createLinkToken(minecraftUuid: string, now = new Date()): Promise<{ token: string; expiresAt: number } | null> {
    const user = await this.users.findByUuid(minecraftUuid)
    if (!user) return null
    if (user.telegramId !== "") return null
    if (isFrozen(user.status)) return null

    const decision = this.tokenIssues.hit(minecraftUuid, now.getTime())
    if (!decision.allowed) return null

    const { token, expiresAt } = await this.tokens.issue({ minecraftUuid, username: user.username }, now.getTime())
    return { token, expiresAt }
  }

  /**
   * verifies a deep-link token WITHOUT consuming it, for the /start
   * confirmation screen: shows the username before the user agrees. the
   * one-time redeem happens after the user confirms and the probe passes.
   */
  async previewLinkToken(token: string, now = new Date()): Promise<{ ok: true; username: string; minecraftUuid: string } | { ok: false }> {
    const verified = await this.tokens.verify(token, now.getTime())
    if (!verified.valid) return { ok: false }
    const minecraftUuid = verified.meta["minecraftUuid"]
    const username = verified.meta["username"]
    if (minecraftUuid === undefined || username === undefined) return { ok: false }
    const user = await this.users.findByUuid(minecraftUuid)
    if (!user || isFrozen(user.status)) return { ok: false }
    return { ok: true, username, minecraftUuid }
  }

  /**
   * links a telegram account to a minecraft account. order of operations is
   * deliberate:
   *   verify token -> account checks -> link-conflict checks -> ownership
   *   probe -> one-time redeem -> write link
   * the redeem is last so a failed probe never burns the token, and the
   * single-token consumption serializes concurrent linking attempts.
   */
  async linkTelegram(
    params: { telegramId: string; telegramUsername: string | null; token: string },
    now = new Date(),
  ): Promise<LinkOutcome> {
    const attempt = this.linkAttempts.hit(params.telegramId, now.getTime())
    if (!attempt.allowed) return { linked: false, reason: "RATE_LIMITED" }

    const verified = await this.tokens.verify(params.token, now.getTime())
    if (!verified.valid) return { linked: false, reason: "TOKEN_INVALID" }
    const minecraftUuid = verified.meta["minecraftUuid"]
    const username = verified.meta["username"]
    if (minecraftUuid === undefined || username === undefined) {
      return { linked: false, reason: "TOKEN_INVALID" }
    }

    const user = await this.users.findByUuid(minecraftUuid)
    if (!user) return { linked: false, reason: "ACCOUNT_NOT_FOUND" }
    if (isFrozen(user.status)) return { linked: false, reason: "ACCOUNT_FROZEN" }

    // one telegram -> one uuid, one uuid -> one telegram
    const existingForTelegram = await this.links.findByTelegramId(params.telegramId)
    if (existingForTelegram !== null && existingForTelegram.status === "LINKED") {
      if (existingForTelegram.minecraftUuid === minecraftUuid) {
        // second /start for the already-linked pair: no-op, nothing new is
        // granted so no probe is needed
        return { linked: true, username, idempotent: true }
      }
      return { linked: false, reason: "ALREADY_LINKED" }
    }
    const existingForUuid = await this.links.findByUuid(minecraftUuid)
    if (
      existingForUuid !== null &&
      existingForUuid.status === "LINKED" &&
      existingForUuid.telegramId !== params.telegramId
    ) {
      return { linked: false, reason: "ALREADY_LINKED" }
    }

    // ownership challenge: fail closed without a live server session
    const probe = await this.ownership.verify(minecraftUuid)
    if (!probe.ok) {
      return { linked: false, reason: probe.reason === "SERVER_OFFLINE" ? "SERVER_OFFLINE" : "OWNERSHIP_NOT_PROVEN" }
    }

    // one-time consume guards the whole flow: concurrent attempts with the
    // same token lose here
    const redeemed = await this.tokens.redeem(params.token, now.getTime())
    if (!redeemed.valid) return { linked: false, reason: "TOKEN_INVALID" }

    const linkRecord = {
      telegramId: params.telegramId,
      minecraftUuid,
      status: "LINKED" as const,
      linkedAt: now,
      unlinkedAt: null,
      linkedBy: "bot" as const,
    }
    try {
      if (existingForTelegram !== null) {
        // re-link after an unlink: same telegram id, possibly fresh uuid
        await this.links.update({ ...linkRecord })
      } else {
        if (existingForUuid !== null && existingForUuid.telegramId !== params.telegramId) {
          // stale UNLINKED record from a previous owner of this uuid: drop it
          // so the unique uuid index lets the new link in
          await this.links.deleteByTelegramId(existingForUuid.telegramId)
        }
        await this.links.create(linkRecord)
      }
    } catch {
      // unique index fired: someone won the race between our check and write
      return { linked: false, reason: "ALREADY_LINKED" }
    }

    user.telegramId = params.telegramId
    user.telegramUsername = params.telegramUsername
    user.telegramLinkedAt = now
    user.updatedAt = now
    await this.users.update(user)

    await this.audit.append({
      actorType: "TELEGRAM",
      actorId: params.telegramId,
      action: "telegram.linked",
      targetType: "user",
      targetId: minecraftUuid,
      details: {},
      serverId: null,
      relatedSessionId: null,
      createdAt: now,
    })

    this.logger.info({ minecraftUuid, username }, "telegram account linked")
    await this.notifier.notifyUser(params.telegramId, t(user.language, "account.linked", { username }))
    await this.notifier.notifyAdmins(t(this.defaultLanguage, "account.linked", { username }))
    return { linked: true, username, idempotent: false }
  }

  /** validated ladder move; audit + notify on every change */
  async changeStatus(
    minecraftUuid: string,
    to: UserStatus,
    actor: StatusActor,
    actorId: string,
    now = new Date(),
  ): Promise<AccountView> {
    const user = await this.users.findByUuid(minecraftUuid)
    if (!user) throw new AccountError("ACCOUNT_NOT_FOUND", `account ${minecraftUuid} not found`)

    assertTransition(user.status, to)

    const from = user.status
    user.status = to
    user.updatedAt = now
    await this.users.update(user)

    await this.audit.append({
      actorType: actor,
      actorId,
      action: "account.status_changed",
      targetType: "user",
      targetId: minecraftUuid,
      details: { from, to },
      serverId: null,
      relatedSessionId: null,
      createdAt: now,
    })

    if (user.telegramId !== "") {
      await this.notifier.notifyUser(
        user.telegramId,
        t(user.language, "account.status_changed", {
          from: statusLabel(user.language, from),
          to: statusLabel(user.language, to),
        }),
      )
    }
    return this.toView(user)
  }

  private toView(user: {
    minecraftUuid: string
    username: string
    telegramId: string
    telegramUsername: string | null
    telegramLinkedAt: Date | null
    status: UserStatus
    language: Locale
    lastApplicationAt: Date | null
    createdAt: Date
  }): AccountView {
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
}
