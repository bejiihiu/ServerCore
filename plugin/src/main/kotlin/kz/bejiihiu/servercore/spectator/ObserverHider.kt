package kz.bejiihiu.servercore.spectator

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kz.bejiihiu.servercore.packet.EntityViewState

/**
 * the hidden observer packet layer for /spec sessions.
 *
 * while an admin observes a target, the admin's client-side entity is removed
 * from the target's view (destroy), every spawn/data packet about the admin is
 * suppressed toward the target (the shared entity listener plus the scoped
 * [SpecTraceListener], keyed off the HIDDEN row written here), and the admin
 * drops out of the target's tab list. on session end everything goes back:
 * player info add, spawn player with fresh metadata and a full equipment
 * resync — captured live off the admin's own entity scheduler, folia-correct.
 *
 * all of this is per-viewer by construction: only the observed target's
 * client is touched, nobody else's picture of the admin changes.
 *
 * the class owns the pair lifecycle and the registry rows; the player handles
 * and the packet bursts sit behind [ObserverWire], so the pairing invariants
 * (begin/end, re-entrancy, shutdown ordering) hold without a server.
 *
 * wrapper names were checked against the packetevents 2.14.0 sources
 * (PLAYER_INFO_REMOVE / PLAYER_INFO_UPDATE, SPAWN_PLAYER, ENTITY_METADATA,
 * ENTITY_EQUIPMENT, ENTITY_HEAD_LOOK, DESTROY_ENTITIES).
 */
class ObserverHider(
    private val sessions: ObserverPairRegistry,
    private val rows: ObserverRows,
    private val wire: ObserverWire,
) {

    /** last captured admin position, keyed by admin uuid, feeds trace attribution */
    private val poses = ConcurrentHashMap<UUID, TracePos>()

    /** per-admin pose refresh handle, closed when their session ends */
    private val poseHandles = ConcurrentHashMap<UUID, AutoCloseable>()

    // ---- session lifecycle ----

    /**
     * open the hiding for a pair; the session bridge calls this once the
     * session settles. [token] identifies the session instance, so a teardown
     * from an older session can tell itself apart from the live one.
     */
    fun begin(admin: UUID, target: UUID, token: Any? = null) {
        if (admin == target) return
        sessions.register(admin, target, token)
        rows.set(target, admin, EntityViewState.HIDDEN)
        trackPose(admin)
        hide(admin, target)
    }

    /**
     * close the hiding for a pair and restore the target's client.
     *
     * a teardown can land after its pair was replaced (the coordinator
     * releases the admin and the next join claims them before this runs).
     * what a replaced teardown may touch is decided by what it owns:
     * - a newer session on the same target owns that client's picture: the
     *   hide, the row and the restore are all skipped, the tracker stays too
     * - a newer session on a *different* target shares nothing with this pair
     *   except the admin's pose tracker, so the tracker stays with the live
     *   session while the old target still gets its picture back (skipping
     *   the restore would leave the admin invisible to that player forever)
     */
    fun end(admin: UUID, target: UUID, token: Any? = null) {
        val current = sessions.observedTarget(admin)
        if (current == target && !sessions.owns(admin, target, token)) return
        val otherPairLive = current != null && current != target
        sessions.unregister(admin, target, token)
        if (!otherPairLive) stopPoseTracking(admin)
        rows.set(target, admin, EntityViewState.VISIBLE)
        restore(admin, target)
    }

    /**
     * either side of an active pair hit a state transition (death, respawn,
     * world change, reconnect). the hide sequence is idempotent, so a full
     * re-assert is the whole policy: destroy is harmless on a client that
     * holds no entity, the row write is idempotent, the tab remove is
     * idempotent. restore deliberately does NOT run here — a transitioning
     * client gets the hide, the session owner decides when it ends.
     */
    fun resync(uuid: UUID) {
        sessions.observedTarget(uuid)?.let { hide(uuid, it) }
        sessions.observersOf(uuid).forEach { hide(it, uuid) }
    }

    /**
     * plugin disable: restore every active pair in deterministic order
     * (admin, then target — same policy as the sync module), then wipe the
     * registries. restore is best-effort this late in the lifecycle; see
     * [ObserverWire.restoreBestEffort].
     */
    fun shutdown() {
        RestorePlan.ordered(sessions.allPairs()).forEach { (admin, target) ->
            sessions.unregister(admin, target)
            stopPoseTracking(admin)
            rows.set(target, admin, EntityViewState.VISIBLE)
            restoreBestEffort(admin, target)
        }
        poseHandles.values.forEach { handle -> runCatching { handle.close() } }
        poseHandles.clear()
        poses.clear()
    }

    // ---- hide / restore ----

    /** idempotent hide sequence, safe to re-run from any resync path */
    fun hide(admin: UUID, target: UUID) {
        if (!wire.online) return
        val entityId = wire.entityId(admin) ?: return
        if (!wire.isPresent(target)) return
        rows.set(target, admin, EntityViewState.HIDDEN)
        wire.hideFrom(target, entityId, admin)
    }

    /** full vanilla-faithful restore of the target's client picture of the admin */
    fun restore(admin: UUID, target: UUID) {
        if (!wire.online) return
        if (!wire.isPresent(target)) return
        wire.restoreTo(target, admin) { entityId -> claimVisible(admin, target, entityId) }
    }

    /** shutdown-flavoured restore, no scheduler hop left to rely on */
    private fun restoreBestEffort(admin: UUID, target: UUID) {
        if (!wire.online) return
        if (!wire.isPresent(target)) return
        wire.restoreBestEffort(target, admin) { entityId -> claimVisible(admin, target, entityId) }
    }

    /**
     * the row flips to VISIBLE and the id is bound right before the restore
     * burst: the shared listener checks the row on every packet, so anything
     * written earlier would let the burst get cancelled as a hidden spawn.
     */
    private fun claimVisible(admin: UUID, target: UUID, entityId: Int) {
        rows.set(target, admin, EntityViewState.VISIBLE)
        rows.bindEntityId(target, entityId, admin)
    }

    // ---- trace attribution (read by the scoped listener) ----

    fun poseOf(admin: UUID): TracePos? = poses[admin]

    // ---- internals ----

    private fun trackPose(admin: UUID) {
        if (poseHandles.containsKey(admin)) return
        val handle = wire.trackPose(admin) { pos -> poses[admin] = pos } ?: return
        poseHandles[admin] = handle
    }

    private fun stopPoseTracking(admin: UUID) {
        poseHandles.remove(admin)?.let { handle -> runCatching { handle.close() } }
        poses.remove(admin)
    }
}
