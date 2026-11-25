package kz.bejiihiu.servercore.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * the decision surface of /servercore diag: module matrix, verdict rules and
 * the hint cap. no server: facts go in, a report comes out.
 */
class DiagnosticsAssemblerTest {

    private fun healthyModules(): ModuleFacts = ModuleFacts(
        guestEnabled = true,
        guestActive = true,
        packetConfigured = true,
        packetEventsPresent = true,
        packetOnline = true,
        espActive = true,
        protectionEnabled = true,
        protectionActive = true,
        specEnabled = true,
        specActive = true,
        barsEnabled = true,
        barsActive = true,
        barsEnabledCount = 4,
        luckPermsPresent = true,
        placeholderApiPresent = true,
        flectonePulsePresent = true,
        flectonePulseCompatible = true,
        flectonePulseConnected = true,
    )

    private fun healthyLink(): LinkFacts = LinkFacts(
        connected = true,
        lastHeartbeatAgeMs = 1_000,
        heartbeatIntervalMs = 15_000,
        outboxSize = 0,
        lastDisconnectReason = null,
        reconnectAttempts = 0,
    )

    private fun facts(
        modules: ModuleFacts = healthyModules(),
        link: LinkFacts = healthyLink(),
        counts: CountFacts = CountFacts(guests = 1, specSessions = 1, bars = 4, onlinePlayers = 3),
        versions: VersionFacts = VersionFacts(
            packetEvents = "2.14.0",
            luckPerms = "5.4.0",
            placeholderApi = "2.11.6",
            flectonePulse = "1.15.2",
        ),
    ): DiagnosticsFacts = DiagnosticsFacts(
        build = BuildFacts(
            pluginVersion = "build-42-abc1234",
            jarName = "ServerCore-build-42-abc1234.jar",
            serverVersion = "26.3",
            folia = true,
            javaVersion = "25.0.2",
        ),
        modules = modules,
        link = link,
        counts = counts,
        versions = versions,
    )

    private fun report(facts: DiagnosticsFacts): DiagnosticsReport =
        DiagnosticsAssembler.assemble(facts)

    @Test
    fun `a healthy setup is OK with an all-enabled matrix`() {
        val report = report(facts())

        assertEquals(Verdict.OK, report.verdict)
        assertTrue(report.findings.isEmpty())
        assertTrue(report.hints.isEmpty())
        assertEquals(ModuleKey.entries.toSet(), report.modules.map { it.module }.toSet())
        assertTrue(report.modules.all { it.status == ModuleStatus.ENABLED })
        assertTrue(report.modules.all { it.detail == null })
    }

    @Test
    fun `config flags map to disabled while enabled-but-missing maps to failed`() {
        val modules = healthyModules().copy(
            guestEnabled = false,
            guestActive = false,
            protectionActive = false,
            specEnabled = false,
            specActive = false,
            espActive = false,
            barsEnabled = false,
            barsActive = false,
            barsEnabledCount = 0,
        )
        val report = report(facts(modules = modules))

        assertEquals(ModuleStatus.DISABLED_BY_CONFIG, report.module(ModuleKey.GUEST).status)
        assertEquals(ModuleStatus.DISABLED_BY_CONFIG, report.module(ModuleKey.SPEC).status)
        assertEquals(ModuleStatus.DISABLED_BY_CONFIG, report.module(ModuleKey.BARS).status)
        assertEquals(ModuleStatus.FAILED, report.module(ModuleKey.PROTECTION).status)
        assertEquals(ModuleStatus.FAILED, report.module(ModuleKey.ANTI_ESP).status)
    }

    @Test
    fun `an absent packetevents is a dependency gap not a crash`() {
        val modules = healthyModules().copy(
            packetEventsPresent = false,
            packetOnline = false,
            espActive = true,
        )
        val report = report(facts(modules = modules))

        assertEquals(ModuleStatus.ABSENT_DEPENDENCY, report.module(ModuleKey.PACKET).status)
        // the engine still runs, its hiding just has no packet writer behind it
        assertEquals(ModuleStatus.ENABLED, report.module(ModuleKey.ANTI_ESP).status)
        assertEquals(ModuleDetail.PACKET_IDLE, report.module(ModuleKey.ANTI_ESP).detail)
    }

    @Test
    fun `every bar switched off is a config state, a missing renderer is a failure`() {
        val allOff = report(facts(modules = healthyModules().copy(barsActive = false, barsEnabledCount = 0)))
        assertEquals(ModuleStatus.DISABLED_BY_CONFIG, allOff.module(ModuleKey.BARS).status)
        assertEquals(ModuleDetail.ALL_BARS_OFF, allOff.module(ModuleKey.BARS).detail)

        val broken = report(facts(modules = healthyModules().copy(barsActive = false, barsEnabledCount = 3)))
        assertEquals(ModuleStatus.FAILED, broken.module(ModuleKey.BARS).status)
        assertNull(broken.module(ModuleKey.BARS).detail)
    }

