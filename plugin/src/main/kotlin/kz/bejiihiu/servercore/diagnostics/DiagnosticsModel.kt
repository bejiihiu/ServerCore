package kz.bejiihiu.servercore.diagnostics

/**
 * the data behind `/servercore diag`: raw readings in, one report out.
 *
 * everything here is plain data with no server dependency, so the assembly
 * rules and the json shape are testable without folia or packetevents. the
 * probe in [DiagnosticsProbe] is the only class that touches live objects.
 */

/** one subsystem the card reports on; the locale leaf keys its label */
enum class ModuleKey(val localeKey: String) {
    GUEST("guest"),
    PACKET("packet"),
    ANTI_ESP("anti-esp"),
    PROTECTION("protection"),
    SPEC("spec"),
    BARS("bars"),
    LUCKPERMS("luckperms"),
    PLACEHOLDERAPI("placeholderapi"),
    FLECTONEPULSE("flectonepulse"),
}

/**
 * why a module is not running. the four cases an operator cares about: it
 * works, it is off on purpose, the thing it needs is not installed, or it was
 * asked to run and did not.
 */
enum class ModuleStatus(val localeKey: String) {
    ENABLED("enabled"),
    DISABLED_BY_CONFIG("disabled"),
    ABSENT_DEPENDENCY("absent"),
    FAILED("failed"),
}

/** a short note next to a module status; the locale leaf keys the text */
enum class ModuleDetail(val localeKey: String) {
    PACKET_IDLE("packet-idle"),
    ALL_BARS_OFF("all-bars-off"),
    STATIC_FALLBACK("static-fallback"),
    FP_INCOMPATIBLE("fp-incompatible"),
    FP_CONNECTING("fp-connecting"),
}

data class ModuleState(
    val module: ModuleKey,
    val status: ModuleStatus,
    val detail: ModuleDetail? = null,
)

data class BuildFacts(
    /** version from the plugin metadata, the same one the boot line logs */
    val pluginVersion: String,
    /** the file the running code was loaded from */
    val jarName: String,
    val serverVersion: String,
    val folia: Boolean,
    val javaVersion: String,
)

/** raw module readings: config flags plus whether the runtime object exists */
data class ModuleFacts(
    val guestEnabled: Boolean,
    val guestActive: Boolean,
    val packetConfigured: Boolean,
    val packetEventsPresent: Boolean,
    val packetOnline: Boolean,
    val espActive: Boolean,
    val protectionEnabled: Boolean,
    val protectionActive: Boolean,
    val specEnabled: Boolean,
    val specActive: Boolean,
    val barsEnabled: Boolean,
    val barsActive: Boolean,
    val barsEnabledCount: Int,
    val luckPermsPresent: Boolean,
    val placeholderApiPresent: Boolean,
    val flectonePulsePresent: Boolean,
    val flectonePulseCompatible: Boolean,
    val flectonePulseConnected: Boolean,
)

data class LinkFacts(
    val connected: Boolean,
    /** null when nothing ever arrived, a fresh boot or a stopped client */
    val lastHeartbeatAgeMs: Long?,
    val heartbeatIntervalMs: Long,
    val outboxSize: Int,
    val lastDisconnectReason: String?,
    val reconnectAttempts: Long,
)

data class CountFacts(
    val guests: Int,
    val specSessions: Int,
    val bars: Int,
    val onlinePlayers: Int,
)

/** null means the integration is not installed */
data class VersionFacts(
    val packetEvents: String?,
    val luckPerms: String?,
    val placeholderApi: String?,
    val flectonePulse: String?,
)

data class DiagnosticsFacts(
    val build: BuildFacts,
    val modules: ModuleFacts,
    val link: LinkFacts,
    val counts: CountFacts,
    val versions: VersionFacts,
)

enum class Severity { FAIL, WARN }

/** the actionable half of a finding, deduplicated and capped in the report */
enum class Hint(val localeKey: String) {
    INSTALL_PACKETEVENTS("install-packetevents"),
    START_BACKEND("start-backend"),
    CHECK_CONFIG("check-config"),
    DRAIN_OUTBOX("drain-outbox"),
    CHECK_NETWORK("check-network"),
    INSTALL_LUCKPERMS("install-luckperms"),
    INSTALL_FLECTONEPULSE("install-flectonepulse"),
    UPDATE_FLECTONEPULSE("update-flectonepulse"),
    ENABLE_ANTI_ESP("enable-anti-esp"),
    ENABLE_PROTECTION("enable-protection"),
}

/** every rule the verdict can fire, with the copy key and the hint it implies */
enum class FindingCode(
    val severity: Severity,
    val hint: Hint?,
    val localeKey: String,
) {
    PACKET_OFFLINE(Severity.FAIL, Hint.INSTALL_PACKETEVENTS, "packet-offline"),
    BACKEND_DOWN(Severity.FAIL, Hint.START_BACKEND, "backend-down"),
    GUEST_FAILED(Severity.FAIL, Hint.CHECK_CONFIG, "guest-failed"),
    PROTECTION_FAILED(Severity.FAIL, Hint.CHECK_CONFIG, "protection-failed"),
    SPEC_FAILED(Severity.FAIL, Hint.CHECK_CONFIG, "spec-failed"),
    ESP_FAILED(Severity.FAIL, Hint.CHECK_CONFIG, "esp-failed"),
    BARS_FAILED(Severity.FAIL, Hint.CHECK_CONFIG, "bars-failed"),
    HEARTBEAT_STALE(Severity.WARN, Hint.CHECK_NETWORK, "heartbeat-stale"),
    OUTBOX_BACKLOG(Severity.WARN, Hint.DRAIN_OUTBOX, "outbox-backlog"),
    ANTI_ESP_DISABLED(Severity.WARN, Hint.ENABLE_ANTI_ESP, "anti-esp-disabled"),
    PROTECTION_DISABLED(Severity.WARN, Hint.ENABLE_PROTECTION, "protection-disabled"),
    LUCKPERMS_ABSENT(Severity.WARN, Hint.INSTALL_LUCKPERMS, "luckperms-absent"),
    FLECTONEPULSE_INCOMPATIBLE(Severity.WARN, Hint.UPDATE_FLECTONEPULSE, "flectonepulse-incompatible"),
    FLECTONEPULSE_ABSENT(Severity.WARN, Hint.INSTALL_FLECTONEPULSE, "flectonepulse-absent"),
    PLACEHOLDERAPI_ABSENT(Severity.WARN, null, "placeholderapi-absent"),
}

data class Finding(
    val code: FindingCode,
    /** values for the locale template, already stringified */
    val values: Map<String, String> = emptyMap(),
)

enum class Verdict { OK, WARN, FAIL }

data class DiagnosticsReport(
    val build: BuildFacts,
    val modules: List<ModuleState>,
    val link: LinkFacts,
    val counts: CountFacts,
    val versions: VersionFacts,
    val verdict: Verdict,
    val findings: List<Finding>,
    val hints: List<Hint>,
) {

    fun module(key: ModuleKey): ModuleState =
        modules.first { it.module == key }

    fun findingCount(severity: Severity): Int = findings.count { it.code.severity == severity }
}
