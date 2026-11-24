import type { Envelope } from "../protocol/message-types.js"
import type { HandlerRegistry } from "./registry.js"
import type { Logger } from "../../logging/logger.js"
import type { AccountService } from "../../modules/account/account-service.js"
import type { GatewayOwnershipVerifier } from "../../modules/account/session-probe.js"
import type { ServerConnection } from "../connection.js"

/**
 * account-aware gateway handlers:
 * - PLAYER_JOINED / PLAYER_LEFT keep presence logs (with the serverId of the
 *   connection the event came through) and kick off link-token issuance.
 * - SESSION_CHECK_RESULT answers the ownership probe of the linking flow.
 */
export function registerAccountHandlers(
  registry: HandlerRegistry,
  deps: {
    accounts: AccountService
    verifier: GatewayOwnershipVerifier
    logger: Logger
  },
): void {
  registry.register("PLAYER_JOINED", async (envelope: Envelope<"PLAYER_JOINED">, connection: ServerConnection) => {
    const { minecraftUuid, username } = envelope.payload
    // serverId rides on the connection (handshake query param), not on the
    // message: velocity-ready, one identity per connection
    deps.logger.info(
      { uuid: minecraftUuid, username, serverId: connection.serverId },
      "player joined",
    )

    await deps.accounts.getOrCreate(minecraftUuid, username)
    const token = await deps.accounts.createLinkToken(minecraftUuid)
    if (token !== null) {
      // the game chat delivery lands with the guest-mode phase; the token is
      // real and single-use either way
      await connection.send(
        "LINK_TOKEN",
        { minecraftUuid, username, token: token.token, expiresAt: token.expiresAt },
        { awaitAck: true },
      )
    }
  })

  registry.register("PLAYER_LEFT", (envelope: Envelope<"PLAYER_LEFT">, connection: ServerConnection) => {
    deps.logger.info(
      { uuid: envelope.payload.minecraftUuid, reason: envelope.payload.reason, serverId: connection.serverId },
      "player left",
    )
  })

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
