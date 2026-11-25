package kz.bejiihiu.servercore.bars

import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.configuration.file.YamlConfiguration

class BarsConfigTest {

    private val logger = Logger.getLogger("bars-config-test")

    private fun load(yaml: String): BarsConfig =
        BarsConfig.load(YamlConfiguration().also { it.loadFromString(yaml) }, logger)

    @Test
    fun `a missing section keeps the shipped defaults`() {
        val config = load("")

        assertTrue(config.enabled)
        assertEquals(BarsConfig.DEFAULT_INTERVAL_TICKS, config.updateIntervalTicks)
        assertEquals(BarSurface.BOSSBAR, config.surface)
        assertEquals(HealthThresholds(60.0, 80.0, lowerIsBetter = true), config.ram.thresholds)
        assertEquals(HealthThresholds(18.0, 15.0, lowerIsBetter = false), config.tps.thresholds)
        assertEquals(listOf(BarId.RAM, BarId.TPS), config.enabledBars())
    }

    @Test
    fun `display parses both surfaces and unknown names fall back`() {
        assertEquals(BarSurface.ACTIONBAR, load("bars:\n  display: actionbar\n").surface)
        assertEquals(BarSurface.BOSSBAR, load("bars:\n  display: Bossbar\n").surface)
        assertEquals(BarSurface.BOSSBAR, load("bars:\n  display: scoreboard\n").surface)
    }

    @Test
    fun `the refresh interval is clamped to something sane`() {
        assertEquals(1L, load("bars:\n  update-interval-ticks: 0\n").updateIntervalTicks)
        assertEquals(200L, load("bars:\n  update-interval-ticks: 10_000\n").updateIntervalTicks)
    }

    @Test
    fun `swapped thresholds are put back in order`() {
        val config = load(
            """
            bars:
              ram:
                good: 90
                medium: 70
              tps:
                good: 10
                medium: 19
            """.trimIndent(),
        )

        assertEquals(HealthThresholds(70.0, 90.0, lowerIsBetter = true), config.ram.thresholds)
        assertEquals(HealthThresholds(19.0, 10.0, lowerIsBetter = false), config.tps.thresholds)
    }

    @Test
    fun `per bar switches and the master switch gate the command list`() {
        val onlyTps = load("bars:\n  ram:\n    enabled: false\n")
        assertEquals(listOf(BarId.TPS), onlyTps.enabledBars())
        assertFalse(onlyTps.isEnabled(BarId.RAM))

        val masterOff = load("bars:\n  enabled: false\n")
        assertTrue(masterOff.enabledBars().isEmpty())
        assertFalse(masterOff.isEnabled(BarId.TPS))
    }

    @Test
    fun `thresholds are clamped into the percentage range`() {
        val config = load("bars:\n  ram:\n    good: -5\n    medium: 250\n")

        assertEquals(1.0, config.ram.thresholds.good)
        assertEquals(99.0, config.ram.thresholds.medium)
    }
}
