package kz.bejiihiu.servercore.bars

import java.nio.file.Path
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kz.bejiihiu.servercore.locale.LocaleService
import org.junit.jupiter.api.io.TempDir

/**
 * the world bar's pure half: clock, cycle, weather words and the two templates.
 * the live world read is a one-liner on the player's own thread and is not
 * reproducible here.
 */
class WorldMetricTest {

    @TempDir
    lateinit var temp: Path

    private val locale: LocaleService by lazy { LocaleService(temp, Logger.getLogger("world-metric-test")) }

    private fun metric(showWeather: Boolean = true) = WorldMetric(locale, showWeather)

    @Test
    fun `the world bar carries the name, clock, cycle and weather`() {
        val reading = metric().readingOf("world", time = 6_000, storm = false, thundering = false)

        assertEquals("world", reading.values["world"])
        assertEquals("12:00", reading.values["time"])
        assertEquals("25", reading.values["cycle"])
        assertEquals(locale.text("bars.world.weather-clear"), reading.values["weather"])
        assertEquals(0.25, reading.fraction!!, absoluteTolerance = 0.0001)
        assertEquals(BarHealth.UNKNOWN, reading.health)
        assertNull(reading.titleKey)
    }

    @Test
    fun `thunder wins over rain, rain over clear`() {
        val metric = metric()

        assertEquals(
            locale.text("bars.world.weather-thunder"),
            metric.readingOf("world", 0, storm = true, thundering = true).values["weather"],
        )
        assertEquals(
            locale.text("bars.world.weather-rain"),
            metric.readingOf("world", 0, storm = true, thundering = false).values["weather"],
        )
        assertEquals(
            locale.text("bars.world.weather-clear"),
            metric.readingOf("world", 0, storm = false, thundering = false).values["weather"],
        )
    }

    @Test
    fun `plain mode switches to the weatherless template`() {
        val reading = metric(showWeather = false)
            .readingOf("world_nether", time = 12_000, storm = true, thundering = true)

        assertEquals("bars.world.title-plain", reading.titleKey)
        // the word still exists in the map, the plain template just never uses it
        assertTrue(reading.values["weather"]!!.isNotEmpty())
    }

    @Test
    fun `the fill follows the day and wraps instead of going negative`() {
        val metric = metric()

        assertEquals(0.0, metric.readingOf("world", 0, false, false).fraction!!, absoluteTolerance = 0.0001)
        assertEquals(0.5, metric.readingOf("world", 12_000, false, false).fraction!!, absoluteTolerance = 0.0001)
        assertEquals(0.5, metric.readingOf("world", -12_000, false, false).fraction!!, absoluteTolerance = 0.0001)
    }
}
