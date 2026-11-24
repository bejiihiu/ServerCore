package kz.bejiihiu.servercore.packet

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * per-viewer state index. two coordinates live here:
 * - (viewer, target) -> [EntityViewState], delegated to a [ViewStateStore]
 * - (viewer, entityId) -> target uuid, so packets that only carry entity ids
 *   (metadata, equipment, destroy, movement) can be attributed to a target
 *
 * entity ids are connection-scoped, which is exactly why the id map hangs off
 * the viewer. everything is thread-safe: packet callbacks race with scheduler
 * tasks by design.
 */
class PacketStateRegistry(private val store: ViewStateStore) {

    private val entityIds = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, UUID>>()

    /** current state of the pair, null when the viewer was never told about the target */
    fun state(viewer: UUID, target: UUID): EntityViewState? = store.get(viewer, target)

    fun set(viewer: UUID, target: UUID, state: EntityViewState) {
        store.set(viewer, target, state)
    }

    /** target whose client-side entity id this is, null when untracked */
    fun targetOf(viewer: UUID, entityId: Int): UUID? = entityIds[viewer]?.get(entityId)

    /** bind a freshly spawned entity id to its owner for this viewer */
    fun bindEntityId(viewer: UUID, entityId: Int, target: UUID) {
        entityIds.computeIfAbsent(viewer) { ConcurrentHashMap() }[entityId] = target
    }

    /**
     * after a destroy packet: forget the ids and, once a target has no more
     * bound ids for this viewer, forget its state too. returns the targets
     * that lost their last binding (the ones the viewer genuinely stopped
     * knowing about).
     */
    fun unbindEntityIds(viewer: UUID, ids: IntArray): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        val map = entityIds[viewer] ?: return emptySet()
        val freed = mutableSetOf<UUID>()
        for (id in ids) {
            map.remove(id)?.let { freed.add(it) }
        }
        // keep state for targets the client still knows under another id
        val dropped = freed.filterNot { target -> hasBinding(map, target) }
        dropped.forEach { store.remove(viewer, it) }
        return dropped.toSet()
    }

    /**
     * concurrent-safe membership scan: a snapshot (values.toSet()) races with
     * concurrent unbinders and can throw mid-iteration, a sequential
     * forEachValue never does
     */
    private fun hasBinding(map: ConcurrentHashMap<Int, UUID>, target: UUID): Boolean {
        var found = false
        map.forEachValue(1L) { if (it == target) found = true }
        return found
    }

    /** forget one viewer completely: disconnect, world change, respawn */
    fun removeViewer(viewer: UUID) {
        entityIds.remove(viewer)
        store.removeViewer(viewer)
    }

    /** the target entity is gone server-side: clean up every viewer's picture of it */
    fun removeTarget(target: UUID) {
        // sequential on purpose: parallel threshold on a live map is overkill
        // for player-sized data and only invites races
        entityIds.forEachValue(1L) { map ->
            map.entries.removeIf { it.value == target }
        }
        store.removeTarget(target)
    }

    fun clear() {
        entityIds.clear()
        store.clear()
    }

    /** live pair count across viewers, for monitoring */
    fun size(): Int = store.size()
}
