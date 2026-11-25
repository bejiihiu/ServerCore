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
 *
 * a pair also carries an opaque [token] - the session id, in practice. the
 * target uuid alone cannot tell "the same session" from "a new session on the
 * same target": a stale teardown landing after a re-spec to the same player
 * would otherwise look current and close the live session.
 */
class ObserverPairRegistry : SpecSessionSource {

    private data class Entry(val target: UUID, val token: Any?)

    /** observing admin -> the pair (target + session token) it is watching */
    private val pairs = ConcurrentHashMap<UUID, Entry>()

    override fun isObserving(admin: UUID, target: UUID): Boolean = pairs[admin]?.target == target

    /** every admin currently watching [target] */
    fun observersOf(target: UUID): Set<UUID> =
        pairs.entries.asSequence().filter { it.value.target == target }.map { it.key }.toSet()

    /** the player [admin] is watching, null when the admin runs no session */
    fun observedTarget(admin: UUID): UUID? = pairs[admin]?.target

    /** true while [uuid] is the observing side of any session */
    fun isObserver(uuid: UUID): Boolean = pairs.containsKey(uuid)

    /** true while [uuid] is being watched by anyone */
    fun isObserved(uuid: UUID): Boolean = pairs.values.any { it.target == uuid }

    /**
     * true when the registered pair is exactly (admin, target) and, when a
     * token is given, was opened by that same session. a null token means
     * "any session", which keeps the callers that predate tokens working.
     */
    fun owns(admin: UUID, target: UUID, token: Any?): Boolean =
        pairs[admin]?.let { pair -> pair.target == target && (token == null || pair.token == token) } ?: false

    /**
     * open a pair. an admin re-specing someone else replaces their old pair:
     * one admin, at most one active observation.
     */
    fun register(admin: UUID, target: UUID, token: Any? = null) {
        if (admin == target) return
        pairs[admin] = Entry(target, token)
    }

    /**
     * close exactly this pair; no-op when the admin is watching someone else,
     * or when [token] is given and a newer session already owns the pair.
     */
    fun unregister(admin: UUID, target: UUID, token: Any? = null) {
        pairs.computeIfPresent(admin) { _, pair ->
            when {
                pair.target != target -> pair
                token != null && pair.token != token -> pair
                else -> null
            }
        }
    }

    /** snapshot of every active pair, for deterministic restore passes */
    fun allPairs(): Set<Pair<UUID, UUID>> = pairs.entries.map { it.key to it.value.target }.toSet()
}

