package kz.bejiihiu.servercore.sync

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * identity of one entity as the packet layer knows it: uuid + entity id +
 * generation counter. the client only ever sees entity ids, and the server
 * reuses ids of despawned entities for new spawns — without generation
 * tracking a stale disguise can silently attach to a completely unrelated
 * new entity. never allowed.
 */
data class EntityIdentity(
    val uuid: UUID,
    val entityId: Int,
    val generation: Long,
)

/** result of [EntityIdentityTracker.register]: the new binding plus everything it made stale */
data class IdentityRegistration(
    val identity: EntityIdentity,
    /** bindings killed by this registration: an id stolen from another entity,
     * and/or this uuid's previous binding when it re-registered with a new id */
    val evicted: List<EntityIdentity>,
)

/**
 * thread-safe tracker of live (uuid, entity id) bindings with generation
 * counters. registration is idempotent for an unchanged pair and bumps the
 * generation whenever a binding changes.
 */
class EntityIdentityTracker {

    private val byUuid = ConcurrentHashMap<UUID, EntityIdentity>()
    private val byId = ConcurrentHashMap<Int, UUID>()

    /**
     * bind a uuid to an entity id. handles both reuse directions:
     * - a different entity claiming an id that used to belong to someone else
     *   (server-side id reuse after despawn) — the old owner's binding is
     *   evicted so no cached disguise can survive for it;
     * - the same uuid coming back with a new entity id (player respawn,
     *   entity replacement) — the old id binding is evicted for that uuid.
     */
    fun register(uuid: UUID, entityId: Int): IdentityRegistration {
        // unchanged binding: idempotent, no generation bump, nothing evicted
        val existing = byUuid[uuid]
        if (existing != null && existing.entityId == entityId) {
            return IdentityRegistration(existing, emptyList())
        }

        val evicted = mutableListOf<EntityIdentity>()

        val previousOwner = byId[entityId]
        if (previousOwner != null && previousOwner != uuid) {
            byId.remove(entityId, previousOwner)
            byUuid.remove(previousOwner)?.let { evicted.add(it) }
        }

        val previousSelf = byUuid[uuid]
        if (previousSelf != null && previousSelf.entityId != entityId) {
            byId.remove(previousSelf.entityId, uuid)
            // dedupe: if the id steal above already evicted this same binding,
            // don't report it twice
            if (evicted.none { it.uuid == uuid }) evicted.add(previousSelf)
        }

        val generation = (previousSelf?.generation ?: 0L) + 1L
        val identity = EntityIdentity(uuid, entityId, generation)
        byUuid[uuid] = identity
        byId[entityId] = uuid
        return IdentityRegistration(identity, evicted)
    }

    /** entity went away: forget its binding. the id becomes reusable again */
    fun despawn(uuid: UUID) {
        val removed = byUuid.remove(uuid) ?: return
        byId.remove(removed.entityId, uuid)
    }

    /** live binding for a uuid, only if it still matches the given entity id */
    fun resolve(uuid: UUID, entityId: Int): EntityIdentity? {
        val current = byUuid[uuid] ?: return null
        return if (current.entityId == entityId) current else null
    }

    /** is this previously handed-out identity still the live binding? */
    fun isLive(cached: EntityIdentity): Boolean =
        byUuid[cached.uuid] == cached && byId[cached.entityId] == cached.uuid

    /** current binding for a uuid, regardless of entity id */
    fun current(uuid: UUID): EntityIdentity? = byUuid[uuid]

    /** tracked entity count, for the reconciliation pass and diagnostics */
    fun trackedCount(): Int = byUuid.size
}
