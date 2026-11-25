package kz.bejiihiu.servercore.bars

import java.util.UUID

/**
 * who has which bar on which surface. pure state, no bukkit, no io — the
 * service turns changes into render tasks, the store persists snapshots.
 *
 * the surface is part of the key: a player may hold one bar on the bossbar
 * and, when a later phase adds actionbar-only bars, a different one on the
 * actionbar. enabling a bar displaces whatever occupied the same surface.
 *
 * every mutator is atomic, because commands land on arbitrary region threads
 * while the player's own task is reading the answer.
 */
class BarsState(initial: Map<UUID, Map<BarSurface, BarId>> = emptyMap()) {

    private val lock = Any()
    private val players = LinkedHashMap<UUID, MutableMap<BarSurface, BarId>>()

    init {
        for ((uuid, bySurface) in initial) {
            if (bySurface.isNotEmpty()) players[uuid] = LinkedHashMap(bySurface)
        }
    }

    fun active(uuid: UUID, surface: BarSurface): BarId? = synchronized(lock) { players[uuid]?.get(surface) }

    /** turns the bar on for the surface; false when it was already the active one */
    fun enable(uuid: UUID, bar: BarId, surface: BarSurface): Boolean {
        synchronized(lock) {
            val bySurface = players.getOrPut(uuid) { LinkedHashMap() }
            if (bySurface[surface] == bar) return false
            bySurface[surface] = bar
            return true
        }
    }

    /** turns the bar off, but only when it is the one on that surface; false otherwise */
    fun disable(uuid: UUID, bar: BarId, surface: BarSurface): Boolean {
        synchronized(lock) {
            val bySurface = players[uuid] ?: return false
            if (bySurface[surface] != bar) return false
            bySurface.remove(surface)
            if (bySurface.isEmpty()) players.remove(uuid)
            return true
        }
    }

    /** flips the bar; returns the new state */
    fun toggle(uuid: UUID, bar: BarId, surface: BarSurface): Boolean {
        synchronized(lock) {
            return if (players[uuid]?.get(surface) == bar) {
                disable(uuid, bar, surface)
                false
            } else {
                enable(uuid, bar, surface)
                true
            }
        }
    }

    /**
     * group toggle for /bar all: on unless every player already has this bar,
     * in which case the group goes off. returns the new state.
     */
    fun toggleAll(uuids: Collection<UUID>, bar: BarId, surface: BarSurface): Boolean {
        synchronized(lock) {
            if (uuids.isEmpty()) return false
            val enabled = uuids.any { players[it]?.get(surface) != bar }
            for (uuid in uuids) {
                if (enabled) {
                    players.getOrPut(uuid) { LinkedHashMap() }[surface] = bar
                } else {
                    val bySurface = players[uuid] ?: continue
                    if (bySurface[surface] == bar) bySurface.remove(surface)
                    if (bySurface.isEmpty()) players.remove(uuid)
                }
            }
            return enabled
        }
    }

    fun clear(uuid: UUID) {
        synchronized(lock) { players.remove(uuid) }
    }

    /** json-friendly view: uuid -> surface name -> bar key */
    fun snapshot(): Map<String, Map<String, String>> {
        synchronized(lock) {
            return players.entries.associate { (uuid, bySurface) ->
                uuid.toString() to bySurface.entries.associate { (surface, bar) -> surface.key to bar.key }
            }
        }
    }
}
