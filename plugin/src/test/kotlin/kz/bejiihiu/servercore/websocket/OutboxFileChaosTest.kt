package kz.bejiihiu.servercore.websocket

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kz.bejiihiu.servercore.testing.ManualExecutor
import org.junit.jupiter.api.io.TempDir

/**
 * the durable outbox is the only thing standing between a crash and a lost
 * guest lifecycle event, and its file is written by whoever the io pool feels
 * like. this suite feeds it the states a real crash produces - garbage,
 * half-written json, a truncated array, a rejected executor - and then asks it
 * to behave: boot clean, keep exactly one copy per idempotency key, never let a
 * stale flush roll back a newer one, and replay each frame exactly once after
 * the restart.
 */
class OutboxFileChaosTest {

    @TempDir
    lateinit var dir: Path

    private fun outbox(name: String = "outbox.json", io: Executor = Executor { it.run() }, max: Int = 500) =
        DurableOutbox(dir.resolve(name), io, maxEntries = max)

    private fun entry(id: String, key: String, type: String = "GUEST_STARTED", at: Long = 1_000) =
        ParkedFrame(id = id, key = key, type = type, wire = """{"id":"$id"}""", enqueuedAt = at)

    private fun quarantined(prefix: String): List<Path> =
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("$prefix.corrupt-") }.toList()
        }

    @Test
    fun `garbage in the file is quarantined and the outbox boots empty`() {
        Files.writeString(dir.resolve("outbox.json"), "definitely not json {{{")

        val box = outbox()
        box.load() // must not throw

        assertEquals(0, box.size())
        assertEquals(1, quarantined("outbox.json").size)
        // and a fresh park persists over the quarantined slot
        box.park(entry("after-corrupt", "GUEST_STARTED|guest-a"))
        val reloaded = outbox()
        reloaded.load()
        assertEquals(listOf("after-corrupt"), reloaded.snapshot().map { it.id })
    }

    @Test
    fun `a truncated array is treated as corrupt, never as an empty outbox`() {
        // the classic crash shape: the encoder got as far as the second frame
        Files.writeString(
            dir.resolve("outbox.json"),
            """[{"id":"frame-1","key":"GUEST_STARTED|a","type":"GUEST_STARTED","wire":"{}","enqueuedAt":1},""",
        )

        val box = outbox()
        box.load()

        assertEquals(0, box.size())
        assertEquals(1, quarantined("outbox.json").size, "the half-written bytes stay for inspection")
    }

    @Test
    fun `a partially written object with the closing bracket missing is corrupt too`() {
        Files.writeString(
            dir.resolve("outbox.json"),
            """[{"id":"frame-1","key":"GUEST_STARTED|a","type":"GUEST_STARTED","wire":"{\"x\":1}"}""",
        )

        val box = outbox()
        box.load()

        assertEquals(0, box.size())
        assertEquals(1, quarantined("outbox.json").size)
    }

    @Test
    fun `shapes that parse but cannot be frames are rejected instead of half-loaded`() {
        val shapes = listOf(
            "null",
            "{}",
            """[null]""",
            """[1,2,3]""",
            """[{"id":"only-an-id"}]""",
            """{"entries":[]}""",
        )
        shapes.forEachIndexed { index, shape ->
            val name = "shape-$index.json"
            Files.writeString(dir.resolve(name), shape)
            val box = outbox(name)
            box.load()
            assertEquals(0, box.size(), "shape #$index must not load as state")
            assertFalse(Files.exists(dir.resolve(name)), "shape #$index is quarantined")
        }
    }

    @Test
    fun `an empty array is a valid empty outbox, not a corrupt file`() {
        Files.writeString(dir.resolve("outbox.json"), "[]")

        val box = outbox()
        box.load()

        assertEquals(0, box.size())
        assertTrue(Files.exists(dir.resolve("outbox.json")), "a valid file is left alone")
        assertTrue(quarantined("outbox.json").isEmpty())
    }

    @Test
    fun `a file corrupted between two loads resets the in-memory set without crashing`() {
        val box = outbox()
        box.load()
        box.park(entry("frame-1", "GUEST_STARTED|a"))
        box.park(entry("frame-2", "GUEST_STARTED|b"))

        // somebody hand-edits the live file while the plugin runs
        Files.writeString(dir.resolve("outbox.json"), "\u0000\u0001 garbage")

        box.load()
        assertEquals(0, box.size(), "the next boot must not resurrect unreadable state")
        assertTrue(box.snapshot().isEmpty())
    }

    @Test
    fun `one idempotency key stays one frame across a restart`() {
        outbox().apply {
            load()
            park(entry("frame-old", "GUEST_STARTED|guest-a", at = 1))
            park(entry("frame-new", "GUEST_STARTED|guest-a", at = 2))
            flushNow()
        }

        val reloaded = outbox()
        reloaded.load()

        assertEquals(listOf("frame-new"), reloaded.snapshot().map { it.id }, "the newest frame of a key wins")
        assertEquals(1, reloaded.size())
    }

    @Test
    fun `the cap evicts oldest-first and the survivors are the ones on disk`() {
        val box = outbox(max = 3)
        box.load()
        (1..6).forEach { box.park(entry("frame-$it", "GUEST_STARTED|guest-$it", at = it.toLong())) }
        box.flushNow()

        val reloaded = outbox(max = 3)
        reloaded.load()
        assertEquals(listOf("frame-4", "frame-5", "frame-6"), reloaded.snapshot().map { it.id })
    }

    @Test
    fun `a rejected io executor never throws at the caller and the park still lands`() {
        val dead = Executor { throw RejectedExecutionException("io pool already shut down") }
        val box = outbox(io = dead)
        box.load()

        // this is the disable path: a player quit parks the frame after the io
        // pool is gone, and an exception here would unwind the quit handler
        box.park(entry("last-frame", "GUEST_ENDED|guest-a", type = "GUEST_ENDED"))

        assertEquals(1, box.size())
        val reloaded = outbox()
        reloaded.load()
        assertEquals(listOf("last-frame"), reloaded.snapshot().map { it.id }, "the inline fallback wrote it")
    }

    @Test
    fun `a stale queued flush cannot roll the file back to older content`() {
        val manual = ManualExecutor()
        val box = outbox(io = manual)
        box.load()

        box.park(entry("frame-a", "GUEST_STARTED|a")) // queued, seq 1
        box.park(entry("frame-b", "GUEST_STARTED|b")) // queued, seq 2
        box.unpark("frame-a") // queued, seq 3 - the newest truth is "only b"
        assertEquals(3, manual.queued())

        // virtual threads are not ordered, so the io pool can run them backwards
        manual.drainInReverse()

        val reloaded = outbox()
        reloaded.load()
        assertEquals(listOf("frame-b"), reloaded.snapshot().map { it.id })
        assertEquals(box.snapshot().map { it.id }, reloaded.snapshot().map { it.id }, "disk agrees with memory")
    }

    @Test
    fun `a shutdown flush is never overwritten by a flush queued before it`() {
        val manual = ManualExecutor()
        val box = outbox(io = manual)
        box.load()

        box.park(entry("frame-a", "GUEST_STARTED|a")) // queued, seq 1
        box.unpark("frame-a") // queued, seq 2
        box.flushNow() // blocking, seq 3, empty
        manual.drainInOrder() // the queued pair must be skipped, they are older

        val reloaded = outbox()
        reloaded.load()
        assertEquals(0, reloaded.size())
    }

    @Test
    fun `concurrent parks and unparks end with a file that matches memory`() {
        val box = outbox(max = 64)
        box.load()
        val pool = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        try {
            (0 until 8).map { worker ->
                pool.submit(Callable {
                    barrier.await()
                    repeat(100) { step ->
                        val key = "GUEST_STARTED|guest-${worker * 100 + step}"
                        val id = "chaos-$worker-$step"
                        box.park(entry(id, key, at = step.toLong()))
                        // a competing holder of the same key replaces the frame
                        if (step % 7 == 0) box.park(entry("$id-replaced", key, at = step.toLong() + 1))
                        if (step % 5 == 0) box.unpark(id)
                    }
                    true
                })
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        box.flushNow()

        val reloaded = outbox(max = 64)
        reloaded.load()

        assertEquals(box.snapshot().map { it.id }, reloaded.snapshot().map { it.id }, "memory and disk agree")
        assertEquals(box.size(), reloaded.size())
        assertEquals(reloaded.snapshot().map { it.key }.distinct().size, reloaded.size(), "one row per key, no dupes")
    }
}
