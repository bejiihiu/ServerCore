import type { Envelope } from "../protocol/message-types.js"
import type { HandlerRegistry } from "./registry.js"
import type { LocaleService } from "../../services/locale-service.js"
import type { Logger } from "../../logging/logger.js"

export type LocaleHandlerDeps = {
  locale: LocaleService
  logger: Logger
}

/**
 * LOCALE_ACK is the plugin's report after applying a pushed bundle. the push
 * itself is originated from the registration hook (see server-lifecycle), so
 * this handler only closes the loop and surfaces failures: a rejected file, a
 * stale revision, or an ack that answers a push this process never sent.
 */
export function registerLocaleHandlers(registry: HandlerRegistry, deps: LocaleHandlerDeps): void {
  registry.register("LOCALE_ACK", (envelope: Envelope<"LOCALE_ACK">, connection) => {
    const record = deps.locale.recordAck(connection.serverId, envelope.payload, envelope.correlationId)
    switch (record.status) {
      case "applied":
        deps.logger.info(
          { serverId: record.serverId, locale: record.locale, revision: record.revision },
          "locale bundle applied by plugin",
        )
        break
      case "rejected":
        deps.logger.error(
          { serverId: record.serverId, locale: record.locale, revision: record.revision, reason: record.reason },
          "plugin rejected the locale bundle",
        )
        break
      case "stale":
        deps.logger.warn(
          { serverId: record.serverId, locale: record.locale, revision: record.revision },
          "plugin acked a revision this backend no longer serves",
        )
        break
      case "unknown-push":
        deps.logger.warn(
          { serverId: record.serverId, locale: record.locale, revision: record.revision },
          "locale ack without a matching push on this connection",
        )
        break
    }
  })
}
