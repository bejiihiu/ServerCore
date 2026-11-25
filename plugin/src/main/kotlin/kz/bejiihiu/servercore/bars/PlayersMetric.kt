package kz.bejiihiu.servercore.bars

import org.bukkit.entity.Player

/**
 * online players vs the slot limit.
 *
 * both numbers are plain server counters: getOnlinePlayers() returns a copy and
 * maxPlayers is an int, so any thread may read them. no bukkit state, no
 * region, no sampler.
 *
 * the bar has no health semantics on purpose: a full server is not "bad" and
 * an empty one is not "good", so the fill only carries the occupancy and the
 * mark stays neutral.
 */
class PlayersMetric : BarMetric {

    override val bar: BarId = BarId.PLAYERS

    override fun read(player: Player): BarReading =
        readingOf(player.server.onlinePlayers.size, player.server.maxPlayers)

    /** pure half, so the copy and the math are testable without a server */
    internal fun readingOf(online: Int, max: Int): BarReading {
        val slots = max.coerceAtLeast(0)
        val players = online.coerceAtLeast(0)
        // a lowered slot limit can leave the real count above max; show the
        // real number and keep the fill inside the bar
        val fraction = if (slots > 0) (players.toDouble() / slots).coerceIn(0.0, 1.0) else null
        return BarReading(
            fraction = fraction,
            health = BarHealth.UNKNOWN,
            values = mapOf(
                "online" to players.toString(),
                "max" to slots.toString(),
                "pct" to (fraction?.let(BarFormats::percent) ?: BarFormats.UNKNOWN),
            ),
        )
    }
}