    @Test
    fun `soft dependencies degrade to absent with a fallback note`() {
        val modules = healthyModules().copy(
            luckPermsPresent = false,
            placeholderApiPresent = false,
            flectonePulsePresent = false,
            flectonePulseConnected = false,
        )
        val report = report(facts(modules = modules))

        assertEquals(ModuleStatus.ABSENT_DEPENDENCY, report.module(ModuleKey.LUCKPERMS).status)
        assertEquals(ModuleDetail.STATIC_FALLBACK, report.module(ModuleKey.LUCKPERMS).detail)
        assertEquals(ModuleStatus.ABSENT_DEPENDENCY, report.module(ModuleKey.PLACEHOLDERAPI).status)
        assertEquals(ModuleStatus.ABSENT_DEPENDENCY, report.module(ModuleKey.FLECTONEPULSE).status)
        assertEquals(Verdict.WARN, report.verdict)
        assertEquals(
            setOf(FindingCode.LUCKPERMS_ABSENT, FindingCode.FLECTONEPULSE_ABSENT, FindingCode.PLACEHOLDERAPI_ABSENT),
            report.findings.map { it.code }.toSet(),
        )
    }

    @Test
    fun `an incompatible flectonepulse is absent, a connecting one is enabled`() {
        val incompatible = report(
            facts(modules = healthyModules().copy(flectonePulseCompatible = false, flectonePulseConnected = false)),
        )
        assertEquals(ModuleStatus.ABSENT_DEPENDENCY, incompatible.module(ModuleKey.FLECTONEPULSE).status)
        assertEquals(ModuleDetail.FP_INCOMPATIBLE, incompatible.module(ModuleKey.FLECTONEPULSE).detail)
        assertTrue(incompatible.findings.any { it.code == FindingCode.FLECTONEPULSE_INCOMPATIBLE })

        val connecting = report(
            facts(modules = healthyModules().copy(flectonePulseConnected = false)),
        )
        assertEquals(ModuleStatus.ENABLED, connecting.module(ModuleKey.FLECTONEPULSE).status)
        assertEquals(ModuleDetail.FP_CONNECTING, connecting.module(ModuleKey.FLECTONEPULSE).detail)
    }

    @Test
    fun `a dead backend link fails the verdict and leads the hints`() {
        val report = report(facts(link = healthyLink().copy(connected = false, reconnectAttempts = 7)))

        assertEquals(Verdict.FAIL, report.verdict)
        assertEquals(listOf(FindingCode.BACKEND_DOWN), report.findings.map { it.code })
        assertEquals(listOf(Hint.START_BACKEND), report.hints)
    }

    @Test
    fun `an offline packet layer fails the verdict`() {
        val report = report(
            facts(modules = healthyModules().copy(packetOnline = false)),
        )

        assertEquals(Verdict.FAIL, report.verdict)
        assertEquals(FindingCode.PACKET_OFFLINE, report.findings.first().code)
        assertEquals(Hint.INSTALL_PACKETEVENTS, report.hints.first())
    }

    @Test
    fun `disabled protections warn without blocking play`() {
        val modules = healthyModules().copy(
            packetConfigured = false,
            packetOnline = false,
            espActive = false,
            protectionEnabled = false,
            protectionActive = false,
        )
        val report = report(facts(modules = modules))

        assertEquals(Verdict.WARN, report.verdict)
        assertEquals(
            setOf(FindingCode.ANTI_ESP_DISABLED, FindingCode.PROTECTION_DISABLED),
            report.findings.map { it.code }.toSet(),
        )
    }

    @Test
    fun `a stalled heartbeat and a backed-up outbox warn with their numbers`() {
        val report = report(
            facts(
                link = healthyLink().copy(
                    lastHeartbeatAgeMs = 40_000,
                    heartbeatIntervalMs = 15_000,
                    outboxSize = DiagnosticsAssembler.OUTBOX_BACKLOG_THRESHOLD,
                ),
            ),
        )

        assertEquals(Verdict.WARN, report.verdict)
        val stale = report.findings.first { it.code == FindingCode.HEARTBEAT_STALE }
        assertEquals("40", stale.values["seconds"])
        val backlog = report.findings.first { it.code == FindingCode.OUTBOX_BACKLOG }
        assertEquals(DiagnosticsAssembler.OUTBOX_BACKLOG_THRESHOLD.toString(), backlog.values["count"])
    }

    @Test
    fun `a fresh heartbeat never warns even on a long interval`() {
        val report = report(facts(link = healthyLink().copy(lastHeartbeatAgeMs = 14_000, heartbeatIntervalMs = 15_000)))

        assertEquals(Verdict.OK, report.verdict)
        assertTrue(report.findings.none { it.code == FindingCode.HEARTBEAT_STALE })
    }

    @Test
    fun `hints are deduplicated and capped at three`() {
        // four enabled-but-failed modules all point at the same hint
        val modules = healthyModules().copy(
            guestActive = false,
            protectionActive = false,
            specActive = false,
            espActive = false,
        )
        val okHints = report(facts(modules = modules))
        assertEquals(listOf(Hint.CHECK_CONFIG), okHints.hints)

        // three different failures: packet, backend and the config trio
        val many = report(
            facts(
                modules = modules.copy(packetEventsPresent = false, packetOnline = false),
                link = healthyLink().copy(connected = false),
            ),
        )
        assertEquals(3, many.hints.size)
        assertEquals(
            listOf(Hint.INSTALL_PACKETEVENTS, Hint.START_BACKEND, Hint.CHECK_CONFIG),
            many.hints,
        )
    }
}
