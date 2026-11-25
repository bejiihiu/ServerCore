package kz.bejiihiu.servercore.bars

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * milliseconds per tick of the region the player is standing in.
 *
 * there is no per-location mspt api on folia (the pr for one is still open),
 * but there is a region-scoped one hiding in plain sight: CraftServer
 * overrides getAverageTickTime() to report the *current region's* 5s tick
 * report and throws "Not on any region" anywhere else. our reads always run
 * on the player's entity scheduler, which is exactly a region thread, so the
 * call is legal and returns the same timePerTickData average the region tps
 * array is built from. no reflection, no nms.
 *
 * getTickTimes() is the other neighbour worth ruling out: folia does not
 * override it, so it keeps paper's server-wide array and cannot answer "how
 * long did this region's tick take". getRegionTPS() answers the same tick
 * report but only as tps, which is clamped at 20 and therefore hides every
 * healthy-region tick time. both are why the scoped getAverageTickTime wins.
 *
 * the number is the region's tick report, not the whole server: folia has no
 * global tick, and a bar that pretends otherwise is lying.
 *
 * p95 is honest about what it is: folia only hands out pre-averaged windows,
 * so p95 is taken over the last [WINDOW] one-second observations of that 5s
 * average. it says "the region's 5s average was at least x in 5% of the last
 * minute", not "5% of individual ticks took x". that is as close as the api
 * lets a plugin get without touching nms internals.
 *
 * the fallback matters for the day getAverageTickTime stops being region
 * scoped (or an implementation never grows one): we then measure the gap
 * between consecutive runs of this bar's own task and divide by its period.
 * that gap is paced to the 50ms tick budget, so it equals 50ms on a healthy
 * region and only grows when the region misses ticks. it is a pacing/overrun
 * signal, not cpu time, and it is deliberately only the fallback.
 */
