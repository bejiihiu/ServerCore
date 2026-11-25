package kz.bejiihiu.servercore.bars

import java.nio.file.Path
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kz.bejiihiu.servercore.locale.LocalePushResult
import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.ui.Theme
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.io.TempDir

/**
 * the cached title path: same line as the parse-per-render one, one parse per
 * template, and a rebuild when a locale push replaces the copy.
 */
class BarTitleCacheTest {

    @TempDir
    lateinit var temp: Path

    private val locale: LocaleService by lazy { LocaleService(temp, Logger.getLogger("bar-title-cache-test")) }

    private fun plain(component: Component): String =
        PlainTextComponentSerializer.plainText().serialize(component)

    // legacy flattens inherited colors into explicit codes, so two trees with a
    // different shape but the same rendering serialize the same
    private fun legacy(component: Component): String = LEGACY.serialize(component)

    /** the pre-cache pipeline: substitute, then parse the whole line every time */
    private fun uncached(key: String, values: Map<String, String>): Component =
        Theme.render(locale.template(key) ?: key, values)

    @Test
    fun `every shipped bar title renders exactly like the uncached pipeline`() {
        val cache = BarTitleCache(locale)
        val samples = mapOf(
            "bars.ram.title" to mapOf("used" to "3.4", "max" to "8.0", "pct" to "42"),
            "bars.tps.title" to mapOf("tps" to "19.9", "region" to "6,-1", "global" to "20.0"),
            "bars.mspt.title" to mapOf("mspt" to "12.5", "p95" to "18.0"),
            "bars.players.title" to mapOf("online" to "12", "max" to "100", "pct" to "12"),
            "bars.world.title" to
                mapOf("world" to "world", "time" to "12:00", "cycle" to "25", "weather" to "rain"),
            "bars.world.title-plain" to mapOf("world" to "world", "time" to "12:00"),
            "bars.net.title" to mapOf("servers" to "3", "players" to "7"),
            "bars.net.title-stale" to mapOf("servers" to "2", "players" to "7", "stale" to "14"),
        )

        for ((key, values) in samples) {
            val expected = uncached(key, values)
            val actual = cache.component(key, values)
            assertEquals(plain(expected), plain(actual), "plain text of $key")
            assertEquals(legacy(expected), legacy(actual), "styling of $key")
        }
    }

    @Test
    fun `the same template is parsed once and reused for every value set`() {
        var parses = 0
        val cache = BarTitleCache(locale, parser = { parses++; Theme.parse(it) })

        val first = cache.component("bars.ram.title", mapOf("used" to "1.0", "max" to "8.0", "pct" to "12"))
        assertTrue(plain(first).contains("1.0"))
        repeat(99) { i ->
            val line = cache.component("bars.ram.title", mapOf("used" to "$i.0", "max" to "8.0", "pct" to "$i"))
            assertTrue(plain(line).contains("$i.0"))
        }
        assertEquals(1, parses)

        // a second title is its own parse; the first one stays warm
        cache.component("bars.tps.title", mapOf("tps" to "19.9", "region" to "6,-1", "global" to "20.0"))
        assertEquals(2, parses)
        assertEquals(
            plain(first),
            plain(cache.component("bars.ram.title", mapOf("used" to "1.0", "max" to "8.0", "pct" to "12"))),
        )
    }

    @Test
    fun `a locale push rebuilds a cached title`() {
        var parses = 0
        val cache = BarTitleCache(locale, parser = { parses++; Theme.parse(it) })

        assertTrue(plain(cache.component("bars.ram.title", RAM_VALUES)).contains("RAM"))
        assertEquals(1, parses)

        applyPush(
            """
            bars:
              ram:
                title: "<primary>MEM</primary> <secondary>{used} GB</secondary>"
            """,
        )
        assertTrue(plain(cache.component("bars.ram.title", RAM_VALUES)).contains("MEM"))
        assertFalse(plain(cache.component("bars.ram.title", RAM_VALUES)).contains("RAM"))
        assertEquals(2, parses)

        // the rebuilt entry is reused, not re-parsed per line
        repeat(10) { cache.component("bars.ram.title", RAM_VALUES) }
        assertEquals(2, parses)
    }

