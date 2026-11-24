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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SpecSessionRegistryTest {

    @TempDir
    lateinit var dir: Path

    private val admin = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val target = UUID.fromString("1b4b2f2e-1c2d-3e4f-5a6b-7c8d9e0f1a2b")
    private val otherAdmin = UUID.fromString("2a4b6c8e-0d1f-2a3b-4c5d-6e7f8091a2b3")
    private val secondTarget = UUID.fromString("3c5d7e9f-0a1b-2c3d-4e5f-60718293a4b5")

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

    private fun newRegistry(
        sender: RecordingSender = RecordingSender(),
        clock: () -> Long = System::currentTimeMillis,
        audit: SpecAuditTrail? = null,
    ): SpecSessionRegistry = SpecSessionRegistry(audit, sender, clock)

    @Test
    fun `start registers the session, reports it and counts it`() {
        val sender = RecordingSender()
        val registry = newRegistry(sender)

        val result = registry.start(admin, "Admin", target, "Target", "spec-abc")
        assertEquals(SpecStartResult.STARTED, result)
        assertEquals(1, registry.activeCount())
        assertEquals(1, sender.started.size)

        val payload = sender.started.single()
        assertEquals(admin.toString(), payload.adminUuid)
        assertEquals(target.toString(), payload.targetUuid)
        assertEquals("spec-abc", payload.sessionId)
        assertEquals("Admin", payload.adminUsername)
    }

    @Test
    fun `conflicting sessions are refused`() {
        val registry = newRegistry()
        assertEquals(SpecStartResult.STARTED, registry.start(admin, "Admin", target, "Target", "spec-1"))

        // same admin, another target
        assertEquals(SpecStartResult.ADMIN_BUSY, registry.start(admin, "Admin", secondTarget, "Other", "spec-2"))

        // another admin, same target
        assertEquals(
            SpecStartResult.TARGET_ALREADY_WATCHED,
            registry.start(otherAdmin, "Other", target, "Target", "spec-3"),
        )

        // nothing changed: one session, one live claim
        assertEquals(1, registry.activeCount())
        assertEquals(1, registry.listActive().size)
    }

    @Test
    fun `end computes the wall duration and reports the reason`() {
        val sender = RecordingSender()
        var now = 100_000L
        val registry = newRegistry(sender, { now })

        registry.start(admin, "Admin", target, "Target", "spec-1")
        now += 95_000
        assertTrue(registry.end(admin, "spec-1", SpecEndReason.NORMAL))

        val payload = sender.ended.single()
        assertEquals(95, payload.durationSec)
        assertEquals("NORMAL", payload.reason)
        assertEquals("spec-1", payload.sessionId)
        assertEquals(0, registry.activeCount())

        // the target claim is free again
        assertEquals(SpecStartResult.STARTED, registry.start(otherAdmin, "Other", target, "Target", "spec-2"))
    }

    @Test
    fun `a non-normal end lands as SPEC_FORCED_END in the audit trail`() {
        val trail = SpecAuditTrail(dir, Runnable::run)
        val registry = newRegistry(audit = trail)
        registry.start(admin, "Admin", target, "Target", "spec-1")
        registry.endAllForTarget(target, SpecEndReason.TARGET_DISCONNECTED)
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
    fun `end with an unknown session is a no-op`() {
        val sender = RecordingSender()
        val registry = newRegistry(sender)
        assertFalse(registry.end(admin, "nope", SpecEndReason.NORMAL))
        assertEquals(SpecStartResult.STARTED, registry.start(admin, "Admin", target, "Target", "spec-1"))
        assertFalse(registry.end(admin, "different-id", SpecEndReason.NORMAL))
        assertEquals(1, registry.activeCount())
        assertTrue(sender.ended.isEmpty())
    }

    @Test
    fun `admin disconnect closes all their sessions`() {
        val sender = RecordingSender()
        val registry = newRegistry(sender)
        registry.start(admin, "Admin", target, "Target", "spec-1")
        registry.start(otherAdmin, "Other", secondTarget, "Other", "spec-2")

        assertEquals(1, registry.endAllForAdmin(admin, SpecEndReason.ADMIN_DISCONNECTED))
        assertEquals(1, registry.activeCount())
        // only the admin's own session ends; the other admin keeps watching
        assertEquals(1, sender.ended.size)
        assertEquals("ADMIN_DISCONNECTED", sender.ended[0].reason)

        // target claims of the closed sessions are free again
        assertEquals(SpecStartResult.STARTED, registry.start(admin, "Admin", target, "Target", "spec-3"))
    }
}