class MsptMetric(
    private val thresholds: HealthThresholds,
    private val intervalTicks: Long,
) : BarMetric {

    override val bar: BarId = BarId.MSPT

    private val trackers = ConcurrentHashMap<UUID, Tracker>()

    @Volatile
    private var plugin: Plugin? = null

    private val warnedAboutFallback = AtomicBoolean(false)

    override fun start(plugin: Plugin, periodTicks: Long) {
        this.plugin = plugin
    }

    override fun stop() {
        trackers.clear()
    }

    override fun forget(uuid: UUID) {
        trackers.remove(uuid)
    }

    override fun read(player: Player): BarReading {
        val tracker = trackers.computeIfAbsent(player.uniqueId) { Tracker() }
        val paced = tracker.samplePacing(System.nanoTime(), intervalTicks)

        val reported = regionTickTimeMillis(player)
        if (reported != null) {
            tracker.recordReported(reported)
            val stats = tracker.stats()
            if (stats != null) return readingOf(stats.average, stats.p95)
        }

        // report unavailable or still empty (a freshly created region has no
        // 5s window yet): fall back to pacing instead of showing anything made up
        if (paced != null) {
            if (warnedAboutFallback.compareAndSet(false, true)) {
                plugin?.logger?.warning(
                    "mspt bar: folia's region tick report is unavailable here, " +
                        "falling back to tick pacing (50 ms floor, overrun only)",
                )
            }
            return pacedReadingOf(paced)
        }
        return unknownReading()
    }

    /**
     * the region-scoped tick report. folia throws from a non-region thread and
     * the value can be zero before the first window fills; both mean "no
     * reading" rather than a crash.
     */
    private fun regionTickTimeMillis(player: Player): Double? = try {
        player.server.averageTickTime.takeIf { it.isFinite() && it > 0.0 }
    } catch (e: RuntimeException) {
        null
    }

    /** pure half: average and p95 come straight from the sampled window */
    internal fun readingOf(averageMs: Double, p95Ms: Double?): BarReading {
        val value = averageMs.takeIf { it.isFinite() && it >= 0.0 } ?: return unknownReading()
        return BarReading(
            fraction = (value / TICK_BUDGET_MILLIS).coerceIn(0.0, 1.0),
            health = thresholds.health(value),
            values = mapOf(
                "mspt" to BarFormats.millis(value),
                "p95" to (p95Ms?.let(BarFormats::millis) ?: BarFormats.UNKNOWN),
            ),
        )
    }

    /**
     * pacing fallback. its bands are not the configured mspt thresholds: a
     * pacing reading of 50ms means the region hit its budget, not that a tick
     * burned 50ms of cpu, so the good band is the budget itself.
     */
    internal fun pacedReadingOf(pacedMs: Double): BarReading {
        val value = pacedMs.coerceAtLeast(0.0)
        return BarReading(
            fraction = (value / TICK_BUDGET_MILLIS).coerceIn(0.0, 1.0),
            health = when {
                value <= TICK_BUDGET_MILLIS + PACING_SLACK_MILLIS -> BarHealth.GOOD
                value <= TICK_BUDGET_MILLIS * PACING_MEDIUM_RATIO -> BarHealth.MEDIUM
                else -> BarHealth.LOW
            },
            values = mapOf(
                "mspt" to BarFormats.millis(value),
                "p95" to BarFormats.UNKNOWN,
            ),
        )
    }

    internal fun unknownReading(): BarReading = BarReading(
        fraction = null,
        health = BarHealth.UNKNOWN,
        values = mapOf("mspt" to BarFormats.UNKNOWN, "p95" to BarFormats.UNKNOWN),
    )

    /**
     * per-player window. folia reads a player's bar task on one thread, but a
     * quit can forget the entry from the event thread, so the state is guarded.
     */
    private class Tracker {

        private val window = DoubleArray(WINDOW)
        private var size = 0
        private var cursor = 0
        private var lastNanos = 0L

        @Synchronized
        fun recordReported(value: Double) {
            window[cursor] = value
            cursor = (cursor + 1) % WINDOW
            if (size < WINDOW) size++
        }

        @Synchronized
        fun stats(): Stats? {
            if (size == 0) return null
            val values = DoubleArray(size)
            for (i in 0 until size) values[i] = window[i]
            return Stats(average = values.average(), p95 = percentile95(values))
        }

        /**
         * mspt implied by the wall clock gap since the previous read. reads
         * are scheduled every [intervalTicks] region ticks, so a gap close to
         * intervalTicks * 50ms means the region kept its pace. wild gaps (a
         * just-toggled bar, a server stall) are dropped instead of poisoning
         * the fallback.
         */
        @Synchronized
        fun samplePacing(nowNanos: Long, intervalTicks: Long): Double? {
            val previous = lastNanos
            lastNanos = nowNanos
            if (previous == 0L || intervalTicks <= 0L) return null
            val expected = intervalTicks * NANOS_PER_TICK
            val elapsed = nowNanos - previous
            if (elapsed < expected / 2 || elapsed > expected * MAX_PACING_FACTOR) return null
            return elapsed.toDouble() / intervalTicks / NANOS_PER_MILLI
        }
    }

    internal data class Stats(val average: Double, val p95: Double?)

    private companion object {
        /** one minute of one-second reads */
        const val WINDOW = 60

        /** 20 tps budget; a tps drop starts exactly here */
        const val TICK_BUDGET_MILLIS = 50.0

        /** paced readings within half a ms of the budget still mean "kept up" */
        const val PACING_SLACK_MILLIS = 0.5

        /** 20% over budget is the middle band; beyond that the region is losing ticks */
        const val PACING_MEDIUM_RATIO = 1.2

        /** 4x the expected gap is a stall, not a sample */
        const val MAX_PACING_FACTOR = 4L

        const val NANOS_PER_TICK = 50_000_000L
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}

/** nearest-rank p95 over the sampled window */
internal fun percentile95(values: DoubleArray): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sortedArray()
    val index = ((sorted.size - 1) * 0.95).toInt()
    return sorted[index]
}
