package kz.bejiihiu.servercore.websocket

import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * the dedupe window is driven by a wall clock that nothing here controls: ntp
 * steps, a virtualised host that jumps, a system clock set backwards by hand.
 * a redelivery must be swallowed inside the window and accepted after it, in
 * either direction of the jump, and the capacity valve must forget old keys
 * without ever reporting a fresh frame as a duplicate.
 */
class InboundDedupeClockTest {

    @Test
    fun `the ttl boundary is inclusive, one millisecond later it is not`() {
        val dedupe = InboundDedupe(ttlMs = 10_000)
        assertFalse(dedupe.isDuplicate("frame", now = 1_000))
        assertTrue(dedupe.isDuplicate("frame", now = 10_999), "just inside the window")
        assertTrue(dedupe.isDuplicate("frame", now = 11_000), "exactly ttl is still inside")
        assertFalse(dedupe.isDuplicate("frame", now = 11_001), "one past the window is a new frame")
        assertTrue(dedupe.isDuplicate("frame", now = 11_002), "and the re-accept restarted the window")
    }

    @Test
    fun `a clock jump backwards keeps the window instead of forgetting frames`() {
        val dedupe = InboundDedupe(ttlMs = 10_000)
        assertFalse(dedupe.isDuplicate("frame", now = 1_000_000))

        // ntp steps back an hour: a naive "now < seenAt" purge would treat the
        // entry as expired (negative age) or crash on it; the frame is still
        // inside the wall-clock window of its arrival
        assertTrue(dedupe.isDuplicate("frame", now = 500_000))
        assertTrue(dedupe.isDuplicate("frame", now = 990_000))
        // and once the clock catches up past the original ttl it expires again
        assertFalse(dedupe.isDuplicate("frame", now = 1_010_001))
    }

    @Test
    fun `a clock jump forward expires the window at once and never re-blocks the key`() {
        val dedupe = InboundDedupe(ttlMs = 10_000)
        assertFalse(dedupe.isDuplicate("frame", now = 1_000))

        // a year of drift: everything seen before now is older than the ttl
        assertFalse(dedupe.isDuplicate("frame", now = 1_000 + 31_536_000_000L))
        assertTrue(dedupe.isDuplicate("frame", now = 1_000 + 31_536_000_000L + 1))
    }

    @Test
    fun `a backwards jump after a forward one cannot resurrect a purged window`() {
        val dedupe = InboundDedupe(ttlMs = 1_000)
        assertFalse(dedupe.isDuplicate("frame", now = 100_000))
        // clock leaps forward, the entry is purged
        assertFalse(dedupe.isDuplicate("other", now = 500_000))
        // clock leaps back: the purged key must stay forgotten (accepting a
        // frame twice is the documented degradation, blocking a fresh one is not)
        assertFalse(dedupe.isDuplicate("frame", now = 100_000))
    }

    @Test
    fun `the capacity valve forgets the oldest key and never reports it as a duplicate`() {
        val dedupe = InboundDedupe(ttlMs = 600_000, maxKeys = 3)
        // four unique frames against a three-key valve: the trim runs on the
        // next call, oldest insertion first
        listOf("a", "b", "c", "d").forEach { assertFalse(dedupe.isDuplicate(it, now = 1_000)) }

        // the three newest keys are still deduped after the trim
        assertTrue(dedupe.isDuplicate("d", now = 1_001))
        assertTrue(dedupe.isDuplicate("c", now = 1_002))
        assertTrue(dedupe.isDuplicate("b", now = 1_003))

        // the evicted key is unknown again: it is accepted, not blocked
        assertFalse(dedupe.isDuplicate("a", now = 1_004), "an evicted id must not come back as a duplicate")
        // taking its slot back trimmed the next-oldest key, so the valve stays bounded
        assertFalse(dedupe.isDuplicate("b", now = 1_005), "the oldest live key was trimmed to make room")
        assertTrue(dedupe.isDuplicate("a", now = 1_006), "the re-accepted id starts a fresh window")
    }

    @Test
    fun `the cache stays bounded across a long burst of unique frames`() {
        val dedupe = InboundDedupe(ttlMs = 60_000, maxKeys = 50)
        repeat(5_000) { dedupe.isDuplicate("burst-$it", now = 1_000L + it) }
        // the newest frames are still remembered
        assertTrue(dedupe.isDuplicate("burst-4999", now = 6_000))
        // and nothing in the burst leaves it stuck claiming everything is new
        assertFalse(dedupe.isDuplicate("brand-new", now = 6_001))
    }

    @Test
    fun `racing redeliveries of one frame are processed exactly once`() {
        val dedupe = InboundDedupe(ttlMs = 60_000)
        val pool = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        val accepted = AtomicInteger()
        try {
            (0 until 8).map {
                pool.submit(Callable {
                    barrier.await()
                    repeat(500) {
                        if (!dedupe.isDuplicate("AUTH_APPROVED|uuid|session-1", now = 1_000)) {
                            accepted.incrementAndGet()
                        }
                    }
                })
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(1, accepted.get(), "one delivery wins, every redelivery is swallowed")
    }

    @Test
    fun `different keys under concurrency each get exactly one winner`() {
        val dedupe = InboundDedupe(ttlMs = 60_000)
        val pool = Executors.newFixedThreadPool(6)
        val barrier = CyclicBarrier(6)
        val wins = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        try {
            (0 until 6).map { worker ->
                pool.submit(Callable {
                    barrier.await()
                    repeat(200) { step ->
                        val key = "key-${(worker + step) % 40}"
                        if (!dedupe.isDuplicate(key, now = 1_000)) {
                            wins.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                        }
                    }
                })
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        wins.forEach { (key, count) -> assertEquals(1, count.get(), "key $key won $count times") }
    }
}
