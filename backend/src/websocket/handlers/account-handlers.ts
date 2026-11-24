import type { Envelope } from "../protocol/message-types.js"
import type { HandlerRegistry } from "./registry.js"
import type { ServerConnection } from "../connection.js"
import type { Logger } from "../../logging/logger.js"
import type { AccountService } from "../../modules/account/account-service.js"
import type { GatewayOwnershipVerifier } from "../../modules/account/session-probe.js"

/**
 * account side of a player join: fresh account row + a one-time link token
 * pushed straight back down the same socket, so the game chat can show the
 * personal bot link. lives as a plain function (not a registry handler)
 * because PLAYER_JOINED is registered exactly once — by the lifecycle
 * wiring, which fans the event out to every interested module.
 */
export async function issueLinkTokenForJoin(
  deps: { accounts: AccountService; logger: Logger },
  event: { minecraftUuid: string; username: string },
  connection: ServerConnection,
): Promise<void> {
  await deps.accounts.getOrCreate(event.minecraftUuid, event.username)
  const token = await deps.accounts.createLinkToken(event.minecraftUuid)
  if (token !== null) {
    // the game chat delivery lands with the guest-mode phase; the token is
    // real and single-use either way
    await connection.send(
      "LINK_TOKEN",
      {
        minecraftUuid: event.minecraftUuid,
        username: event.username,
        token: token.token,
        expiresAt: token.expiresAt,
      },
      { awaitAck: true },
    )
  }
}

/**
 * account-aware gateway handlers: SESSION_CHECK_RESULT answers the ownership
 * probe of the telegram linking flow. player join/leave handling lives in
 * the lifecycle handlers (single registration, module hooks fan out).
 */
export function registerAccountHandlers(
  registry: HandlerRegistry,
  deps: {
    accounts: AccountService
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
