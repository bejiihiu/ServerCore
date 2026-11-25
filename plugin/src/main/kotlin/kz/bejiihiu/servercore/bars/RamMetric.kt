package kz.bejiihiu.servercore.bars

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * heap fill. jmx answers from any thread, but it is not free: one async
 * sampler writes a volatile snapshot per interval and every player's bar task
 * merely reads it. that keeps region threads off the management beans.
 */
class RamMetric(private val thresholds: HealthThresholds) : BarMetric {

    override val bar: BarId = BarId.RAM

    @Volatile
    private var snapshot: MemorySnapshot? = null

    private var sampler: ScheduledTask? = null

    override fun start(plugin: Plugin, periodTicks: Long) {
        // the async scheduler speaks real time, not ticks; one game tick is 50ms
        val periodMs = periodTicks.coerceAtLeast(1L) * TICK_MILLIS
        sample()
        sampler = plugin.server.asyncScheduler.runAtFixedRate(
            plugin,
            { sample() },
            0L,
            periodMs,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun stop() {
        sampler?.cancel()
        sampler = null
    }

    override fun read(player: Player): BarReading = readingOf(snapshot)

    /** one jmx read; called from the async sampler only */
    private fun sample() {
        val usage = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        // -1 means no fixed ceiling; runtime knows the real xmx
        val max = if (usage.max > 0) usage.max else Runtime.getRuntime().maxMemory()
        snapshot = MemorySnapshot(used = usage.used, max = max)
    }

    /** pure half, so formatting and thresholds are testable without a server */
    internal fun readingOf(memory: MemorySnapshot?): BarReading {
        if (memory == null || memory.max <= 0) {
            return BarReading(
                fraction = null,
                health = BarHealth.UNKNOWN,
                values = mapOf("used" to BarFormats.UNKNOWN, "max" to BarFormats.UNKNOWN, "pct" to BarFormats.UNKNOWN),
            )
        }
        val fraction = (memory.used.toDouble() / memory.max).coerceIn(0.0, 1.0)
        return BarReading(
            fraction = fraction,
            health = thresholds.health(fraction * 100),
            values = mapOf(
                "used" to BarFormats.gigabytes(memory.used),
                "max" to BarFormats.gigabytes(memory.max),
                "pct" to BarFormats.percent(fraction),
            ),
        )
    }

    private companion object {
        const val TICK_MILLIS = 50L
    }
}

/** what the sampler saw; max is xmx, used is current heap */
internal data class MemorySnapshot(val used: Long, val max: Long)
