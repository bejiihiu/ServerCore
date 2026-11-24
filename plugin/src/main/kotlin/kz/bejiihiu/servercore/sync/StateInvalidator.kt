package kz.bejiihiu.servercore.sync

import java.util.UUID

/**
 * seam for the cached (viewer, target) view state. the full packet registry
 * from the pe-core side implements this interface; this module codes against
 * it exclusively so the state-sync logic never depends on packet internals.
 *
 * threading: all implementations must be safe to call from any folia region
 * thread — transitions fire on region threads and never hop.
 */
interface StateInvalidator {
    /** store (or overwrite) the cached view state of one pair */
    fun set(viewer: UUID, target: UUID, state: ViewState)

    /** current cached state of one pair, null when nothing is cached */
    fun get(viewer: UUID, target: UUID): ViewState?

    /** drop one pair's cached row */
    fun clear(viewer: UUID, target: UUID)

    /** invalidate everything this player sees as a viewer (their applied overrides) */
    fun clearViewer(viewer: UUID)

    /** invalidate everything cached about this player as a target (every viewer's row) */
    fun clearTarget(target: UUID)

    /** targets this viewer currently has cached state for */
    fun targetsOf(viewer: UUID): Set<UUID>

    /** viewers that currently have cached state about this target */
    fun viewersOf(target: UUID): Set<UUID>

    /** every cached row; used by the reload restore pass and reconciliation */
    fun allRows(): Collection<ViewStateRow>

    /** cheap size probe */
    fun rowCount(): Int
}
