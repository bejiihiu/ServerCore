package kz.bejiihiu.servercore.sync

import java.util.UUID
import java.util.logging.Logger

/**
 * the fallback [ResyncExecutor] used when packetevents is absent, so the
 * plugin still boots and the sync core keeps the registry rows correct.
 * everything achievable without packets is real; the only thing this
 * executor cannot do is emit the actual packets, and it says so at debug
 * level rather than pretending. the packet-backed replacement lives in
 * [PacketResyncExecutor] and is wired instead whenever the packet layer is
 * online.
 *
 * note that [resetViewer] is genuinely a no-op by design even with a real
 * writer: dropping the overrides *is* the mechanism — vanilla resends on its
 * own once nothing is intercepting the entity packets anymore.
 */
class VanillaOnlyResyncExecutor(
    private val logger: Logger? = null,
) : ResyncExecutor {

    override fun resetViewer(viewer: UUID) {
        // nothing to intercept -> vanilla resend happens by itself
    }

    override fun resyncViewer(viewer: UUID) {
        debug("resync of viewer $viewer deferred: packetevents writer not attached yet")
    }

    override fun resetTarget(viewer: UUID, target: UUID) {
        debug("per-target reset $viewer -> $target deferred: packetevents writer not attached yet")
    }

    override fun resyncTarget(viewer: UUID, target: UUID): ViewState? {
        debug("per-target resync $viewer -> $target deferred: packetevents writer not attached yet")
        return null
    }

    override fun restoreVanilla(viewer: UUID, target: UUID) {
        debug("vanilla restore $viewer -> $target deferred: packetevents writer not attached yet")
    }

    private fun debug(message: String) {
        logger?.fine(message)
    }
}
