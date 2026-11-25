package kz.bejiihiu.servercore.diagnostics

import kz.bejiihiu.servercore.diagnostics.ModuleDetail.ALL_BARS_OFF
import kz.bejiihiu.servercore.diagnostics.ModuleDetail.FP_CONNECTING
import kz.bejiihiu.servercore.diagnostics.ModuleDetail.FP_INCOMPATIBLE
import kz.bejiihiu.servercore.diagnostics.ModuleDetail.PACKET_IDLE
import kz.bejiihiu.servercore.diagnostics.ModuleDetail.STATIC_FALLBACK

/**
 * the rules behind `/servercore diag`: raw readings become a module matrix, a
 * finding list and a verdict. pure on purpose — the whole decision surface is
 * exercised in tests with faked states, no server needed.
 *
 * the shape of the answer: FAIL means something the config asks for is not
 * running (or the backend link is down, which takes authentication with it);
 * WARN means a soft dependency is missing, a protection is switched off, or
 * the link is unhealthy but still up.
 */
object DiagnosticsAssembler {

    /** above this many parked frames the backend is not draining the outbox */
    const val OUTBOX_BACKLOG_THRESHOLD = 50

    /** a card can carry three actions before it turns into a wall */
    const val MAX_HINTS = 3

    fun assemble(facts: DiagnosticsFacts): DiagnosticsReport {
        val modules = moduleStates(facts.modules)
        val findings = findings(facts, modules)
        val verdict = when {
            findings.any { it.code.severity == Severity.FAIL } -> Verdict.FAIL
            findings.isNotEmpty() -> Verdict.WARN
            else -> Verdict.OK
        }
        return DiagnosticsReport(
            build = facts.build,
            modules = modules,
            link = facts.link,
            counts = facts.counts,
            versions = facts.versions,
            verdict = verdict,
            findings = findings,
            hints = findings.mapNotNull { it.code.hint }.distinct().take(MAX_HINTS),
        )
    }

    /** the module matrix: config says yes, but did the runtime object come up */
    fun moduleStates(facts: ModuleFacts): List<ModuleState> = listOf(
        ModuleState(
            module = ModuleKey.GUEST,
            status = when {
                !facts.guestEnabled -> ModuleStatus.DISABLED_BY_CONFIG
                facts.guestActive -> ModuleStatus.ENABLED
                else -> ModuleStatus.FAILED
            },
        ),
        packetState(facts),
        ModuleState(
            module = ModuleKey.ANTI_ESP,
            status = when {
                !facts.packetConfigured -> ModuleStatus.DISABLED_BY_CONFIG
                facts.espActive -> ModuleStatus.ENABLED
                else -> ModuleStatus.FAILED
            },
            // the engine runs without packets, its hiding just degrades to nothing
            detail = if (facts.espActive && !facts.packetOnline) PACKET_IDLE else null,
        ),
        ModuleState(
            module = ModuleKey.PROTECTION,
            status = when {
                !facts.protectionEnabled -> ModuleStatus.DISABLED_BY_CONFIG
                facts.protectionActive -> ModuleStatus.ENABLED
                else -> ModuleStatus.FAILED
            },
        ),
        ModuleState(
            module = ModuleKey.SPEC,
            status = when {
                !facts.specEnabled -> ModuleStatus.DISABLED_BY_CONFIG
                facts.specActive -> ModuleStatus.ENABLED
                else -> ModuleStatus.FAILED
            },
        ),
        ModuleState(
            module = ModuleKey.BARS,
            status = when {
                !facts.barsEnabled || facts.barsEnabledCount == 0 -> ModuleStatus.DISABLED_BY_CONFIG
                facts.barsActive -> ModuleStatus.ENABLED
                else -> ModuleStatus.FAILED
            },
            detail = if (facts.barsEnabled && facts.barsEnabledCount == 0) ALL_BARS_OFF else null,
        ),
        ModuleState(
            module = ModuleKey.LUCKPERMS,
            status = if (facts.luckPermsPresent) ModuleStatus.ENABLED else ModuleStatus.ABSENT_DEPENDENCY,
            // the plugin never fails without luckperms, it silently loses real groups
            detail = if (facts.luckPermsPresent) null else STATIC_FALLBACK,
        ),
        ModuleState(
            module = ModuleKey.PLACEHOLDERAPI,
            status = if (facts.placeholderApiPresent) ModuleStatus.ENABLED else ModuleStatus.ABSENT_DEPENDENCY,
        ),
        flectonePulseState(facts),
    )

