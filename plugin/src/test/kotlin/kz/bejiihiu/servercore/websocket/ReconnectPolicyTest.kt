package kz.bejiihiu.servercore.websocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconnectPolicyTest {

    private fun policy(jitter: Double) = ReconnectPolicy(1_000, 60_000, jitterSource = { jitter })

    @Test
    fun `first attempt is the initial delay with jitter`() {
        assertEquals(800, policy(0.0).delayFor(1))
        assertEquals(1_200, policy(1.0).delayFor(1))
        assertEquals(1_000, policy(0.5).delayFor(1))
    }

    @Test
    fun `delay doubles exponentially up to the cap`() {
        // jitter 0.5 keeps the base value untouched
        assertEquals(2_000, policy(0.5).delayFor(2))
        assertEquals(4_000, policy(0.5).delayFor(3))
        assertEquals(8_000, policy(0.5).delayFor(4))
    }

    @Test
    fun `the cap stops the growth`() {
        assertEquals(60_000, policy(0.5).delayFor(20))
        assertEquals(60_000, policy(0.5).delayFor(100))
    }

    @Test
    fun `jitter stays inside the band even at the cap`() {
        val p = policy(1.0)
        assertEquals(72_000, p.delayFor(20))
        // never below the jittered initial either
        assertTrue(p.delayFor(1) >= 800)
    }

    @Test
    fun `huge attempt counts do not overflow into negative delays`() {
        val p = ReconnectPolicy(1_000, 60_000, jitterSource = { 0.5 })
        repeat(200) { attempt ->
            val delay = p.delayFor(attempt.toLong() + 1)
            assertTrue(delay in 1..72_000, "attempt $attempt produced $delay")
        }
    }

    @Test
    fun `default jitter source stays in the band`() {
        val p = ReconnectPolicy(1_000, 60_000)
        repeat(100) {
            val delay = p.delayFor(1)
            assertTrue(delay in 800..1_200, "delay $delay outside the jitter band")
        }
    }
}
