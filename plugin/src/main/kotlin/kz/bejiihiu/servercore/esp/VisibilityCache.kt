package kz.bejiihiu.servercore.esp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** one ordered (viewer, target) pair, both directions are separate entries */
data class PairKey(val viewer: UUID, val target: UUID)

/**
 * per-pair visibility cache with a tick TTL. this is the load-bearing piece of
 * the performance contract: a pair verdict is computed at most once per TTL
 * window, and the engine refreshes a bounded number of stale pairs per cycle
 * (round-robin cursor), so the cost never becomes all-pairs-per-tick.
 *
 * time is counted in engine cycles, not raw ticks - the engine converts the
 * configured tick TTL into cycles up front.
 */
class VisibilityCache(private val ttlCycles: Long) {

    data class Entry(
        val verdict: PairVerdict,
        /** engine cycle of the settle that produced this verdict */
        val updatedAt: Long,
        /** last computed line-of-sight result, null until the first trace landed */
        val losBlocked: Boolean?,
        /** a raytrace is in flight, don't bill another one for this pair */
        val losPending: Boolean,
    )

    private val entries = ConcurrentHashMap<PairKey, Entry>()

    operator fun get(key: PairKey): Entry? = entries[key]

    /** atomic entry swap; [update] may run more than once on a losing race */
    fun upsert(key: PairKey, update: (Entry?) -> Entry) {
        entries.compute(key) { _, current -> update(current) }
    }

    /** fresh = inside the TTL window, i.e. no refresh owed yet */
    fun fresh(entry: Entry, now: Long): Boolean = now - entry.updatedAt < ttlCycles

    /** drop every entry the player takes part in, viewer or target side */
    fun removePlayer(uuid: UUID) {
        entries.keys.removeIf { it.viewer == uuid || it.target == uuid }
    }

    fun clear() = entries.clear()

    fun size(): Int = entries.size
}
