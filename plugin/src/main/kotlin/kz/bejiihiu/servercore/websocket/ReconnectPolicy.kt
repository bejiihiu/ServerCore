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
        var base = initialMs.coerceAtLeast(1)
        repeat(exp.toInt()) {
            // clamp while doubling, 2^62 * initial would overflow a long
            base = minOf(base * 2, maxMs)
        }
        val factor = 0.8 + jitterSource() * 0.4
        return (base * factor).toLong().coerceAtLeast(1)
    }
}
