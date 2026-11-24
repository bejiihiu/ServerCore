package kz.bejiihiu.servercore.spectator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushThrottleTest {

    private var now = 0L

    private fun throttle(intervalMs: Long = 500) = PushThrottle(intervalMs) { now }

    @Test
    fun `first push always goes out immediately`() {
        assertTrue(throttle().tryAcquire())
    }

    @Test
    fun `push inside the interval is rejected and stays pending`() {
        val t = throttle()
        now = 1000
        assertTrue(t.tryAcquire())
        now = 1200
        assertFalse(t.tryAcquire())
        assertEquals(300, t.timeUntilNextMs())
        now = 1500 // exactly at the interval boundary: allowed again
        assertTrue(t.tryAcquire())
    }

    @Test
    fun `time until next is zero before any push`() {
        assertEquals(0, throttle().timeUntilNextMs())
    }

    @Test
    fun `marking a full resync also arms the throttle`() {
        val t = throttle()
        now = 500
        t.markPushed()
        now = 700
        assertFalse(t.tryAcquire())
        now = 1000
        assertTrue(t.tryAcquire())
    }

    @Test
    fun `independent throttles do not interfere`() {
        now = 0
        val a = throttle()
        val b = throttle()
        assertTrue(a.tryAcquire())
        assertTrue(b.tryAcquire())
    }
}
