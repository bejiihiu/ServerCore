package kz.bejiihiu.servercore.locale

import java.util.logging.Logger
import kz.bejiihiu.servercore.ws.BackendLink
import kz.bejiihiu.servercore.ws.GatewayListener
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.decodePayload
import kz.bejiihiu.servercore.ws.messageEnvelope

/**
 * consumes LOCALE_PUSH frames: applies the full bundle to the live store and
 * answers LOCALE_ACK with the outcome, correlating through the push's
 * correlationId.
 *
 * runs on the inbound ws thread. applyPush parses off the render path and
 * swaps the store atomically, so a push never makes a region thread see a
 * half-built locale. a rejected file keeps the old store untouched and the
 * rejection reason travels back to the backend.
 */
class LocalePushListener(
    private val locale: LocaleService,
    private val link: BackendLink,
    private val logger: Logger,
) : GatewayListener {

    override fun onMessage(envelope: WsEnvelope) {
        if (envelope.type != LocaleProtocol.TYPE_LOCALE_PUSH) return

        val push = try {
            envelope.decodePayload<LocalePushPayload>()
        } catch (e: Exception) {
            // cannot ack what we could not read; the backend keeps its own
            // revision and re-delivers on the next connection
            logger.warning("locale push ignored, payload did not decode: ${e.message}")
            return
        }

        val ack = when (val result = locale.applyPush(push.locale, push.revision, push.content)) {
            is LocalePushResult.Accepted -> {
                logger.info(
                    "locale bundle applied: ${result.locale} @ ${result.revision} " +
                        "(${locale.snapshot().size} entries)",
                )
                LocaleAckPayload(result.locale, result.revision, accepted = true)
            }
            is LocalePushResult.Rejected -> {
                logger.warning("locale bundle rejected: ${result.locale} @ ${result.revision} — ${result.reason}")
                LocaleAckPayload(result.locale, result.revision, accepted = false, reason = result.reason)
            }
        }

        val sent = link.send(
            messageEnvelope(LocaleProtocol.TYPE_LOCALE_ACK, ack, envelope.correlationId),
        )
        if (!sent) {
            logger.warning("locale ack for ${ack.revision} not sent, link is down")
        }
    }
}
