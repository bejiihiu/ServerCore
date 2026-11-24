package kz.bejiihiu.servercore.esp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * chunk-coarse uniform grid over tracked players. buckets are keyed by world +
 * chunk coords, candidate pairs come from buckets only - never all-pairs.
 *
 * rebuilt incrementally: [update] is called on every player snapshot and
 * no-ops when the cell did not change, so a standing player costs one map put.
 *
 * why scheduler tick and not packet-level move events: a per-movement-packet
 * listener fires for every flying packet of every player (up to 20/s each) and
 * would need a packetevents listener on the hot path of the whole server. the
 * snapshot tick below runs once per check-interval per player on the player's
 * own entity scheduler, does no packet parsing, and the grid math itself is a
 * couple of map operations. at a 10-tick interval the positional error is at
 * most one interval of movement, which the pair TTL smooths over anyway.
 */
class SpatialGrid {

    data class Cell(val world: String, val cx: Int, val cz: Int)

    private val buckets = ConcurrentHashMap<Cell, MutableSet<UUID>>()
    private val locationOf = ConcurrentHashMap<UUID, Cell>()

    /** move [id] to [cell], cheap no-op when the player didn't change chunks */
    fun update(id: UUID, cell: Cell) {
        val old = locationOf.put(id, cell)
        if (old == cell) return
        if (old != null) removeFromBucket(old, id)
        addToBucket(cell, id)
    }

    fun remove(id: UUID) {
        val old = locationOf.remove(id) ?: return
        removeFromBucket(old, id)
    }

    /** uuids in the square of cells around [origin], excluding [exclude] */
    fun nearby(origin: Cell, chunkRadius: Int, exclude: UUID): List<UUID> {
        val out = ArrayList<UUID>()
        for (dx in -chunkRadius..chunkRadius) {
            for (dz in -chunkRadius..chunkRadius) {
                val bucket = buckets[Cell(origin.world, origin.cx + dx, origin.cz + dz)] ?: continue
                for (id in bucket) if (id != exclude) out += id
            }
        }
        return out
    }

    fun cellOf(id: UUID): Cell? = locationOf[id]

    fun tracked(): Int = locationOf.size

    fun clear() {
        buckets.clear()
        locationOf.clear()
    }

    // compute() keeps bucket mutation and empty-bucket eviction atomic, so a
    // concurrent add can never strand a uuid inside an evicted set
    private fun addToBucket(cell: Cell, id: UUID) {
        buckets.compute(cell) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).also { it += id }
        }
    }

    private fun removeFromBucket(cell: Cell, id: UUID) {
        buckets.computeIfPresent(cell) { _, existing ->
            existing -= id
            if (existing.isEmpty()) null else existing
        }
    }
}
