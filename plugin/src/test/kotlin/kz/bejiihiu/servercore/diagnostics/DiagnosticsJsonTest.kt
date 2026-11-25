package kz.bejiihiu.servercore.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * the --json contract: stable keys, enum constant names for statuses and
 * locale-neutral codes for findings, so a support script can rely on it.
 */
class DiagnosticsJsonTest {

    private fun facts(linkConnected: Boolean, versions: VersionFacts): DiagnosticsFacts = DiagnosticsFacts(
        build = BuildFacts(
            pluginVersion = "build-42-abc1234",
            jarName = "ServerCore-build-42-abc1234.jar",
            serverVersion = "26.3",
            folia = true,
            javaVersion = "25.0.2",
        ),
        modules = ModuleFacts(
            guestEnabled = true,
            guestActive = true,
            packetConfigured = true,
            packetEventsPresent = false,
            packetOnline = false,
            espActive = true,
            protectionEnabled = true,
            protectionActive = true,
            specEnabled = true,
            specActive = true,
            barsEnabled = true,
            barsActive = true,
            barsEnabledCount = 2,
            luckPermsPresent = false,
            placeholderApiPresent = true,
            flectonePulsePresent = true,
            flectonePulseCompatible = true,
            flectonePulseConnected = true,
        ),
        link = LinkFacts(
            connected = linkConnected,
            lastHeartbeatAgeMs = null,
            heartbeatIntervalMs = 15_000,
            outboxSize = 12,
            lastDisconnectReason = "closed (1006) ",
            reconnectAttempts = 3,
        ),
        counts = CountFacts(guests = 2, specSessions = 1, bars = 2, onlinePlayers = 5),
        versions = versions,
    )

    private fun encode(facts: DiagnosticsFacts): JsonObject =
        Json.parseToJsonElement(
            DiagnosticsJson.encode(DiagnosticsAssembler.assemble(facts)),
        ).jsonObject

    @Test
    fun `the document carries every section with stable key names`() {
        val json = encode(
            facts(
                linkConnected = false,
                versions = VersionFacts(
                    packetEvents = null,
                    luckPerms = null,
                    placeholderApi = "2.11.6",
                    flectonePulse = "1.15.2",
                ),
            ),
        )

        val build = json["build"]!!.jsonObject
        assertEquals("build-42-abc1234", build["pluginVersion"]!!.jsonPrimitive.content)
        assertEquals("26.3", build["server"]!!.jsonPrimitive.content)
        assertEquals(true, build["folia"]!!.jsonPrimitive.boolean)

        val modules = json["modules"]!!.jsonArray.map { it.jsonObject }
        val packet = modules.first { it["module"]!!.jsonPrimitive.content == "PACKET" }
        assertEquals("ABSENT_DEPENDENCY", packet["status"]!!.jsonPrimitive.content)
        val antiEsp = modules.first { it["module"]!!.jsonPrimitive.content == "ANTI_ESP" }
        assertEquals("ENABLED", antiEsp["status"]!!.jsonPrimitive.content)
        assertEquals("PACKET_IDLE", antiEsp["detail"]!!.jsonPrimitive.content)

        val link = json["link"]!!.jsonObject
        assertEquals(false, link["connected"]!!.jsonPrimitive.boolean)
        assertEquals(15_000L, link["heartbeatIntervalMs"]!!.jsonPrimitive.long)
        assertEquals(12, link["outboxSize"]!!.jsonPrimitive.int)
        assertEquals(3L, link["reconnectAttempts"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, link["lastHeartbeatAgeMs"])

        val counts = json["counts"]!!.jsonObject
        assertEquals(5, counts["players"]!!.jsonPrimitive.int)

        val versions = json["versions"]!!.jsonObject
        assertEquals(JsonNull, versions["packetevents"])
        assertEquals("2.11.6", versions["placeholderapi"]!!.jsonPrimitive.content)

        val verdict = json["verdict"]!!.jsonObject
        assertEquals("FAIL", verdict["status"]!!.jsonPrimitive.content)
        val findingCodes = verdict["findings"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
        assertEquals(listOf("packet-offline", "backend-down", "luckperms-absent"), findingCodes)
        val hints = verdict["hints"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("install-packetevents", "start-backend", "install-luckperms"), hints)
    }

    @Test
    fun `finding values land next to the code`() {
        val base = facts(
            linkConnected = true,
            versions = VersionFacts("2.14.0", "5.4.0", "2.11.6", "1.15.2"),
        )
        val json = encode(
            base.copy(
                link = base.link.copy(
                    lastHeartbeatAgeMs = 90_000,
                    outboxSize = DiagnosticsAssembler.OUTBOX_BACKLOG_THRESHOLD,
                ),
            ),
        )

        val findings = json["verdict"]!!.jsonObject["findings"]!!.jsonArray.map { it.jsonObject }
        val backlog = findings.first { it["code"]!!.jsonPrimitive.content == "outbox-backlog" }
        assertEquals("50", backlog["count"]!!.jsonPrimitive.content)
        assertEquals("WARN", backlog["severity"]!!.jsonPrimitive.content)
        val stale = findings.first { it["code"]!!.jsonPrimitive.content == "heartbeat-stale" }
        assertEquals("90", stale["seconds"]!!.jsonPrimitive.content)
    }
}
