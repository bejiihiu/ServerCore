package kz.bejiihiu.servercore.websocket

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * property-style sweep over the reconnect ladder: random knobs, random attempt
 * counts, random jitter. whatever the config says, a delay is a positive
 * number, never grows past the ceiling, never shrinks as attempts pile up and
 * always sits inside the +/-20% jitter band of its own exponential base.
 */
class ReconnectPolicyPropertyTest {

    private fun policy(initial: Long, max: Long, jitter: Double) =
        ReconnectPolicy(initial, max, jitterSource = { jitter })

    /** the base the ladder must have arrived at for the given attempt */
    private fun expectedBase(initialMs: Long, maxMs: Long, attempt: Long): Long {
        val ceiling = maxOf(maxMs, initialMs, 1L)
        var base = initialMs.coerceIn(1, ceiling)
        repeat((attempt - 1).coerceIn(0, 60).toInt()) {
            base = if (base > ceiling / 2) ceiling else base * 2
        }
        return base
    }

    @Test
    fun `every delay sits in the jitter band of its own base`() {
        val random = Random(0xC0FFEE)
        val configs = listOf(
            1_000L to 60_000L,
            250L to 1_000L,
            60_000L to 60_000L,
            // misconfigured on purpose: cap below the initial delay, cap at 0
            60_000L to 1_000L,
            5_000L to 0L,
            0L to 0L,
            Long.MAX_VALUE to 60_000L,
            Long.MAX_VALUE to Long.MAX_VALUE,
        )
        configs.forEach { (initial, max) ->
            repeat(200) {
                val attempt = random.nextLong().let { raw -> (raw % 400 + 401) } // 1..800, can go past the clamp
                val jitter = random.nextDouble()
                val delay = policy(initial, max, jitter).delayFor(attempt)
                val base = expectedBase(initial, max, attempt)
                val low = (base.toDouble() * 0.8).toLong()
                val high = (base.toDouble() * 1.2).toLong()
                assertTrue(
                    delay in low.coerceAtLeast(1)..high,
                    "initial=$initial max=$max attempt=$attempt jitter=$jitter -> $delay outside $low..$high",
                )
            }
        }
    }

    @Test
    fun `the ladder never goes down while attempts go up`() {
        val random = Random(0xBADF00D)
        repeat(100) {
            val initial = 1L + random.nextInt(120_000)
            val max = initial + random.nextInt(600_000)
            val jitter = random.nextDouble()
            val policy = policy(initial, max, jitter)
            var previous = 0L
            (1L..80L).forEach { attempt ->
                val delay = policy.delayFor(attempt)
                assertTrue(delay >= previous, "attempt $attempt went down: $delay < $previous")
                assertTrue(delay >= 1)
                assertTrue(delay <= (max.toDouble() * 1.2).toLong() + 1, "attempt $attempt broke the cap: $delay")
                previous = delay
            }
        }
    }

    @Test
    fun `a cap below the initial delay never collapses the ladder into a hammer loop`() {
        // hand-edited config: initial 60s, cap 1s. the old ladder returned 60s
        // first and then collapsed to 1ms on the next attempt - a storm in both
        // directions. a cap below the configured initial delay is nonsense, so
        // the initial delay wins as the floor instead of the 1ms collapse
        val p = policy(initial = 60_000, max = 1_000, jitter = 0.5)
        (1L..40L).forEach { attempt ->
            val delay = p.delayFor(attempt)
            assertTrue(delay in 48_000..72_000, "attempt $attempt produced $delay")
        }
    }

    @Test
    fun `a zero cap cannot turn reconnects into a 1ms hammer loop`() {
        val p = policy(initial = 2_000, max = 0, jitter = 0.5)
        assertTrue(p.delayFor(1) >= 2_000, "attempt one keeps the initial delay")
        assertTrue(p.delayFor(50) >= 2_000, "the ladder cannot collapse below the initial delay")
        assertTrue(p.delayFor(50) <= 2_400)
    }

    @Test
    fun `absurd base values never overflow into negative or zero delays`() {
        listOf(
            Long.MAX_VALUE to Long.MAX_VALUE,
            Long.MAX_VALUE to 1L,
            Long.MAX_VALUE - 1 to Long.MAX_VALUE,
        ).forEach { (initial, max) ->
            val p = policy(initial, max, 1.0)
            (0L..200L).forEach { attempt ->
                val delay = p.delayFor(attempt)
                assertTrue(delay >= 1, "attempt $attempt produced $delay")
            }
        }
    }

    @Test
    fun `attempt zero and negative attempts reuse the initial delay`() {
        val p = policy(1_000, 60_000, 0.5)
        assertEquals(1_000, p.delayFor(0))
        assertEquals(1_000, p.delayFor(-7))
        assertEquals(1_000, p.delayFor(1))
        assertEquals(2_000, p.delayFor(2))
    }

    @Test
    fun `the jitter band is exactly 0 point 8 to 1 point 2 of the base`() {
        val initial = 1_000L
        val max = 60_000L
        assertEquals(800, policy(initial, max, 0.0).delayFor(1))
        assertEquals(1_000, policy(initial, max, 0.5).delayFor(1))
        assertEquals(1_200, policy(initial, max, 1.0).delayFor(1))
        // at the cap the band scales with the cap, not with the initial delay
        assertEquals(48_000, policy(initial, max, 0.0).delayFor(60))
        assertEquals(72_000, policy(initial, max, 1.0).delayFor(60))
    }
}
