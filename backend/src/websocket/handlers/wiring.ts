import type { Logger } from "../../logging/logger.js"
import type { HandlerRegistry } from "./registry.js"
import type { ServerDirectory, LifecycleHooks } from "./server-lifecycle.js"
import type { GatewayOwnershipVerifier } from "../../modules/account/session-probe.js"
import type { SpecAuditService } from "../../modules/audit/spec-audit-service.js"
import { registerLifecycleHandlers } from "./server-lifecycle.js"
import { registerAccountHandlers } from "./account-handlers.js"
import { registerSpecHandlers } from "./spec-handlers.js"
import { registerLocaleHandlers } from "./locale-handlers.js"
import type { LocaleService } from "../../services/locale-service.js"

export type HandlerWiringDeps = {
  directory: ServerDirectory
  logger: Logger
  verifier: GatewayOwnershipVerifier
  specAudit: SpecAuditService
  /** canonical locale bundle + ack bookkeeping, fed by LOCALE_ACK */
  locale: LocaleService
  /** module hooks fired from the lifecycle events (ops, authentication) */
  hooks: LifecycleHooks
}

/**
 * registers every websocket handler in one place. PLAYER_JOINED used to be
 * registered twice (lifecycle + account module) which made the registry
 * throw on boot — from here on one registration per message type, with the
 * interested modules riding the lifecycle hooks.
 */
export function registerAllHandlers(registry: HandlerRegistry, deps: HandlerWiringDeps): void {
  registerLifecycleHandlers(registry, deps.directory, deps.logger, {
    ...deps.hooks,
    // the account side of a join rides the same single PLAYER_JOINED event
    onPlayerJoined: (event, connection) => {
      deps.hooks.onPlayerJoined?.(event, connection)
    },
  })

  registerAccountHandlers(registry, {
    verifier: deps.verifier,
    logger: deps.logger,
  })

  registerSpecHandlers(registry, {
    audit: deps.specAudit,
    logger: deps.logger,
  })

  registerLocaleHandlers(registry, {
    locale: deps.locale,
    logger: deps.logger,
  })
}
