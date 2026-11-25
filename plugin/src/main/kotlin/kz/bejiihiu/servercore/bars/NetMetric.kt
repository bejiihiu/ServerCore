package kz.bejiihiu.servercore.bars

import org.bukkit.entity.Player

/**
 * the whole network at a glance: how many servers answered, how many players
 * are on them, and how many have gone quiet.
 *
 * the data comes from the backend, which already aggregates every game
 * server's heartbeat. this bar is the only one whose source is not this
 * process, so it never touches a world, a player or a region — it renders a
 * cached aggregate and is safe from any thread.
 *
 * three honest states:
 * - fresh aggregate: "3/3 servers · 7 players", stale servers counted apart
 * - no aggregate yet: this server is the whole network, "1/1", local count
 * - aggregate older than the window: servers age out individually, so a
 *   backend that died slowly turns the bar into "0/3 · 3 offline" instead of
 *   freezing a healthy picture
 */
class NetMetric(
    private val source: NetSnapshotSource,
    private val staleAfterMs: Long,
    /** this server's own player count, for the no-aggregate fallback */
    private val local: () -> LocalNetView,
) : BarMetric {

    override val bar: BarId = BarId.NET

    override fun read(player: Player): BarReading = readGlobal()

    /** no player state involved: the ambient sampler feeds placeholderapi too */
    override fun readGlobal(): BarReading =
        readingOf(source.latest(), source.now(), local(), staleAfterMs)

    /**
     * pure half. [now] is the local clock, [snapshot] carries the local receipt
     * time, so every server's age grows with the picture even between pushes.
     */
    internal fun readingOf(
        snapshot: NetSnapshot?,
        now: Long,
        local: LocalNetView,
        staleAfterMs: Long,
    ): BarReading {
        if (snapshot == null || snapshot.servers.isEmpty()) return selfView(local)

        val elapsed = (now - snapshot.receivedAt).coerceAtLeast(0L)
        var up = 0
        for (server in snapshot.servers) {
            if (server.lastSeenAgeMs + elapsed <= staleAfterMs) up++
        }
        val total = snapshot.servers.size
        val stale = total - up
        return BarReading(
            fraction = up.toDouble() / total,
            health = when {
                stale == 0 -> BarHealth.GOOD
                up > 0 -> BarHealth.MEDIUM
                else -> BarHealth.LOW
            },
            values = mapOf(
                "servers" to "$up/$total",
                "players" to snapshot.playersOnline.toString(),
                "stale" to stale.toString(),
            ),
            titleKey = if (stale > 0) STALE_TITLE else null,
        )
    }

    /** no aggregate: one server, and it is this one */
    internal fun selfView(local: LocalNetView): BarReading = BarReading(
        fraction = 1.0,
        health = BarHealth.GOOD,
        values = mapOf(
            "servers" to "1/1",
            "players" to local.onlinePlayers.coerceAtLeast(0).toString(),
            "stale" to "0",
        ),
    )

    internal fun unknownReading(): BarReading = BarReading(
        fraction = null,
        health = BarHealth.UNKNOWN,
        values = mapOf(
            "servers" to BarFormats.UNKNOWN,
            "players" to BarFormats.UNKNOWN,
            "stale" to BarFormats.UNKNOWN,
        ),
    )

    private companion object {
        /** the variant that adds "· N offline" to the same line */
        const val STALE_TITLE = "bars.net.title-stale"
    }
}
