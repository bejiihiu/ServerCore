package kz.bejiihiu.servercore.websocket

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableOutboxTest {

    private val dir = Files.createTempDirectory("servercore-outbox-test")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(dir)
    }

    private fun outbox(name: String, max: Int = 500) =
        DurableOutbox(dir.resolve(name), Runnable::run, maxEntries = max)

    private fun entry(id: String, key: String, type: String = "GUEST_STARTED") =
        ParkedFrame(id = id, key = key, type = type, wire = """{"id":"$id"}""", enqueuedAt = 1_000)

    @Test
    fun `park and unpark roundtrip`() {
        val box = outbox("basic.json")
        box.load()
        box.park(entry("frame-1", "GUEST_STARTED|guest-a"))
        assertEquals(1, box.size())
        box.unpark("frame-1")
        assertEquals(0, box.size())
    }

    @Test
    fun `same idempotency key is upserted, never duplicated`() {
        val box = outbox("upsert.json")
        box.load()
        box.park(entry("frame-1", "GUEST_STARTED|guest-a"))
        box.park(entry("frame-2", "GUEST_STARTED|guest-a"))
        assertEquals(1, box.size())
        // the newer frame won
        assertEquals("frame-2", box.snapshot().single().id)
    }

    @Test
    fun `different session ids park side by side`() {
        val box = outbox("multi.json")
        box.load()
        box.park(entry("frame-1", "GUEST_STARTED|guest-a"))
        box.park(entry("frame-2", "GUEST_STARTED|guest-b"))
        box.park(entry("frame-3", "GUEST_ENDED|guest-a", type = "GUEST_ENDED"))
        assertEquals(3, box.size())
    }

    @Test
    fun `content survives a plugin restart`() {
        outbox("durable.json").apply {
            load()
            park(entry("frame-1", "GUEST_STARTED|guest-a"))
            park(entry("frame-2", "GUEST_STARTED|guest-b"))
            // the crash simulation: async writes are cut off, the flush is not
            flushNow()
        }
        // fresh process, same file
        val reloaded = outbox("durable.json")
        reloaded.load()
        assertEquals(listOf("frame-1", "frame-2"), reloaded.snapshot().map { it.id })
        assertTrue(reloaded.snapshot().all { it.wire.contains("\"id\":\"frame-") })
    }

    @Test
    fun `the cap evicts the oldest key instead of growing forever`() {
        val box = outbox("capped.json", max = 3)
        box.load()
        box.park(entry("frame-1", "GUEST_STARTED|a"))
        box.park(entry("frame-2", "GUEST_STARTED|b"))
        box.park(entry("frame-3", "GUEST_STARTED|c"))
        box.park(entry("frame-4", "GUEST_STARTED|d"))
        assertEquals(listOf("frame-2", "frame-3", "frame-4"), box.snapshot().map { it.id })
    }
}
