package kz.bejiihiu.servercore.bars

import java.nio.file.Path
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.ui.Theme
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.io.TempDir

/**
 * the pure half of the renderer: fill math, health colors and the title line.
 * the packets themselves are folia's business.
 */
class BarPresentationTest {

    @TempDir
    lateinit var temp: Path

    private val locale: LocaleService by lazy { LocaleService(temp, Logger.getLogger("bars-presentation-test")) }

    private val titles: BarTitleCache by lazy { BarTitleCache(locale) }

    private fun plain(component: net.kyori.adventure.text.Component): String =
        PlainTextComponentSerializer.plainText().serialize(component)

    @Test
    fun `progress is clamped and a missing reading renders empty`() {
        assertEquals(0.5f, BarPresentation.progress(0.5))
        assertEquals(1f, BarPresentation.progress(4.0))
        assertEquals(0f, BarPresentation.progress(-2.0))
        assertEquals(0f, BarPresentation.progress(null))
        assertEquals(0f, BarPresentation.progress(Double.NaN))
    }

    @Test
    fun `health picks the bossbar colors`() {
        assertEquals(BossBar.Color.GREEN, BarPresentation.bossBarColor(BarHealth.GOOD))
        assertEquals(BossBar.Color.YELLOW, BarPresentation.bossBarColor(BarHealth.MEDIUM))
        assertEquals(BossBar.Color.RED, BarPresentation.bossBarColor(BarHealth.LOW))
        assertEquals(BossBar.Color.WHITE, BarPresentation.bossBarColor(BarHealth.UNKNOWN))
    }

    @Test
    fun `the ram title renders the template and every value`() {
        val reading = BarReading(
            fraction = 0.5,
            health = BarHealth.GOOD,
            values = mapOf("used" to "2.0", "max" to "4.0", "pct" to "50"),
        )

        val line = plain(BarPresentation.line(titles, BarId.RAM, reading, BarSurface.BOSSBAR))

        assertTrue(line.startsWith("◆ "))
        assertTrue(line.contains("RAM"))
        assertTrue(line.contains("2.0"))
        assertTrue(line.contains("4.0"))
        assertTrue(line.contains("50%"))
        assertFalse(line.contains("{"))
    }

    @Test
    fun `the tps title names the region and the server fallback`() {
        val reading = BarReading(
            fraction = 0.995,
            health = BarHealth.GOOD,
            values = mapOf("tps" to "19.9", "region" to "6,-1", "global" to "20.0"),
        )

        val line = plain(BarPresentation.line(titles, BarId.TPS, reading, BarSurface.BOSSBAR))

        assertTrue(line.contains("TPS"))
        assertTrue(line.contains("19.9"))
        assertTrue(line.contains("6,-1"))
        assertTrue(line.contains("20.0"))
        assertFalse(line.contains("{"))
    }

    @Test
    fun `the actionbar mark carries health, the bossbar keeps the brand brass`() {
        fun mark(health: BarHealth, surface: BarSurface) =
            BarPresentation.line(
                titles,
                BarId.RAM,
                BarReading(0.5, health, mapOf("used" to "1.0", "max" to "2.0", "pct" to "50")),
                surface,
            ).color()

        assertEquals(Theme.GOLD, mark(BarHealth.LOW, BarSurface.BOSSBAR))
        assertEquals(Theme.OK, mark(BarHealth.GOOD, BarSurface.ACTIONBAR))
        assertEquals(Theme.WARN, mark(BarHealth.MEDIUM, BarSurface.ACTIONBAR))
        assertEquals(Theme.DANGER, mark(BarHealth.LOW, BarSurface.ACTIONBAR))
    }

    @Test
    fun `an unknown reading still renders a readable line`() {
        val reading = BarReading(
            fraction = null,
            health = BarHealth.UNKNOWN,
            values = mapOf("used" to "—", "max" to "—", "pct" to "—"),
        )

        val line = plain(BarPresentation.line(titles, BarId.RAM, reading, BarSurface.ACTIONBAR))

        assertTrue(line.contains("RAM"))
        assertTrue(line.contains("—"))
    }

    @Test
    fun `the mspt title renders the average and the p95`() {
        val reading = BarReading(
            fraction = 0.25,
            health = BarHealth.GOOD,
            values = mapOf("mspt" to "12.5", "p95" to "18.0"),
        )

        val line = plain(BarPresentation.line(titles, BarId.MSPT, reading, BarSurface.BOSSBAR))

        assertTrue(line.contains("MSPT"))
        assertTrue(line.contains("12.5"))
        assertTrue(line.contains("18.0"))
        assertFalse(line.contains("{"))
    }

    @Test
    fun `the players title renders the occupancy`() {
        val reading = BarReading(
            fraction = 0.12,
            health = BarHealth.UNKNOWN,
            values = mapOf("online" to "12", "max" to "100", "pct" to "12"),
        )

        val line = plain(BarPresentation.line(titles, BarId.PLAYERS, reading, BarSurface.ACTIONBAR))

        assertTrue(line.contains("12"))
        assertTrue(line.contains("100"))
        assertTrue(line.contains("12%"))
        assertFalse(line.contains("{"))
    }

    @Test
    fun `the world title renders the weather, and the plain variant drops it`() {
        val reading = BarReading(
            fraction = 0.25,
            health = BarHealth.UNKNOWN,
            values = mapOf("world" to "world", "time" to "12:00", "cycle" to "25", "weather" to "rain"),
        )

        val withWeather = plain(BarPresentation.line(titles, BarId.WORLD, reading, BarSurface.BOSSBAR))
        assertTrue(withWeather.contains("world"))
        assertTrue(withWeather.contains("12:00"))
        assertTrue(withWeather.contains("25%"))
        assertTrue(withWeather.contains("rain"))
        assertFalse(withWeather.contains("{"))

        val plainLine = plain(
            BarPresentation.line(titles, BarId.WORLD, reading.copy(titleKey = "bars.world.title-plain"), BarSurface.BOSSBAR),
        )
        assertTrue(plainLine.contains("world"))
        assertTrue(plainLine.contains("12:00"))
        assertFalse(plainLine.contains("rain"))
    }
}
