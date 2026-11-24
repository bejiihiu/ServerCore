package kz.bejiihiu.servercore.spec

import java.util.UUID

/**
 * what a viewer should currently see of a target entity. HIDDEN means "the
 * viewer must not perceive the target at all" - no entity, no tab entry, no
 * effect traces. the packet layer (view state applier) owns the real per-viewer
 * implementation, bukkit visibility is the base backend here.
 */
enum class VisibilityState { HIDDEN, VISIBLE }

/**
 * per-viewer visibility seam, shared shape with the packet layer's view state
 * applier: apply(viewer, target, state). viewer is the player whose perception
 * changes, target is the entity being hidden from or shown to them.
 *
 * implementations must be callable from any thread and schedule entity work
 * themselves; a missing player (quit, dimension change) is a no-op, never an
 * error - sessions routinely outlive one of the two parties.
 */
fun interface HiddenObserver {
    fun apply(viewer: UUID, target: UUID, state: VisibilityState)
}