    @Test
    fun `values carrying markup stay literal`() {
        applyPush(
            """
            bars:
              ram:
                title: "<primary>{label}</primary> <secondary>{used}</secondary>"
            """,
        )
        val cache = BarTitleCache(locale)
        val values = mapOf("label" to "<gold>RAM</gold>", "used" to "$5 \\ 3")

        val line = cache.component("bars.ram.title", values)

        assertEquals("<gold>RAM</gold> $5 \\ 3", plain(line))
    }

    @Test
    fun `digits and adjacent tokens around a placeholder survive the cache`() {
        applyPush(
            """
            bars:
              ram:
                title: "<primary>{a}{b}</primary> {a}0 {b}5%"
            """,
        )
        val cache = BarTitleCache(locale)
        val values = mapOf("a" to "12", "b" to "34")

        assertEquals("1234 120 345%", plain(cache.component("bars.ram.title", values)))
        // and the same text the plain path produces, digits included
        assertEquals(plain(uncached("bars.ram.title", values)), plain(cache.component("bars.ram.title", values)))
    }

    @Test
    fun `a missing value keeps its token and an unknown key renders as the key`() {
        val cache = BarTitleCache(locale)

        val partial = plain(cache.component("bars.ram.title", mapOf("used" to "1.0")))
        assertTrue(partial.contains("1.0"))
        assertTrue(partial.contains("{max}"))
        assertTrue(partial.contains("{pct}"))

        assertEquals("bars.no-such-key", plain(cache.component("bars.no-such-key")))
    }

    @Test
    fun `a template with no placeholders keeps the cached tree`() {
        applyPush(
            """
            bars:
              ram:
                title: "<primary>static</primary>"
            """,
        )
        var parses = 0
        val cache = BarTitleCache(locale, parser = { parses++; Theme.parse(it) })

        repeat(20) { assertEquals("static", plain(cache.component("bars.ram.title", RAM_VALUES))) }
        assertEquals(1, parses)
    }

    @Test
    fun `the cached line carries the same characters as the plain path`() {
        // Theme.plain is the stripping path the ui uses for width math: a second
        // reader of the same template, so the values have to land identically
        val cache = BarTitleCache(locale)
        val samples = mapOf(
            "bars.ram.title" to RAM_VALUES,
            "bars.players.title" to mapOf("online" to "1", "max" to "2000", "pct" to "0"),
            "bars.mspt.title" to mapOf("mspt" to "0.1", "p95" to "123.4"),
        )
        for ((key, values) in samples) {
            val template = locale.template(key)!!
            assertEquals(Theme.plain(template, values), plain(cache.component(key, values)), key)
        }
    }

    @Test
    fun `a placeholder inside a tag argument keeps the per-render path`() {
        // the locale really carries this shape (`<click:open_url:'{link}'>`): a
        // marker there would be parsed into the url, so the cache has to refuse it
        applyPush(
            """
            bars:
              ram:
                title: "<click:open_url:'{link}'>RAM {used}</click>"
            """,
        )
        var parses = 0
        val cache = BarTitleCache(locale, parser = { parses++; Theme.parse(it) })
        val values = mapOf("link" to "https://example.invalid/sc", "used" to "3.4")

        val line = cache.component("bars.ram.title", values)

        assertEquals(plain(uncached("bars.ram.title", values)), plain(line))
        // the url is the proof: had the marker been cached into it, this would carry
        // the raw marker instead of the value the push asked for
        assertEquals(listOf("https://example.invalid/sc"), clickUrls(line))
        repeat(3) { assertEquals(plain(line), plain(cache.component("bars.ram.title", values))) }
        // only the probe parse; every render went through the plain path, which is
        // not behind this seam
        assertEquals(1, parses)
    }

    private fun clickUrls(component: Component): List<String> {
        val out = ArrayList<String>()
        fun walk(node: Component) {
            val click = node.clickEvent()
            val payload = click?.payload()
            if (click?.action() == ClickEvent.Action.OPEN_URL && payload is ClickEvent.Payload.Text) {
                out.add(payload.value())
            }
            node.children().forEach(::walk)
        }
        walk(component)
        return out
    }

    private fun applyPush(yaml: String) {
        val result = locale.applyPush("ru", "test-${System.nanoTime()}", yaml.trimIndent() + "\n")
        assertTrue(result is LocalePushResult.Accepted, "push rejected: $result")
    }

    private companion object {
        val RAM_VALUES = mapOf("used" to "2.0", "max" to "4.0", "pct" to "50")
        val LEGACY = LegacyComponentSerializer.builder().hexColors().build()
    }
}
