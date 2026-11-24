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
}
