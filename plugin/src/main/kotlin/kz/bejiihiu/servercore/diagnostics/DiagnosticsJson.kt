package kz.bejiihiu.servercore.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * the `--json` form: one stable document an operator can paste into a ticket,
 * and a script can parse. enum values go out as their constant names and
 * findings as stable codes, so the shape does not drift with the locale.
 */
object DiagnosticsJson {

    private val pretty = Json { prettyPrint = true }

    fun encode(report: DiagnosticsReport): String =
        pretty.encodeToString(JsonObject.serializer(), toJson(report))

    fun toJson(report: DiagnosticsReport): JsonObject = buildJsonObject {
        putJsonObject("build") {
            put("pluginVersion", report.build.pluginVersion)
            put("jar", report.build.jarName)
            put("server", report.build.serverVersion)
            put("folia", report.build.folia)
            put("java", report.build.javaVersion)
        }
        putJsonArray("modules") {
            for (module in report.modules) {
                addJsonObject {
                    put("module", module.module.name)
                    put("status", module.status.name)
                    module.detail?.let { put("detail", it.name) }
                }
            }
        }
        putJsonObject("link") {
            put("connected", report.link.connected)
            put("lastHeartbeatAgeMs", report.link.lastHeartbeatAgeMs)
            put("heartbeatIntervalMs", report.link.heartbeatIntervalMs)
            put("outboxSize", report.link.outboxSize)
            put("lastDisconnectReason", report.link.lastDisconnectReason)
            put("reconnectAttempts", report.link.reconnectAttempts)
        }
        putJsonObject("counts") {
            put("guests", report.counts.guests)
            put("specSessions", report.counts.specSessions)
            put("bars", report.counts.bars)
            put("players", report.counts.onlinePlayers)
        }
        putJsonObject("versions") {
            put("packetevents", report.versions.packetEvents)
            put("luckperms", report.versions.luckPerms)
            put("placeholderapi", report.versions.placeholderApi)
            put("flectonepulse", report.versions.flectonePulse)
        }
        putJsonObject("verdict") {
            put("status", report.verdict.name)
            putJsonArray("findings") {
                for (finding in report.findings) {
                    addJsonObject {
                        put("code", finding.code.localeKey)
                        put("severity", finding.code.severity.name)
                        for ((key, value) in finding.values) put(key, value)
                    }
                }
            }
            putJsonArray("hints") {
                for (hint in report.hints) add(hint.localeKey)
            }
        }
    }
}
