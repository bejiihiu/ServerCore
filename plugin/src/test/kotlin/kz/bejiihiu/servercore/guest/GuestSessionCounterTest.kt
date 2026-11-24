package kz.bejiihiu.servercore.guest

import java.util.UUID
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GuestSessionCounterTest {

    @TempDir
    lateinit var dir: Path

    private fun newCounter() = GuestSessionCounter(dir.resolve("counts.json"), Runnable::run)

    @Test
    fun `counts increment and persist across reload`() {
        val counter = newCounter()
        val uuid = UUID.randomUUID()
        repeat(3) { counter.increment(uuid) }
        counter.shutdown()

        val reloaded = GuestSessionCounter(dir.resolve("counts.json"), Runnable::run)
        reloaded.load()
        assertEquals(3, reloaded.count(uuid))
    }

    @Test
    fun `authorization resets the budget`() {
        val counter = newCounter()
        val uuid = UUID.randomUUID()
        repeat(3) { counter.increment(uuid) }
        counter.reset(uuid)
        assertEquals(0, counter.count(uuid))
    }

    @Test
    fun `limit boundary behaves like the product decision says`() {
        val counter = newCounter()
        val uuid = UUID.randomUUID()
        val limit = 3
        repeat(limit) { counter.increment(uuid) }
        // sessions 1..3 allowed
        assertTrue(counter.count(uuid) < limit + 1)
        // the fourth join is over the line
        counter.increment(uuid)
        assertFalse(counter.count(uuid) <= limit)
    }

    @Test
    fun `a corrupt counter file resets with a warning instead of boot-failing`() {
        val path = dir.resolve("counts.json")
        java.nio.file.Files.writeString(path, "hand-edited garbage {{{")

        val counter = newCounter()
        counter.load() // must not throw

        assertEquals(0, counter.count(UUID.randomUUID()))
        // the broken bytes are quarantined next to where the file was
        val quarantined = java.nio.file.Files.list(dir).use { stream ->
            stream.anyMatch { it.fileName.toString().startsWith("counts.json.corrupt-") }
        }
        assertTrue(quarantined)
    }

    @Test
    fun `hand-edited nonsense values are clamped, foreign keys ignored`() {
        val uuid = UUID.randomUUID()
        val other = UUID.randomUUID()
        val path = dir.resolve("counts.json")
        java.nio.file.Files.writeString(
            path,
            """{"version":1,"counts":{"$uuid":-5,"$other":42,"not-a-uuid":7,"${UUID.randomUUID()}":999999999}}""",
        )

        val counter = newCounter()
        counter.load()

        assertEquals(0, counter.count(uuid), "a negative hand edit counts as nothing")
        assertEquals(42, counter.count(other))
    }

    @Test
    fun `concurrent increments from racing joins never lose a count`() {
        val counter = newCounter()
        val uuid = UUID.randomUUID()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val barrier = java.util.concurrent.CyclicBarrier(8)

        (1..8).map {
            pool.submit(java.util.concurrent.Callable {
                barrier.await()
                counter.increment(uuid)
            })
        }.forEach { it.get() }
        pool.shutdown()

        assertEquals(8, counter.count(uuid))
        counter.shutdown()
        val reloaded = GuestSessionCounter(dir.resolve("counts.json"), Runnable::run)
        reloaded.load()
        assertEquals(8, reloaded.count(uuid), "the durable file kept every increment too")
    }
}
