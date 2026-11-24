package kz.bejiihiu.servercore.spectator

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * the observation pairs the packet layer works against, as a pure view.
 *
 * it holds no lifecycle authority: the session coordinator is the only owner
 * of "who is watching whom" (conflicts, phases, restores) and drives the
 * hiding bridge on every settled transition; the bridge is the one writer of
 * this view. everything else - packet hiding, resync, esp, trace - only reads.
 *
 * thread-safe on purpose: packet listeners (netty threads), entity schedulers
 * (region threads) and command handlers all touch it concurrently.
 */
class ObserverPairRegistry : SpecSessionSource {

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

    /** snapshot of every active pair, for deterministic restore passes */
    fun allPairs(): Set<Pair<UUID, UUID>> = pairs.entries.map { it.key to it.value }.toSet()
}