    private fun packetState(facts: ModuleFacts): ModuleState = ModuleState(
        module = ModuleKey.PACKET,
        status = when {
            !facts.packetConfigured -> ModuleStatus.DISABLED_BY_CONFIG
            !facts.packetEventsPresent -> ModuleStatus.ABSENT_DEPENDENCY
            facts.packetOnline -> ModuleStatus.ENABLED
            else -> ModuleStatus.FAILED
        },
    )

    private fun flectonePulseState(facts: ModuleFacts): ModuleState = ModuleState(
        module = ModuleKey.FLECTONEPULSE,
        status = when {
            !facts.flectonePulsePresent -> ModuleStatus.ABSENT_DEPENDENCY
            !facts.flectonePulseCompatible -> ModuleStatus.ABSENT_DEPENDENCY
            else -> ModuleStatus.ENABLED
        },
        detail = when {
            !facts.flectonePulsePresent -> null
            !facts.flectonePulseCompatible -> FP_INCOMPATIBLE
            !facts.flectonePulseConnected -> FP_CONNECTING
            else -> null
        },
    )

    private fun findings(facts: DiagnosticsFacts, modules: List<ModuleState>): List<Finding> {
        fun statusOf(key: ModuleKey): ModuleStatus = modules.first { it.module == key }.status

        val out = mutableListOf<Finding>()
        when (statusOf(ModuleKey.PACKET)) {
            ModuleStatus.ABSENT_DEPENDENCY, ModuleStatus.FAILED -> out += Finding(FindingCode.PACKET_OFFLINE)
            else -> Unit
        }
        if (!facts.link.connected) out += Finding(FindingCode.BACKEND_DOWN)
        if (statusOf(ModuleKey.GUEST) == ModuleStatus.FAILED) out += Finding(FindingCode.GUEST_FAILED)
        if (statusOf(ModuleKey.PROTECTION) == ModuleStatus.FAILED) out += Finding(FindingCode.PROTECTION_FAILED)
        if (statusOf(ModuleKey.SPEC) == ModuleStatus.FAILED) out += Finding(FindingCode.SPEC_FAILED)
        if (statusOf(ModuleKey.ANTI_ESP) == ModuleStatus.FAILED) out += Finding(FindingCode.ESP_FAILED)
        if (statusOf(ModuleKey.BARS) == ModuleStatus.FAILED) out += Finding(FindingCode.BARS_FAILED)

        val heartbeatAge = facts.link.lastHeartbeatAgeMs
        if (facts.link.connected && heartbeatAge != null && heartbeatAge > facts.link.heartbeatIntervalMs * 2) {
            out += Finding(FindingCode.HEARTBEAT_STALE, mapOf("seconds" to (heartbeatAge / 1000).toString()))
        }
        if (facts.link.outboxSize >= OUTBOX_BACKLOG_THRESHOLD) {
            out += Finding(FindingCode.OUTBOX_BACKLOG, mapOf("count" to facts.link.outboxSize.toString()))
        }
        if (statusOf(ModuleKey.ANTI_ESP) == ModuleStatus.DISABLED_BY_CONFIG) {
            out += Finding(FindingCode.ANTI_ESP_DISABLED)
        }
        if (statusOf(ModuleKey.PROTECTION) == ModuleStatus.DISABLED_BY_CONFIG) {
            out += Finding(FindingCode.PROTECTION_DISABLED)
        }
        if (statusOf(ModuleKey.LUCKPERMS) == ModuleStatus.ABSENT_DEPENDENCY) {
            out += Finding(FindingCode.LUCKPERMS_ABSENT)
        }
        when {
            !facts.modules.flectonePulsePresent -> out += Finding(FindingCode.FLECTONEPULSE_ABSENT)
            !facts.modules.flectonePulseCompatible -> out += Finding(FindingCode.FLECTONEPULSE_INCOMPATIBLE)
        }
        if (statusOf(ModuleKey.PLACEHOLDERAPI) == ModuleStatus.ABSENT_DEPENDENCY) {
            out += Finding(FindingCode.PLACEHOLDERAPI_ABSENT)
        }
        return out
    }
}
