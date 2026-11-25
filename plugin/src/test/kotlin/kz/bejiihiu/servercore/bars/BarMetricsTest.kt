package kz.bejiihiu.servercore.bars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * the sampled numbers and their health bands. everything here is the pure
 * half of the metrics: jmx and the server are only the feed.
 */
class BarMetricsTest {

    private val ramThresholds = HealthThresholds(60.0, 80.0, lowerIsBetter = true)
    private val tpsThresholds = HealthThresholds(18.0, 15.0, lowerIsBetter = false)

    private val gib = 1024L * 1024L * 1024L

    @Test
    fun `heap fill renders used max and percent with a fill fraction`() {
        val reading = RamMetric(ramThresholds).readingOf(MemorySnapshot(used = 2 * gib, max = 4 * gib))

        assertEquals(0.5, reading.fraction)
        assertEquals(BarHealth.GOOD, reading.health)
        assertEquals("2.0", reading.values["used"])
        assertEquals("4.0", reading.values["max"])
        assertEquals("50", reading.values["pct"])
    }

    @Test
    fun `heap health bands sit on the configured edges`() {
        val metric = RamMetric(ramThresholds)
        fun healthAtPercent(pct: Int): BarHealth =
            metric.readingOf(MemorySnapshot(used = pct.toLong(), max = 100L)).health

        assertEquals(BarHealth.GOOD, healthAtPercent(60))
        assertEquals(BarHealth.MEDIUM, healthAtPercent(61))
        assertEquals(BarHealth.MEDIUM, healthAtPercent(80))
        assertEquals(BarHealth.LOW, healthAtPercent(81))
    }

    @Test
    fun `a missing or broken heap reading is unknown, not a crash`() {
        val metric = RamMetric(ramThresholds)

        val missing = metric.readingOf(null)
        assertNull(missing.fraction)
        assertEquals(BarHealth.UNKNOWN, missing.health)
        assertEquals(BarFormats.UNKNOWN, missing.values["used"])
        assertEquals(BarFormats.UNKNOWN, missing.values["pct"])

        val broken = metric.readingOf(MemorySnapshot(used = 10, max = 0))
        assertNull(broken.fraction)
        assertEquals(BarHealth.UNKNOWN, broken.health)
    }

    @Test
    fun `region tps carries the chunk pair and the global comparison`() {
        val reading = TpsMetric(tpsThresholds).readingOf(
            regionTps = 19.9,
            globalTps = 20.0,
            chunkX = 100 shr 4,
            chunkZ = -5 shr 4,
        )

        assertEquals(19.9, reading.fraction!! * 20.0, absoluteTolerance = 0.0001)
        assertEquals(BarHealth.GOOD, reading.health)
        assertEquals("19.9", reading.values["tps"])
        assertEquals("6,-1", reading.values["region"])
        assertEquals("20.0", reading.values["global"])
    }

    @Test
    fun `a missing region falls back to the global tps`() {
        val reading = TpsMetric(tpsThresholds).readingOf(
            regionTps = null,
            globalTps = 16.0,
            chunkX = 0,
            chunkZ = 0,
        )

        assertEquals(BarHealth.MEDIUM, reading.health)
        assertEquals("16.0", reading.values["tps"])
        assertEquals(BarFormats.UNKNOWN, reading.values["region"])
        assertEquals("16.0", reading.values["global"])
    }

    @Test
    fun `both readings missing leaves the bar empty instead of faking a number`() {
        val reading = TpsMetric(tpsThresholds).readingOf(null, Double.NaN, chunkX = 1, chunkZ = 2)

        assertNull(reading.fraction)
        assertEquals(BarHealth.UNKNOWN, reading.health)
        assertEquals(BarFormats.UNKNOWN, reading.values["tps"])
        assertEquals(BarFormats.UNKNOWN, reading.values["global"])
    }

    @Test
    fun `tps health bands sit on the configured edges`() {
        val metric = TpsMetric(tpsThresholds)
        fun healthAtTps(tps: Double): BarHealth = metric.readingOf(tps, tps, 0, 0).health

        assertEquals(BarHealth.GOOD, healthAtTps(18.0))
        assertEquals(BarHealth.MEDIUM, healthAtTps(17.9))
        assertEquals(BarHealth.MEDIUM, healthAtTps(15.0))
        assertEquals(BarHealth.LOW, healthAtTps(14.9))
    }

    @Test
    fun `formats are locale independent`() {
        assertEquals("1.5", BarFormats.gigabytes(3 * gib / 2))
        assertEquals("50", BarFormats.percent(0.5))
        assertEquals("19.9", BarFormats.tps(19.94))
        // clamped, so a nonsensical fraction can never paint beyond the bar
        assertEquals("100", BarFormats.percent(4.0))
        assertEquals("0", BarFormats.percent(-1.0))
    }
}
