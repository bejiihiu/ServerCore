package kz.bejiihiu.servercore.packet

import java.util.UUID

/**
 * change notifications for a [ViewStateStore]. the ws sync layer registers a
 * sink here to fan local view-state changes out to other agents of this server.
 */
interface ViewStateSink {
    fun onStateSet(viewer: UUID, target: UUID, state: EntityViewState)
    fun onStateRemoved(viewer: UUID, target: UUID)
    fun onViewerRemoved(viewer: UUID)
    fun onTargetRemoved(target: UUID)
}

/**
 * ws-synced view state variant.
 *
 * important: the backend never sends view state, and it never will — view
 * state is an opinion of the local game server about its own clients, so
 * reads are always local and instant. the only network-facing part is the
 * write side: every local change is offered to [sink] so the sync module can
 * replicate it if it ever wants to. with no sink attached this behaves
 * exactly like its backing store.
 */
class WsSyncedViewStateStore(
    private val backing: ViewStateStore,
    @Volatile private var sink: ViewStateSink? = null,
) : ViewStateStore {

    override fun get(viewer: UUID, target: UUID): EntityViewState? = backing.get(viewer, target)

    override fun set(viewer: UUID, target: UUID, state: EntityViewState) {
        backing.set(viewer, target, state)
        sink?.onStateSet(viewer, target, state)
    }

    override fun remove(viewer: UUID, target: UUID): EntityViewState? {
        val removed = backing.remove(viewer, target)
        if (removed != null) sink?.onStateRemoved(viewer, target)
        return removed
    }

    override fun removeTarget(target: UUID) {
        backing.removeTarget(target)
        sink?.onTargetRemoved(target)
    }

    override fun removeViewer(viewer: UUID) {
        backing.removeViewer(viewer)
        sink?.onViewerRemoved(viewer)
    }

    override fun clear() {
        // bulk clear says nothing about individual pairs; no per-pair events,
        // the sync layer gets its own explicit wipe signal instead
        backing.clear()
        sink?.onViewerRemoved(ALL_VIEWERS)
    }

    override fun size(): Int = backing.size()

    companion object {
        /** wildcard used by clear(), only meaningful to sink implementations */
        val ALL_VIEWERS = UUID(0L, 0L)
    }
}
