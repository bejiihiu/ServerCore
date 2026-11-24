package kz.bejiihiu.servercore.packet

import java.util.UUID

/**
 * storage for per-viewer entity view state: (viewer, target) -> state.
 *
 * the interface is deliberately storage-agnostic so the packet layer can be
 * unit-tested without packetevents and swap backends later. all methods must
 * be safe to call from any thread — packet callbacks never run on a single
 * dedicated thread.
 */
interface ViewStateStore {
    fun get(viewer: UUID, target: UUID): EntityViewState?

    fun set(viewer: UUID, target: UUID, state: EntityViewState)

    fun remove(viewer: UUID, target: UUID): EntityViewState?

    /** drop every pair pointing at this target, for all viewers */
    fun removeTarget(target: UUID)

    /** drop everything this viewer knows, on disconnect/world change */
    fun removeViewer(viewer: UUID)

    fun clear()

    /** number of stored (viewer, target) pairs, for monitoring */
    fun size(): Int
}
