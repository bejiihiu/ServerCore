package kz.bejiihiu.servercore.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kz.bejiihiu.servercore.websocket.Payload
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SpecSessionRegistryTest {

    @TempDir
    lateinit var dir: Path

    private val admin = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val target = UUID.fromString("1b4b2f2e-1c2d-3e4f-5a6b-7c8d9e0f1a2b")

    /** records every spec frame the registry produces */
    private class RecordingSender : SpecEventSender {
        val started = mutableListOf<Payload.SpecStarted>()
        val ended = mutableListOf<Payload.SpecEnded>()
        override fun sendSpecStarted(payload: Payload.SpecStarted) {
            started.add(payload)
        }
        override fun sendSpecEnded(payload: Payload.SpecEnded) {
            ended.add(payload)
        }
    }

    private fun session(
        adminId: UUID = admin,
        targetId: UUID = target,
        startedAt: Long = 100_000L,
    ): SpecSession = SpecSession(
        adminId = adminId,
        adminName = "Admin",
        targetId = targetId,
        targetName = "Target",
        sessionId = "spec-abc",
        startedAtMs = startedAt,
    )

    private fun newRegistry(
        sender: RecordingSender = RecordingSender(),
        clock: () -> Long = { 100_000L },
        audit: SpecAuditTrail? = null,
    ): SpecSessionRegistry = SpecSessionRegistry(sender, audit, clock)

    /** trail whose prune clock matches the fake entry clock, so nothing expires mid-test */
    private fun trailFor(clock: () -> Long): SpecAuditTrail = SpecAuditTrail(dir, Runnable::run, clock = clock)

    @Test
    fun `started reports the session upstream`() {
        val sender = RecordingSender()
        val registry = newRegistry(sender)

        registry.started(session())
        assertEquals(1, sender.started.size)

        val payload = sender.started.single()
        assertEquals(admin.toString(), payload.adminUuid)
        assertEquals(target.toString(), payload.targetUuid)
        assertEquals("spec-abc", payload.sessionId)
        assertEquals("Admin", payload.adminUsername)
    }

    @Test
    fun `ended computes the wall duration and reports the reason`() {
        val sender = RecordingSender()
        var now = 100_000L
        val registry = newRegistry(sender, { now })

        registry.started(session())
        now += 95_000
        registry.ended(session(), ExitReason.COMMAND)

        val payload = sender.ended.single()
        assertEquals(95, payload.durationSec)
        assertEquals("NORMAL", payload.reason)
        assertEquals("spec-abc", payload.sessionId)
    }

    @Test
    fun `every exit reason maps to its wire reason`() {
        val sender = RecordingSender()
        var now = 100_000L
        val registry = newRegistry(sender, { now })

        val cases = listOf(
            ExitReason.COMMAND to "NORMAL",
            ExitReason.TARGET_QUIT to "TARGET_DISCONNECTED",
            ExitReason.ADMIN_QUIT to "ADMIN_DISCONNECTED",
            ExitReason.SERVER_SHUTDOWN to "FORCED",
        )
        for ((exit, wire) in cases) {
            val s = session()
            registry.started(s)
            now += 1_000
            registry.ended(s, exit)
            assertEquals(wire, sender.ended.last().reason)
        }
        assertEquals(4, sender.ended.size)
    }

    @Test
    fun `a non-normal end lands as SPEC_FORCED_END in the audit trail`() {
        val trail = trailFor { 100_000L }
        val registry = newRegistry(audit = trail)
        val s = session()
        registry.started(s)
        registry.ended(s, ExitReason.TARGET_QUIT)
        trail.flushNow()

        val file = Files.list(dir).use { stream ->
            stream.map { p -> p.fileName.toString() }.toList().single()
        }
        val lines = Files.readAllLines(dir.resolve(file)).filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        val events = lines.map { Json.parseToJsonElement(it).jsonObject["event"]!!.jsonPrimitive.content }
        assertEquals(SpecAuditEvents.START, events[0])
        assertEquals(SpecAuditEvents.FORCED_END, events[1])
    }

    @Test
    fun `denials land in the trail only, never on the wire`() {
        val sender = RecordingSender()
        val trail = trailFor { 100_000L }
        val registry = newRegistry(sender, audit = trail)

        registry.denied(admin, "Admin", target, "Target", "no access level resolved")
        trail.flushNow()

        assertTrue(sender.started.isEmpty() && sender.ended.isEmpty())
        val names = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }
        val lines = names.flatMap { Files.readAllLines(dir.resolve(it)) }.filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        val entry = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals(SpecAuditEvents.DENIED, entry["event"]!!.jsonPrimitive.content)
        assertEquals("no access level resolved", entry["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the live count comes from the session authority`() {
        val registry = newRegistry()
        assertEquals(0, registry.activeCount(), "an unbound bridge counts nothing")

        var live = 3
        registry.bindActiveCount { live }
        assertEquals(3, registry.activeCount())

        live = 1
        assertEquals(1, registry.activeCount(), "the bridge only forwards, it never counts")
    }
}
