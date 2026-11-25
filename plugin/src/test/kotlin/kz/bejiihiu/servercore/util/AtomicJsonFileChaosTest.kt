package kz.bejiihiu.servercore.util

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kz.bejiihiu.servercore.testing.ManualExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertNotNull

@Serializable
private data class ChaosSample(val name: String, val n: Int)

/**
 * the write path every durable state file shares. the crash shapes a real host
 * produces: the process dies between the temp write and the move, two virtual
 * threads run their flushes in the wrong order, the target cannot be replaced
 * for a while. whatever happens, the file a reader finds is either the previous
 * good content or a complete newer one - never a torn document, and never a
 * silent rollback.
 */
class AtomicJsonFileChaosTest {

    @TempDir
    lateinit var dir: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(
        name: String = "state.json",
        io: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
        attempts: Int = 3,
        delayMs: Long = 100,
    ) = AtomicJsonFile(dir.resolve(name), ChaosSample.serializer(), io, json, attempts, delayMs)

    @Test
    fun `a crash between the temp write and the move leaves the previous file intact`() {
        file().writeNow(ChaosSample("old", 1))

        // the process died right after writing the temp file: the move never ran
        Files.writeString(dir.resolve("state.json.tmp"), """{"name":"torn","n":""")

        assertEquals(ChaosSample("old", 1), file().read(), "a reader never sees the torn temp file")

        // and the leftover temp file does not block the next save
        file().writeNow(ChaosSample("new", 2))
        assertEquals(ChaosSample("new", 2), file().read())
    }

    @Test
    fun `reverse-order async flushes always land the newest value`() {
        val manual = ManualExecutor()
        val target = file(io = manual)

        target.write(ChaosSample("first", 1))
        target.write(ChaosSample("second", 2))
        target.write(ChaosSample("third", 3))
        assertEquals(3, manual.queued())

        // a shut-down pool is not ordered: run the queued writes backwards
        manual.drainInReverse()

        assertEquals(ChaosSample("third", 3), file().read())
    }

    @Test
    fun `a synchronous flush is not rolled back by writes queued before it`() {
        val manual = ManualExecutor()
        val target = file(io = manual)

        target.write(ChaosSample("queued-1", 1))
        target.write(ChaosSample("queued-2", 2))
        target.writeNow(ChaosSample("final", 9))
        manual.drainInOrder()

        assertEquals(ChaosSample("final", 9), file().read())
    }

    @Test
    fun `a write that exhausts its retries keeps the previous content readable`() {
        val target = file()
        target.writeNow(ChaosSample("good", 1))

        // a directory squatting on the temp path makes every attempt fail on
        // every platform, so the write never lands
        Files.createDirectory(dir.resolve("state.json.tmp"))
        file(attempts = 2, delayMs = 1).writeNow(ChaosSample("lost", 2))

        assertEquals(ChaosSample("good", 1), file().read(), "the last good value is what a reader gets")
        assertTrue(Files.isDirectory(dir.resolve("state.json.tmp")), "the obstruction is still there")

        // once the obstruction is gone the next save works again
        Files.delete(dir.resolve("state.json.tmp"))
        file().writeNow(ChaosSample("recovered", 3))
        assertEquals(ChaosSample("recovered", 3), file().read())
    }

    @Test
    fun `an empty target is quarantined even when a valid temp file sits next to it`() {
        // a crash that happened after the temp file was moved but before the
        // bytes were flushed: empty target, valid temp
        Files.writeString(dir.resolve("state.json"), "")
        Files.writeString(dir.resolve("state.json.tmp"), """{"name":"intact","n":5}""")

        // the empty target is corrupt, so the store quarantines it and starts
        // clean - it must never adopt the temp file as truth
        assertEquals(null, file().read())
        assertFalse(Files.exists(dir.resolve("state.json")))
    }

    @Test
    fun `concurrent writers always leave one complete document, never a torn one`() {
        val pool = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        val target = file(io = pool)
        val values = (0 until 8).map { worker -> (0 until 25).map { ChaosSample("w$worker", it) } }
        try {
            values.mapIndexed { worker, mine ->
                pool.submit(Callable {
                    barrier.await()
                    mine.forEach { target.writeNow(it) }
                    worker
                })
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        val landed = assertNotNull(file().read(), "the file always parses after a write storm")
        assertTrue(
            values.flatten().contains(landed),
            "content is one of the written values, not a mix: $landed",
        )
        assertFalse(Files.exists(dir.resolve("state.json.tmp")), "no temp file is left behind")
    }

    @Test
    fun `a rejected io pool persists inline instead of throwing at the caller`() {
        val rejected = java.util.concurrent.Executor {
            throw java.util.concurrent.RejectedExecutionException("pool is gone")
        }
        val target = file(io = rejected)

        // the disable path: async saves are already impossible, the caller still
        // must not see an exception on its region thread
        target.write(ChaosSample("last", 1))

        assertEquals(ChaosSample("last", 1), file().read(), "the fallback wrote it synchronously")
    }
}
