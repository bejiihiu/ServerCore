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
 *
 * reads happen for every entity packet of every viewer, so the hot path is
 * two map reads and two plain long stores - no boxing, no second map. both
 * capacity scans compare primitive longs, which keeps an eviction on a full
 * viewer in the low microseconds instead of a boxed comparator walk.
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

    /** one viewer's rows plus its own recency stamp, so reads never touch a second map */
    private class ViewerState {
        val targets = ConcurrentHashMap<UUID, Entry>()

        @Volatile
        var lastAccess: Long = 0
    }

    private val viewers = ConcurrentHashMap<UUID, ViewerState>()

    override fun get(viewer: UUID, target: UUID): EntityViewState? {
        val holder = viewers[viewer] ?: return null
        // one clock read for both stamps: a miss still keeps the viewer warm
        // (it was touched by a packet), exactly like the pair row used to
        val stamp = now()
        holder.lastAccess = stamp
        val entry = holder.targets[target] ?: return null
        entry.lastAccess = stamp
        return entry.state
    }

    override fun set(viewer: UUID, target: UUID, state: EntityViewState) {
        val stamp = now()
        val holder = viewers.computeIfAbsent(viewer) { ViewerState() }
        holder.lastAccess = stamp
        val existing = holder.targets[target]
        if (existing != null) {
            // re-applying a state is the common packet case; mutating the row
            // keeps it out of the allocator without changing what readers see
            existing.state = state
            existing.lastAccess = stamp
        } else {
            holder.targets[target] = Entry(state, stamp)
        }
        evictTargets(holder)
        evictViewers()
    }

    override fun remove(viewer: UUID, target: UUID): EntityViewState? {
        val holder = viewers[viewer] ?: return null
        return holder.targets.remove(target)?.state
    }

    override fun removeTarget(target: UUID) {
        viewers.forEachValue(1L) { holder -> holder.targets.remove(target) }
    }

    override fun removeViewer(viewer: UUID) {
        viewers.remove(viewer)
    }

    override fun clear() {
        viewers.clear()
    }

    override fun size(): Int = viewers.values.sumOf { it.targets.size }

    private fun evictTargets(holder: ViewerState) {
        var overflow = holder.targets.size - maxTargetsPerViewer
        while (overflow > 0) {
            if (!evictStalestTarget(holder.targets)) break
            overflow--
        }
    }

    private fun evictStalestTarget(targets: ConcurrentHashMap<UUID, Entry>): Boolean {
        var stalestKey: UUID? = null
        var stalestAt = Long.MAX_VALUE
        for ((key, entry) in targets) {
            val accessed = entry.lastAccess
            // strict compare keeps the first entry in iteration order on ties,
            // the same pick the previous minBy scan made
            if (accessed < stalestAt) {
                stalestAt = accessed
                stalestKey = key
            }
        }
        val key = stalestKey ?: return false
        return targets.remove(key) != null
    }

    private fun evictViewers() {
        var overflow = viewers.size - maxViewers
        while (overflow > 0) {
            var stalestKey: UUID? = null
            var stalestAt = Long.MAX_VALUE
            for ((key, holder) in viewers) {
                val accessed = holder.lastAccess
                if (accessed < stalestAt) {
                    stalestAt = accessed
                    stalestKey = key
                }
            }
            val key = stalestKey ?: return
            viewers.remove(key)
            overflow--
        }
    }
}
