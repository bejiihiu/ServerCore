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
        assertEquals(HealthThresholds(40.0, 50.0, lowerIsBetter = true), config.mspt.thresholds)
        assertTrue(config.world.showWeather)
        assertTrue(config.net.enabled)
        assertEquals(BarsConfig.DEFAULT_HEARTBEAT_SECONDS * 1000L * BarsConfig.STALE_HEARTBEATS, config.net.staleAfterMs)
        assertEquals(
            listOf(BarId.RAM, BarId.TPS, BarId.MSPT, BarId.PLAYERS, BarId.WORLD, BarId.NET),
            config.enabledBars(),
        )
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
        assertFalse(onlyTps.isEnabled(BarId.RAM))
        assertTrue(onlyTps.isEnabled(BarId.TPS))
        assertEquals(
            listOf(BarId.TPS, BarId.MSPT, BarId.PLAYERS, BarId.WORLD, BarId.NET),
            onlyTps.enabledBars(),
        )

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

    @Test
    fun `mspt thresholds are clamped into the millisecond range and ordered`() {
        val config = load("bars:\n  mspt:\n    good: 90\n    medium: 70\n")
        assertEquals(HealthThresholds(70.0, 90.0, lowerIsBetter = true), config.mspt.thresholds)

        val clamped = load("bars:\n  mspt:\n    good: -5\n    medium: 5000\n")
        assertEquals(0.0, clamped.mspt.thresholds.good)
        assertEquals(1_000.0, clamped.mspt.thresholds.medium)
    }

    @Test
    fun `world weather is on by default and can be switched off without touching the bar`() {
        assertTrue(load("").world.showWeather)

        val plain = load("bars:\n  world:\n    show-weather: false\n")
        assertFalse(plain.world.showWeather)
        assertTrue(plain.world.enabled)
    }

    @Test
    fun `players and world have their own switches`() {
        val config = load("bars:\n  players:\n    enabled: false\n  world:\n    enabled: false\n")

        assertFalse(config.isEnabled(BarId.PLAYERS))
        assertFalse(config.isEnabled(BarId.WORLD))
        assertTrue(config.isEnabled(BarId.MSPT))
    }

    @Test
    fun `the network bar can be switched off without touching the rest`() {
        val config = load("bars:\n  net:\n    enabled: false\n")

        assertFalse(config.isEnabled(BarId.NET))
        assertTrue(config.isEnabled(BarId.RAM))
        assertFalse(BarId.NET in config.enabledBars())
    }

    @Test
    fun `the stale window follows the heartbeat cadence unless it is set`() {
        // three heartbeats: a server is offline after three missed frames
        val fastHeartbeat = load("backend:\n  heartbeat-interval-seconds: 5\n")
        assertEquals(15_000L, fastHeartbeat.net.staleAfterMs)

        val explicit = load("bars:\n  net:\n    stale-after-ms: 90000\n")
        assertEquals(90_000L, explicit.net.staleAfterMs)
    }

    @Test
    fun `the stale window is clamped into something usable`() {
        // below one heartbeat every server would flicker offline
        assertEquals(BarsConfig.MIN_STALE_AFTER_MS, load("bars:\n  net:\n    stale-after-ms: 100\n").net.staleAfterMs)
        assertEquals(BarsConfig.MAX_STALE_AFTER_MS, load("bars:\n  net:\n    stale-after-ms: 99999999\n").net.staleAfterMs)
    }
}
