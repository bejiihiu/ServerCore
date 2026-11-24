package kz.bejiihiu.servercore.locale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

class LocaleServiceTest {

    private val logger = Logger.getLogger("test")

    @TempDir
    lateinit var temp: Path

    @Test
    fun `boots on the bundled seed`() {
        val service = LocaleService(temp, logger)
        assertFalse(service.snapshot().isEmpty())
        assertEquals("bundled", service.revision)
        // a known key from the seed resolves with a placeholder
        val window = service.text("auth.window", mapOf("code" to "123-456", "time" to "5:00"))
        assertTrue(window.contains("123-456") && window.contains("5:00"))
        assertFalse(window.contains("{"))
    }

    @Test
    fun `missing key renders as the key itself`() {
        val service = LocaleService(temp, logger)
        assertEquals("ghost.key", service.text("ghost.key"))
    }

    @Test
    fun `bundled seed resolves every key the ui kit surfaces use`() {
        val service = LocaleService(temp, logger)
        val uiKeys = listOf(
            "tablist.header",
            "tablist.footer",
            "guest.welcome.title",
            "guest.welcome.subtitle",
            "guest.welcome.card",
            "auth.window",
            "auth.prompt.card",
            "auth.pulse",
            "auth.expired",
        )
        for (key in uiKeys) {
            // a missing key renders as the key itself, which players would see
            assertTrue(key != service.text(key), "locale seed is missing ui key: $key")
        }
        // regression guard: the auth card lives at auth.prompt.card; a nested
        // map under auth.prompt would silently render the bare key again
        assertFalse(service.snapshot().containsKey("auth.prompt"))
    }

    @Test
    fun `both bundled locales carry the same key set`() {
        val service = LocaleService(temp, logger)
        assertEquals(service.snapshot("en").keys, service.snapshot("ru").keys)
    }

    @Test
    fun `language switch serves the other locale`() {
        val service = LocaleService(temp, logger)
        val ru = service.text("tablist.header")
        service.setLanguage("en")
        val en = service.text("tablist.header")
        assertNotEquals(ru, en)
        assertTrue(en.contains("closed season"))
        // an unknown tag stays canonical instead of blanking the server
        service.setLanguage("de")
        assertEquals(ru, service.text("tablist.header"))
    }

    @Test
    fun `push replaces the store and persists to disk`() {
        val service = LocaleService(temp, logger)
        val pushed = "greeting:\n  line: \"<info>новая волна</info>\"\n"
        val result = service.applyPush("ru", "rev-7", pushed)
        assertTrue(result is LocalePushResult.Accepted)
        assertEquals("rev-7", service.revision)
        assertEquals("<info>новая волна</info>", service.text("greeting.line"))
        // seed keys that the push dropped are gone — full file semantics
        assertFalse(service.snapshot().containsKey("guest.welcome.title"))
        // and the file survives for the next boot
        val persisted = Files.readString(temp.resolve("locales/ru.yml"))
        assertTrue(persisted.contains("новая волна"))
    }

    @Test
    fun `a dropped key falls back to the english base layer`() {
        val service = LocaleService(temp, logger)
        service.applyPush("ru", "rev-11", "greeting:\n  line: \"<info>новая волна</info>\"\n")
        // ru no longer ships the tablist, so the base locale answers instead
        assertNotEquals("tablist.header", service.text("tablist.header"))
    }

    @Test
    fun `push accepts any bundled locale`() {
        val service = LocaleService(temp, logger)
        val result = service.applyPush("en", "rev-en", "greeting:\n  line: \"<info>new wave</info>\"\n")
        assertTrue(result is LocalePushResult.Accepted)
        service.setLanguage("en")
        assertEquals("rev-en", service.revision)
        assertEquals("<info>new wave</info>", service.text("greeting.line"))
        assertTrue(Files.exists(temp.resolve("locales/en.yml")))
    }

    @Test
    fun `push of an unsupported locale is rejected`() {
        val service = LocaleService(temp, logger)
        val result = service.applyPush("de", "rev-8", "a: b\n")
        assertTrue(result is LocalePushResult.Rejected)
        assertEquals("bundled", service.revision)
    }

    @Test
    fun `push of broken yaml is rejected and keeps the old store`() {
        val service = LocaleService(temp, logger)
        val result = service.applyPush("ru", "rev-9", "tablist: [unclosed")
        assertTrue(result is LocalePushResult.Rejected)
        assertFalse(service.snapshot().isEmpty())
    }

    @Test
    fun `push of an empty document is rejected`() {
        val service = LocaleService(temp, logger)
        val result = service.applyPush("ru", "rev-10", "# nothing here\n")
        assertTrue(result is LocalePushResult.Rejected)
    }

    @Test
    fun `broken persisted files fall back to the bundled seeds`() {
        Files.createDirectories(temp.resolve("locales"))
        Files.writeString(temp.resolve("locales/ru.yml"), "tablist: [unclosed")
        val service = LocaleService(temp, logger)
        assertNotEquals("tablist.header", service.text("tablist.header"))
        assertEquals("bundled", service.revision)
    }
}
