package kz.bejiihiu.servercore.bars

import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * the tps of the region the player is standing in — the folia-native reading.
 *
 * folia has no server-wide tps, so a bar that says "tps" without saying
 * "where" is lying. the region value is read on the player's own entity
 * scheduler (the task that refreshes the bar runs there), the chunk pair goes
 * into the title, and the global aggregate rides along as the fallback and as
 * the comparison line.
 */
class TpsMetric(private val thresholds: HealthThresholds) : BarMetric {

    override val bar: BarId = BarId.TPS

    @Volatile
    private var server: Server? = null

    override fun start(plugin: Plugin, periodTicks: Long) {
        // the ambient sampler has no player and therefore no region; it still
        // publishes the server-wide aggregate so placeholderapi never shows a
        // dash just because nobody has the bar toggled
        server = plugin.server
    }

    override fun stop() {
        server = null
    }

    override fun read(player: Player): BarReading {
        val location = player.location
        // getRegionTPS returns null when no region owns the spot (unloaded or
        // split); the api is thread-safe, but we are already on the owner thread
        val region = player.server.getRegionTPS(location)
        val global = player.server.tps
        return readingOf(
            regionTps = region?.getOrNull(REGION_WINDOW),
            globalTps = global.getOrNull(GLOBAL_WINDOW),
            chunkX = location.blockX shr CHUNK_SHIFT,
            chunkZ = location.blockZ shr CHUNK_SHIFT,
        )
    }

    /**
     * server-wide aggregate only: folia has no global region tps to read by
     * location, and paper's array is exactly the aggregate this bar already
     * shows as the comparison line
     */
    override fun readGlobal(): BarReading? {
        val globalTps = server?.tps?.getOrNull(GLOBAL_WINDOW) ?: return null
        return readingOf(regionTps = null, globalTps = globalTps, chunkX = 0, chunkZ = 0)
    }

    /** pure half: null region, nan and zero readings all fall back or go unknown */
    internal fun readingOf(regionTps: Double?, globalTps: Double?, chunkX: Int, chunkZ: Int): BarReading {
        val regionValue = regionTps?.takeIf { it.isFinite() && it > 0.0 }
        val globalValue = globalTps?.takeIf { it.isFinite() && it > 0.0 }
        val value = regionValue ?: globalValue
        val health = value?.let(thresholds::health) ?: BarHealth.UNKNOWN
        val fraction = value?.let { (it / MAX_TPS).coerceIn(0.0, 1.0) }
        val values = mapOf(
            "tps" to (value?.let(BarFormats::tps) ?: BarFormats.UNKNOWN),
            "region" to (regionValue?.let { "$chunkX,$chunkZ" } ?: BarFormats.UNKNOWN),
            "global" to (globalValue?.let(BarFormats::tps) ?: BarFormats.UNKNOWN),
        )
        return BarReading(fraction = fraction, health = health, values = values)
    }

    private companion object {
        // folia's region array is 5s, 15s, 1m, 5m, 15m — 1m is the calm window
        const val REGION_WINDOW = 2

        // paper's global array is 1m, 5m, 15m
        const val GLOBAL_WINDOW = 0

        const val CHUNK_SHIFT = 4
        const val MAX_TPS = 20.0
    }
}
