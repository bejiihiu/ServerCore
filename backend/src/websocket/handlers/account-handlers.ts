import type { Envelope } from "../protocol/message-types.js"
import type { HandlerRegistry } from "./registry.js"
import type { Logger } from "../../logging/logger.js"
import type { Gateway } from "../gateway.js"
import type { AccountService } from "../../modules/account/account-service.js"
import type { GatewayOwnershipVerifier } from "../../modules/account/session-probe.js"

/**
 * account-aware gateway handlers:
 * - SESSION_CHECK_RESULT answers the ownership probe of the linking flow.
 * - the player join/left work lives in [AccountJoinFlow] below: the lifecycle
 *   module owns the PLAYER_JOINED / PLAYER_LEFT registrations (exactly one
 *   registrar per type — the handler registry rejects duplicates), and the
 *   app wiring composes the account flow with the auth flow in one hook.
 */
export function registerAccountHandlers(
  registry: HandlerRegistry,
  deps: {
    verifier: GatewayOwnershipVerifier
    logger: Logger
  },
): void {
  registry.register(
    "SESSION_CHECK_RESULT",
    (envelope: Envelope<"SESSION_CHECK_RESULT">) => {
      deps.verifier.onProbeAnswer(
        envelope.correlationId,
        envelope.payload.online,
        envelope.payload.sessionId ?? null,
      )
    },
  )
}

/**
 * the account side of a player join: the row is created before anything else
 * consumes the join (the auth flow only answers ACTIVE+linked accounts, so
 * the row must exist by then), and unlinked players get a fresh one-time
 * deep-link token pushed to the game chat. the link token is best effort:
 * a downed game-server link wastes this token, the next join issues a new one.
 */
export class AccountJoinFlow {
  private readonly accounts: AccountService
  private readonly gateway: Gateway
  private readonly logger: Logger

  constructor(deps: { accounts: AccountService; gateway: Gateway; logger: Logger }) {
    this.accounts = deps.accounts
    this.gateway = deps.gateway
    this.logger = deps.logger
  }

  /** account row + personal linking token for one join, in that order */
  async onPlayerJoined(event: { minecraftUuid: string; username: string; serverId: string }): Promise<void> {
    await this.accounts.getOrCreate(event.minecraftUuid, event.username)
    const token = await this.accounts.createLinkToken(event.minecraftUuid)
    if (token === null) return
    try {
      await this.gateway.sendTo(
        event.serverId,
        "LINK_TOKEN",
        {
          minecraftUuid: event.minecraftUuid,
          username: event.username,
          token: token.token,
          expiresAt: token.expiresAt,
        },
        { awaitAck: true },
      )
    } catch (error) {
      // the token survives unconsumed; the next join issues a fresh one
      this.logger.warn({ err: error, serverId: event.serverId }, "link token push failed")
    }
  }

  onPlayerLeft(event: { minecraftUuid: string; username?: string; reason: string }): void {
    // the lifecycle handler already logs the player-left milestone; here only
    // the account-side view of it, useful when chasing a linking problem
    this.logger.debug(
      { uuid: event.minecraftUuid, username: event.username, reason: event.reason },
      "account flow saw the player leave",
    )
  }
}
