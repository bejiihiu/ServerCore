package kz.bejiihiu.servercore.packet

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * the default in-memory store. bounded two ways:
 * - at most [maxViewers] viewers keep state (idlest viewer's whole map is evicted)
 * - at most [maxTargetsPerViewer] targets per viewer (stalest pair is evicted)
 *
 * there is deliberately NO time-based expiry: the store holds the *applied*
 * client picture, which cannot be recomputed from packets. an expired HIDDEN
 * row would stop suppressing spawns and data and flash the player back
 * visible - fail-open. rows live until an explicit removal (viewer disconnect,
 * world change, target removal, destroy bookkeeping); the two capacity caps
 * are the only eviction pressure. the clock is injectable so tests don't sleep.
 */
class CachedViewStateStore(
    private val maxViewers: Int,
    private val maxTargetsPerViewer: Int,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewStateStore {

    private class Entry(
        @Volatile var state: EntityViewState,
        @Volatile var lastAccess: Long,
    )

    private val viewers = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Entry>>()
    private val viewerAccess = ConcurrentHashMap<UUID, Long>()

    override fun get(viewer: UUID, target: UUID): EntityViewState? {
        val map = viewers[viewer] ?: return null
        touchViewer(viewer)
        val entry = map[target] ?: return null
        entry.lastAccess = now()
        return entry.state
    }

    override fun set(viewer: UUID, target: UUID, state: EntityViewState) {
        val map = viewers.computeIfAbsent(viewer) { ConcurrentHashMap() }
        touchViewer(viewer)
        map[target] = Entry(state, now())
        evictTargets(map)
        evictViewers()
    }

    override fun remove(viewer: UUID, target: UUID): EntityViewState? {
        val map = viewers[viewer] ?: return null
        return map.remove(target)?.state
    }

    override fun removeTarget(target: UUID) {
        viewers.forEachValue(1L) { map -> map.remove(target) }
    }

    override fun removeViewer(viewer: UUID) {
        viewers.remove(viewer)
        viewerAccess.remove(viewer)
    }

    override fun clear() {
        viewers.clear()
        viewerAccess.clear()
    }

    override fun size(): Int = viewers.values.sumOf { it.size }

    private fun touchViewer(viewer: UUID) {
        viewerAccess[viewer] = now()
    }

    private fun evictTargets(map: ConcurrentHashMap<UUID, Entry>) {
        var overflow = map.size - maxTargetsPerViewer
        while (overflow > 0) {
            val stalest = map.entries.minByOrNull { it.value.lastAccess } ?: break
            map.remove(stalest.key)
            overflow--
        }
    }

    private fun evictViewers() {
        var overflow = viewers.size - maxViewers
        if (overflow <= 0) return
        while (overflow > 0) {
            val stalest = viewerAccess.entries.minByOrNull { it.value } ?: break
            viewers.remove(stalest.key)
            viewerAccess.remove(stalest.key)
            overflow--
        }
    }
}
