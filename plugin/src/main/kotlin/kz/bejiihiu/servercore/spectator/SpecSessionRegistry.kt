package kz.bejiihiu.servercore.spectator

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * local in-memory source of truth for active observation pairs. thread-safe
 * on purpose: packet listeners (netty threads), entity schedulers (region
 * threads) and command handlers all touch it concurrently.
 *
 * the /spec command module drives [register] and [unregister] through here
 * once it lands; until then the registry works standalone so the hiding layer
 * can be wired and exercised without a session owner.
 */
class SpecSessionRegistry : SpecSessionSource {

    /** observing admin -> the uuid it is currently watching */
    private val pairs = ConcurrentHashMap<UUID, UUID>()

    override fun isObserving(admin: UUID, target: UUID): Boolean = pairs[admin] == target

    /** every admin currently watching [target] */
    fun observersOf(target: UUID): Set<UUID> =
        pairs.entries.asSequence().filter { it.value == target }.map { it.key }.toSet()

    /** the player [admin] is watching, null when the admin runs no session */
    fun observedTarget(admin: UUID): UUID? = pairs[admin]

    /** true while [uuid] is the observing side of any session */
    fun isObserver(uuid: UUID): Boolean = pairs.containsKey(uuid)

    /** true while [uuid] is being watched by anyone */
    fun isObserved(uuid: UUID): Boolean = pairs.containsValue(uuid)

    /**
     * open a pair. an admin re-specing someone else replaces their old pair:
     * one admin, at most one active observation.
     */
    fun register(admin: UUID, target: UUID) {
        if (admin == target) return
        pairs[admin] = target
    }

    /** close exactly this pair; no-op when the admin is watching someone else */
    fun unregister(admin: UUID, target: UUID) {
        if (pairs[admin] == target) pairs.remove(admin, target)
    }

    /** close everything [admin] owns (their disconnect, shutdown) */
    fun unregisterAdmin(admin: UUID) {
        pairs.remove(admin)
    }

    /**
     * disconnect cleanup for one player: they leave as an admin (their pair
     * dies) and as a target (nobody is watching them anymore). the caller
     * decides whether restore packets are still worth sending — usually they
     * are not, nothing restores into a leaving client.
     */
    fun detach(uuid: UUID) {
        pairs.remove(uuid)
        pairs.entries.removeIf { it.value == uuid }
    }

    fun clear() = pairs.clear()

    /** snapshot of every active pair, for deterministic restore passes */
    fun allPairs(): Set<Pair<UUID, UUID>> = pairs.entries.map { it.key to it.value }.toSet()
}
