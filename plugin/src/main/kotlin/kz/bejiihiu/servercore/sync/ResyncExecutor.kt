package kz.bejiihiu.servercore.sync

import java.util.UUID

/**
 * seam for the packet writer side. the packetevents writer implements this:
 * every method here translates into concrete packet work (destroy-entity,
 * spawn/metadata/equipment resend, or "do nothing and let vanilla resend").
 *
 * the state-sync module only ever talks to the writer through this interface,
 * so the writer can land later without touching any transition logic.
 *
 * threading: implementations are responsible for hopping to whatever thread
 * their packet backend requires (folia region/entity scheduler or the
 * packetevents worker). the sync module calls from region threads and does
 * not assume the writer is already on the right one.
 */
interface ResyncExecutor {
    /**
     * viewer transition: drop every applied per-target override on this
     * viewer's client and let vanilla resend. registry rows are untouched —
     * they are the policy truth that gets re-applied on re-tracking.
     */
    fun resetViewer(viewer: UUID)

    /**
     * viewer transition: re-send the viewer's full applied view from the
     * registry rows, without dropping anything. used when the client is fresh
     * (reconnect) or its view needs a non-destructive reassert.
     */
    fun resyncViewer(viewer: UUID)

    /**
     * drop the applied overrides for one (viewer, target) pair and let vanilla
     * resend that entity. used for cross-world teleports of the target.
     */
    fun resetTarget(viewer: UUID, target: UUID)

    /**
     * re-apply the correct state for one pair, from the registry.
     * returns the state that was actually applied so the caller can keep the
     * cached row in sync with reality; null when the writer applied nothing.
     */
    fun resyncTarget(viewer: UUID, target: UUID): ViewState?

    /**
     * remove any disguise and unhide: the pair goes back to vanilla truth.
     * used for explicit DEAD handling and for the full reload restore pass.
     */
    fun restoreVanilla(viewer: UUID, target: UUID)
}
