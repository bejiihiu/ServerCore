package kz.bejiihiu.servercore.spec

import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import kz.bejiihiu.servercore.protection.AllowedGamemodeOverrideRegistry
import kz.bejiihiu.servercore.spectator.InventoryViewerService
import kz.bejiihiu.servercore.spectator.ObserverHider
import kz.bejiihiu.servercore.spectator.SpecSessionReader
import org.bukkit.GameMode

/**
 * the three bridges that hang the rest of the plugin off the session machine.
 * each one is a dumb [SpecSessionListener]: the coordinator decides, they react.
 */

/**
 * sanctions the /spec gamemode switches through the protection guard's override
 * registry. the grant lands at claim time, before the effects port touches the
 * mode, so the guard's LOWEST-priority event check sees it. on the way out the
 * session grant is revoked and a short-lived grant covers the restore: a saved
 * creative/flying admin must get their old mode back even though the restore
 * event fires after the session is already gone.
 */
class GamemodeSanctionBridge(
    private val registry: AllowedGamemodeOverrideRegistry,
) : SpecSessionListener {

    override fun onClaimed(session: SpecSession) {
        registry.grant(session.adminId, setOf(GameMode.SPECTATOR), SESSION_SOURCE)
    }

    override fun onAborted(session: SpecSession) {
        sanctionRestore(session)
    }

    override fun onEnded(session: SpecSession, reason: ExitReason) {
        sanctionRestore(session)
    }

    private fun sanctionRestore(session: SpecSession) {
        registry.revoke(session.adminId, SESSION_SOURCE)
        session.savedState
            ?.takeIf { it.gamemode != GameMode.SURVIVAL }
            ?.let {
                registry.grant(
                    session.adminId,
                    setOf(it.gamemode),
                    RESTORE_SOURCE,
                    ttlMillis = TimeUnit.SECONDS.toMillis(RESTORE_TTL_SECONDS),
                )
            }
    }

    private companion object {
        const val SESSION_SOURCE = "spec-session"
        const val RESTORE_SOURCE = "spec-restore"

        /** the restore event fires within a couple of scheduler hops, this is generous */
        const val RESTORE_TTL_SECONDS = 60L
    }
}

/**
 * packet-level hiding: the admin's entity leaves the target's client (destroy +
 * suppressed spawns + tab removal) the moment the session settles, and comes
 * back the moment it ends. runs alongside the bukkit-level hide the effects
 * port applies - the two layers agree because both flip at the same lifecycle
 * points and both are idempotent.
 */
class ObserverHidingBridge(
    private val hider: ObserverHider,
) : SpecSessionListener {

    override fun onStarted(session: SpecSession) {
        hider.begin(session.adminId, session.targetId, session.sessionId)
    }

    override fun onEnded(session: SpecSession, reason: ExitReason) {
        // the session id scopes the teardown: a late end from a replaced
        // session must not close the pair that now owns this admin
        hider.end(session.adminId, session.targetId, session.sessionId)
    }
}

/** seam for the live inventory view; keeps this file free of viewer internals */
interface SpecInventoryViews {
    fun open(adminId: UUID, targetId: UUID, reader: SpecSessionReader)
    fun close(adminId: UUID, reason: String)
}

/**
 * live target inventory view: opens with the session (once it is actually
 * active, not while the camera is still attaching) and closes with it.
 */
class InventoryViewerBridge(
    private val views: SpecInventoryViews,
) : SpecSessionListener {

    override fun onStarted(session: SpecSession) {
        views.open(session.adminId, session.targetId, SessionBoundReader(session))
    }

    override fun onEnded(session: SpecSession, reason: ExitReason) {
        views.close(session.adminId, "spec session ended")
    }
}

/**
 * reader bound to one session object instead of a coordinator lookup: the
 * viewer's keep-alive asks "does this session still point at this target" and
 * the session itself is the truth. answers empty once the phase says ENDING,
 * so a backstop keep-alive tick can never resurrect a closed view.
 */
class SessionBoundReader(private val session: SpecSession) : SpecSessionReader {

    override fun currentTarget(): Optional<UUID> =
        if (session.phase == SessionPhase.ENDING) Optional.empty() else Optional.of(session.targetId)
}

/** adapter onto the real viewer service, built in the plugin wiring */
class InventoryViewerViews(
    private val plugin: org.bukkit.plugin.Plugin,
    private val viewer: InventoryViewerService,
) : SpecInventoryViews {

    override fun open(adminId: UUID, targetId: UUID, reader: SpecSessionReader) {
        val admin = plugin.server.getPlayer(adminId) ?: return
        val target = plugin.server.getPlayer(targetId) ?: return
        viewer.open(admin, target, reader)
    }

    override fun close(adminId: UUID, reason: String) {
        viewer.close(adminId, reason)
    }
}
