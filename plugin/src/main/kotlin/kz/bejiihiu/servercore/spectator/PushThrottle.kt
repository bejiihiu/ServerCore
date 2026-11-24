package kz.bejiihiu.servercore.spectator

/**
 * pure push throttle: at most one throttled push per [intervalMs], the first
 * push always goes out immediately. full resyncs (viewer open, world change,
 * safety backstop) bypass the gate — they are rare and correctness beats
 * politeness there.
 *
 * not thread-safe by design; the flush loop is the only caller and runs
 * serialized on the global region scheduler. [clock] is injectable so tests
 * drive time by hand.
 */
class PushThrottle(
    private val intervalMs: Long,
    private val clock: () -> Long,
) {
    private var lastPushMs = Long.MIN_VALUE

    /** true when a throttled (diff) push may go out now; consuming a permit records it */
    fun tryAcquire(): Boolean {
        val now = clock()
        if (lastPushMs != Long.MIN_VALUE && now - lastPushMs < intervalMs) return false
        lastPushMs = now
        return true
    }

    /** record that a push went out (unthrottled full resync) without gating */
    fun markPushed() {
        lastPushMs = clock()
    }

    /** ms until the next throttled push is allowed; 0 when immediately eligible */
    fun timeUntilNextMs(): Long {
        if (lastPushMs == Long.MIN_VALUE) return 0
        return (intervalMs - (clock() - lastPushMs)).coerceAtLeast(0)
    }
}
