package kz.bejiihiu.servercore.websocket

import java.util.concurrent.ThreadLocalRandom

/**
 * reconnect delay schedule: exponential growth with a cap and +/-20% jitter so
 * a fleet of servers never reconnects in lockstep. attempt is 1-based.
 */
class ReconnectPolicy(
    private val initialMs: Long,
    private val maxMs: Long,
    /** injectable randomness for tests, defaults to the thread-local rng */
    private val jitterSource: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) {
    /** full-jitter band around the exponential base: 0.8x .. 1.2x */
    fun delayFor(attempt: Long): Long {
        val exp = (attempt - 1).coerceIn(0, 60)
        // a hand-edited config can put the cap below the initial delay, or at
        // zero: without this the first delay is longer than the "cap" and the
        // ladder then collapses to 1ms, i.e. a reconnect storm in both
        // directions. the ceiling is at least the initial delay, always > 0
        val ceiling = maxMs.coerceAtLeast(initialMs).coerceAtLeast(1)
        var base = initialMs.coerceIn(1, ceiling)
        repeat(exp.toInt()) {
            // clamp before doubling, base * 2 would overflow a long
            base = if (base > ceiling / 2) ceiling else base * 2
        }
        val factor = 0.8 + jitterSource() * 0.4
        return (base * factor).toLong().coerceAtLeast(1)
    }
}
