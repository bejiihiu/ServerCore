package kz.bejiihiu.servercore.disguise

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class DisguiseStatus { DISGUISED, HIDDEN }

data class DisguisePairKey(val viewer: UUID, val target: UUID)

/**
 * one client-only entity parked on a (viewer, target) pair.
 *
 * realEntityId is the server-side player entity id the viewer must NOT see;
 * syntheticEntityId is 0 until a disguise actually spawned (a hidden pair has
 * none). fields are volatile: written on the target's entity scheduler,
 * read from netty threads in the packet listener.
 */
class ActiveDisguise(
    val viewer: UUID,
    val target: UUID,
    @Volatile var syntheticEntityId: Int,
    @Volatile var mobTypeName: String,
    @Volatile var realEntityId: Int,
    @Volatile var status: DisguiseStatus,
)

/**
 * thread-safe per-pair disguise bookkeeping, shared by the service and the
 * packet listener. keyed by uuid pair; entity-id lookups are a linear scan
 * over the pairs of one viewer, which stays tiny (only nearby targets).
 */
class DisguiseRegistry {

    private val pairs = ConcurrentHashMap<DisguisePairKey, ActiveDisguise>()

    fun get(viewer: UUID, target: UUID): ActiveDisguise? = pairs[DisguisePairKey(viewer, target)]

    fun put(disguise: ActiveDisguise) {
        pairs[DisguisePairKey(disguise.viewer, disguise.target)] = disguise
    }

    fun remove(viewer: UUID, target: UUID): ActiveDisguise? = pairs.remove(DisguisePairKey(viewer, target))

    fun findByRealEntityId(viewer: UUID, realEntityId: Int): ActiveDisguise? =
        pairs.values.firstOrNull { it.viewer == viewer && it.realEntityId == realEntityId }

    /** every record where this uuid is the viewer, dropped from the map */
    fun removeForViewer(viewer: UUID): List<ActiveDisguise> = removeMatching { it.viewer == viewer }

    /** every record where this uuid is the target, dropped from the map */
    fun removeForTarget(target: UUID): List<ActiveDisguise> = removeMatching { it.target == target }

    fun all(): Collection<ActiveDisguise> = pairs.values

    fun clear() = pairs.clear()

    private inline fun removeMatching(crossinline predicate: (ActiveDisguise) -> Boolean): List<ActiveDisguise> {
        val removed = ArrayList<ActiveDisguise>()
        pairs.entries.removeIf { entry ->
            predicate(entry.value).also { matched -> if (matched) removed.add(entry.value) }
        }
        return removed
    }
}

/**
 * synthetic client entity ids. the server allocates real ids from 1 upward,
 * so a monotonic counter coming DOWN from the top of the int range cannot
 * collide with a live server entity, nor with itself.
 */
class SyntheticEntityIds(start: Int = Int.MAX_VALUE) {

    private val counter = AtomicInteger(start)

    fun next(): Int = counter.getAndDecrement()
}
