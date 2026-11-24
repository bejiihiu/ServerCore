package kz.bejiihiu.servercore.sync

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/** what one plugin disable produced, in the exact restore order */
data class RestoreReport(val restored: List<ViewStateRow>)

/** what one reconciliation pass fixed */
data class ReconcileReport(val reapplied: Int, val droppedRows: Int)

/**
 * the packet-state sync coordinator: turns every spec-mandated state
 * transition into invalidation + clean resync work, in both directions
 * (the transitioning player as viewer of others, and as target seen by others).
 *
 * pure logic, no bukkit imports: listeners and the folia reconciler feed
 * transitions in, all state lives behind [StateInvalidator] and
 * [EntityIdentityTracker], all packet work goes out through [ResyncExecutor].
 * that keeps the whole transition layer testable without any runtime.
 *
 * threading: every method is safe from any folia region thread. internal
 * state is concurrent; the writer is responsible for its own thread hops.
 */
class PacketStateSync private constructor(
    private val states: StateInvalidator,
    private val resync: ResyncExecutor,
    private val identities: EntityIdentityTracker,
    private val logger: Logger?,
) {

    /**
     * last identity generation whose state was (re)applied per target; the
     * reconciliation pass compares against this to find drift without
     * re-sending everything on every pass.
     */
    private val appliedGeneration = ConcurrentHashMap<UUID, Long>()

    /**
     * try to become the one live sync instance. false means another instance
     * still holds the claim — the caller must not register listeners and must
     * log loudly instead.
     */
    fun tryClaim(): Boolean = CLAIMED.compareAndSet(false, true)

    /** release the claim during disable, allowing the next enable to claim it */
    fun release() {
        CLAIMED.set(false)
    }

    /**
     * a transition happened for one player. both directions are handled per
     * the invalidation matrix: what their own client view needs, and what
     * every active viewer of them needs.
     */
    fun onTransition(reason: SyncReason, uuid: UUID) {
        when (reason) {
            // the disable path is a dedicated ordered restore, not a matrix row
            SyncReason.PLUGIN_RELOAD -> {
                restoreAllForReload()
                return
            }
            // reconcile is its own pass with drift repair, not a matrix row
            SyncReason.RECONCILE -> {
                reconcile()
                return
            }
            else -> {}
        }

        val policy = policyFor(reason)

        when (policy.viewer) {
            ViewerAction.RESET -> resync.resetViewer(uuid)
            ViewerAction.RESEND -> resync.resyncViewer(uuid)
            ViewerAction.NONE -> {}
        }

        when (policy.target) {
            // respawn is the only transition allowed to clear the DEAD pin:
            // every other re-apply on a dead target must keep vanilla death
            TargetAction.REAPPLY -> reapplyTarget(uuid, clearDead = reason == SyncReason.RESPAWN)
            TargetAction.RESET_FOR_VIEWERS -> resetTargetForViewers(uuid)
            TargetAction.MARK_DEAD -> markDead(uuid)
            TargetAction.DROP -> {
                states.clearTarget(uuid)
                appliedGeneration.remove(uuid)
            }
            TargetAction.NONE -> {}
        }
    }

    /**
     * the packet layer registered a new (uuid, entity id) binding. entity id
     * reuse is the dangerous case: an id that used to belong to a despawned
     * entity now belongs to a new spawn. every cached row of the old owner is
     * dropped here, so a stale disguise can never attach to the new entity.
     * a same-uuid rebind (respawn, replacement) keeps rows and re-applies
     * them against the new id.
     */
    fun onIdentityRegistered(registration: IdentityRegistration) {
        registration.evicted
            .filter { it.uuid != registration.identity.uuid }
            .forEach { evicted ->
                logger?.fine("entity id ${evicted.entityId} reused: dropping stale state of ${evicted.uuid}")
                states.clearTarget(evicted.uuid)
                appliedGeneration.remove(evicted.uuid)
            }
        // same uuid, new id: viewers must be corrected against the new id
        if (registration.identity.generation > 1L) {
            onTransition(SyncReason.ENTITY_CHANGE, registration.identity.uuid)
        }
    }

    /** the entity behind a tracked identity went away without a registration */
    fun onIdentityDespawned(uuid: UUID) {
        identities.despawn(uuid)
        onTransition(SyncReason.ENTITY_REMOVAL, uuid)
    }

    /** re-apply the correct state to every active viewer of this target */
    private fun reapplyTarget(target: UUID, clearDead: Boolean) {
        val viewers = states.viewersOf(target)
        viewers.forEach { viewer ->
            // a still-dead target keeps its DEAD pin: no re-apply may put a
            // disguise back onto a corpse
            if (!clearDead && states.get(viewer, target) == ViewState.DEAD) {
                resync.restoreVanilla(viewer, target)
                return@forEach
            }
            val applied = resync.resyncTarget(viewer, target)
            if (applied != null) {
                states.set(viewer, target, applied)
            }
        }
        identities.current(target)?.let { appliedGeneration[target] = it.generation }
    }

    /**
     * cross-world teleport of the target: every viewer gets a full per-target
     * viewer-side reset — applied overrides dropped, vanilla resend for that
     * entity — then the correct state is re-applied on top.
     */
    private fun resetTargetForViewers(target: UUID) {
        states.viewersOf(target).forEach { viewer ->
            // dead target teleporting: vanilla death stays, no disguise comes back
            if (states.get(viewer, target) == ViewState.DEAD) {
                resync.restoreVanilla(viewer, target)
                return@forEach
            }
            resync.resetTarget(viewer, target)
            val applied = resync.resyncTarget(viewer, target)
            if (applied != null) {
                states.set(viewer, target, applied)
            }
        }
        identities.current(target)?.let { appliedGeneration[target] = it.generation }
    }

    /**
     * explicit DEAD handling: a dead target must not keep rendering as a
     * disguised zombie. surgical on purpose — HIDDEN pairs keep hiding
     * (that is policy, not disguise), VISIBLE pairs are already vanilla.
     * only DISGUISED pairs are forced back to vanilla death and pinned DEAD;
     * respawn clears the pin and re-applies the real policy.
     */
    private fun markDead(target: UUID) {
        states.viewersOf(target).forEach { viewer ->
            if (states.get(viewer, target) == ViewState.DISGUISED) {
                resync.restoreVanilla(viewer, target)
                states.set(viewer, target, ViewState.DEAD)
            }
        }
    }

    /**
     * scheduler-driven reconciliation pass: find and fix drift the event path
     * could have missed (a dropped event, a racing registration).
     *  - rows whose target re-registered with a newer generation and were not
     *    re-applied since -> re-apply (DEAD rows get a vanilla reassert instead).
     *  - rows whose target identity is no longer the live binding -> the row
     *    points at a dead identity, drop it.
     */
    fun reconcile(): ReconcileReport {
        var reapplied = 0
        var droppedRows = 0

        for (row in states.allRows()) {
            val live = identities.current(row.target)
            when {
                live == null -> {
                    // untracked target: rows are allowed to survive untracking
                    // (quit keeps rows for reconnect), nothing to fix here
                }
                !identities.isLive(live) -> {
                    states.clear(row.viewer, row.target)
                    droppedRows++
                }
                appliedGeneration[row.target] != live.generation -> {
                    if (row.state == ViewState.DEAD) {
                        // still dead, still must not render disguised
                        resync.restoreVanilla(row.viewer, row.target)
                        states.set(row.viewer, row.target, ViewState.DEAD)
                    } else {
                        val applied = resync.resyncTarget(row.viewer, row.target)
                        if (applied != null) states.set(row.viewer, row.target, applied)
                    }
                    appliedGeneration[row.target] = live.generation
                    reapplied++
                }
            }
        }
        return ReconcileReport(reapplied, droppedRows)
    }

    /**
     * plugin disable / reload: restore every modified client view to vanilla
     * truth (disguises removed, hidden entities unhidden), then clear the
     * registry. ordering is deterministic — sorted by viewer, then target —
     * so a reload can never depend on map iteration luck. on the next enable
     * the registry rebuilds from scratch: the pe layer re-applies policy as
     * entities are tracked fresh.
     */
    fun restoreAllForReload(): RestoreReport {
        val rows = states.allRows()
            .sortedWith(compareBy({ it.viewer }, { it.target }))
        rows.forEach { row ->
            resync.restoreVanilla(row.viewer, row.target)
        }
        // policy truth is rebuilt from scratch on the next enable; nothing
        // survives disable, so nothing stale can survive either
        states.allRows().map { it.viewer }.distinct().forEach { states.clearViewer(it) }
        appliedGeneration.clear()
        logger?.info("packet state sync: restored ${rows.size} view(s) to vanilla before disable")
        return RestoreReport(rows)
    }

    companion object {
        /**
         * reload safety / double-load guard. reload tools (plugman, /reload, our
         * own updater restart path) can spin a second plugin instance while the
         * old classloader is still alive. two live sync instances would
         * double-register listeners and double-send packets — the classic way a
         * reload leaves ghost entities on clients. so exactly one instance in the
         * process may hold the claim at a time; it is released on disable, which
         * is what makes a clean enable -> disable -> enable reload cycle work.
         */
        private val CLAIMED = AtomicBoolean(false)

        /**
         * claim-guarded factory. returns null when another live sync instance
         * already holds the double-load claim; the caller must treat that as a
         * hard error and skip listener registration entirely.
         */
        fun create(
            states: StateInvalidator,
            resync: ResyncExecutor,
            identities: EntityIdentityTracker,
            logger: Logger? = null,
        ): PacketStateSync? {
            val sync = PacketStateSync(states, resync, identities, logger)
            if (!sync.tryClaim()) {
                logger?.severe("packet state sync already claimed by another plugin instance; refusing double load")
                return null
            }
            return sync
        }
    }
}
