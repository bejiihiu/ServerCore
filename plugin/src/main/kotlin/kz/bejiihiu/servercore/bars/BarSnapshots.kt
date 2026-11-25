package kz.bejiihiu.servercore.bars

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * one formatted bar value, addressed by bar and (optionally) player. this is
 * the only seam between the bar loop and the placeholderapi expansion: the
 * expansion asks for a number, gets a string that was already rendered, and
 * never triggers a metric read on its own thread.
 */
fun interface BarValueSource {
    /** null = nothing sampled yet, the caller decides what to show */
    fun value(bar: BarId, uuid: UUID?): String?
}

/**
 * the last reading of every bar, published by the refresh loop and by the
 * ambient sampler.
 *
 * two slots, on purpose:
 * - global: readings that mean the same thing for everyone (heap, online
 *   count, the network view, the server-wide tps aggregate). published by the
 *   ambient sampler, so they are fresh with zero bars toggled.
 * - per player: readings that depend on where the player stands (region tps,
 *   region mspt, their world). published only by that player's own refresh
 *   task, because that task is the one running on their region thread.
 *
 * a player-scoped value never leaks into the global slot: a scoreboard entry
 * that silently showed someone else's region would be worse than a dash.
 */
class BarSnapshots : BarValueSource {

    private val global = ConcurrentHashMap<BarId, BarReading>()
    private val perPlayer = ConcurrentHashMap<UUID, MutableMap<BarId, BarReading>>()

    /** [uuid] null publishes a global reading, otherwise a player-scoped one */
    fun publish(bar: BarId, reading: BarReading, uuid: UUID? = null) {
        if (uuid == null) {
            global[bar] = reading
            return
        }
        perPlayer.computeIfAbsent(uuid) { ConcurrentHashMap() }[bar] = reading
    }

    /** the player's own reading first, the global one as the fallback */
    override fun value(bar: BarId, uuid: UUID?): String? {
        val reading = uuid?.let { perPlayer[it]?.get(bar) } ?: global[bar] ?: return null
        val raw = reading.values[valueKey(bar)] ?: return null
        return raw.takeIf { it != BarFormats.UNKNOWN }
    }

    /** drops a quitting player's readings; their task is gone with them */
    fun forget(uuid: UUID) {
        perPlayer.remove(uuid)
    }

    /** which template value carries the number a scoreboard wants to see */
    private fun valueKey(bar: BarId): String = when (bar) {
        BarId.RAM -> "pct"
        BarId.TPS -> "tps"
        BarId.MSPT -> "mspt"
        BarId.PLAYERS -> "online"
        BarId.WORLD -> "time"
        BarId.NET -> "servers"
    }
}
