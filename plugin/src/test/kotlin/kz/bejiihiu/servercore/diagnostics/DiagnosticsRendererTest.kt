package kz.bejiihiu.servercore.diagnostics

import java.nio.file.Path
import java.util.logging.Logger
import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.util.I18n
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * the card layout: every line resolves against the locale, no raw key or
 * placeholder leaks to an operator, and the two surfaces (card, console) are
 * the same list of lines.
 */
class DiagnosticsRendererTest {

    @TempDir
    lateinit var temp: Path

    private fun renderer(): DiagnosticsRenderer =
        DiagnosticsRenderer(I18n(LocaleService(temp, Logger.getLogger("test")), "en"))

    private fun modules(): ModuleFacts = ModuleFacts(
        guestEnabled = true,
        guestActive = true,
        packetConfigured = true,
        packetEventsPresent = true,
        packetOnline = true,
        espActive = true,
        protectionEnabled = true,
        protectionActive = true,
        specEnabled = false,
        specActive = false,
        barsEnabled = true,
        barsActive = true,
        barsEnabledCount = 4,
        luckPermsPresent = true,
        placeholderApiPresent = true,
        flectonePulsePresent = true,
        flectonePulseCompatible = true,
        flectonePulseConnected = true,
    )

    private fun facts(
        link: LinkFacts,
        modules: ModuleFacts = modules(),
        versions: VersionFacts = VersionFacts("2.14.0", "5.4.0", "2.11.6", "1.15.2"),
    ): DiagnosticsFacts = DiagnosticsFacts(
        build = BuildFacts("build-42-abc1234", "ServerCore.jar", "26.3", true, "25.0.2"),
        modules = modules,
        link = link,
        counts = CountFacts(guests = 2, specSessions = 0, bars = 4, onlinePlayers = 6),
        versions = versions,
    )

    @Test
    fun `a degraded server renders every section and resolves every placeholder`() {
        val report = DiagnosticsAssembler.assemble(
            facts(
                link = LinkFacts(
                    connected = true,
                    lastHeartbeatAgeMs = 4_821,
                    heartbeatIntervalMs = 15_000,
                    outboxSize = 60,
                    lastDisconnectReason = "closed (1006) ",
                    reconnectAttempts = 2,
                ),
                modules = modules().copy(luckPermsPresent = false),
            ),
        )
        val renderer = renderer()
        val lines = renderer.console(report)

        assertEquals(lines.size, renderer.card(report).size)
        val text = lines.joinToString("\n")
        for (section in listOf("build", "modules", "backend link", "counts", "versions", "verdict")) {
            assertTrue(text.contains(section), "section missing from the card: $section")
        }
        assertTrue(text.contains("spec · disabled by config"))
        assertTrue(text.contains("luckperms · absent dependency"))
        assertTrue(text.contains("static config fallback"))
        assertTrue(text.contains("4.8 s"))
        assertTrue(text.contains("outbox · 60"))
        assertTrue(text.contains("WARN"))
        // both warning findings carry their numbers and their hint
        assertTrue(text.contains("holds 60 frame(s)"))
        assertTrue(text.contains("install luckperms"))
        // nothing unresolved and no locale key leaked
        assertFalse(text.contains("{"))
        assertFalse(text.contains("diag."))
    }

    @Test
    fun `a healthy server renders the OK verdict with no hints`() {
        val report = DiagnosticsAssembler.assemble(
            facts(link = LinkFacts(true, 500, 15_000, 0, null, 0)),
        )
        val text = renderer().console(report).joinToString("\n")

        assertTrue(text.contains("OK"))
        assertTrue(text.contains("500 ms"))
        assertTrue(text.contains("packet · enabled"))
        assertFalse(text.contains("read the boot log"))
    }

    @Test
    fun `absent integrations render as absent, not as a crash`() {
        val report = DiagnosticsAssembler.assemble(
            facts(
                link = LinkFacts(false, null, 15_000, 0, "unknown server id", 4),
                modules = modules().copy(luckPermsPresent = false),
                versions = VersionFacts(null, null, null, null),
            ),
        )
        val text = renderer().console(report).joinToString("\n")

        assertTrue(text.contains("FAIL"))
        assertTrue(text.contains("reconnect attempts · 4"))
        assertTrue(text.contains("unknown server id"))
        assertTrue(text.contains("start the backend"))
        assertTrue(text.contains("packetevents · absent"))
        assertTrue(text.contains("flectonepulse · absent"))
    }
}
