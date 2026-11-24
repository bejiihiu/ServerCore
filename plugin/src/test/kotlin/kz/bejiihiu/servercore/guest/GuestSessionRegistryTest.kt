package kz.bejiihiu.servercore.guest

import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * the session store races, run for real on a concurrent executor: double
 * joins must not double-create sessions or double-burn the budget, and a
 * telegram approval must beat an in-flight guest start.
 */
class GuestSessionRegistryTest {

    private fun snapshot(sessionId: String) = GuestSession(
        sessionId, "player",
        PlayerSnapshot("world", 0.0, 64.0, 0.0, 0f.toDouble(), 0f.toDouble(), "SURVIVAL", false, 0.1, 0.2),
    )

    @Test
    fun `two simultaneous joins create exactly one session`() {
        val registry = GuestSessionRegistry()
        val uuid = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        val wins = AtomicInteger()

        val results = (1..8).map {
            pool.submit(Callable {
                barrier.await()
                registry.start(uuid) { snapshot("s-$it") } != null
            })
        }.map { it.get() }

        results.forEach { won -> if (won) wins.incrementAndGet() }
        pool.shutdown()

        assertEquals(1, wins.get(), "exactly one join wins the session start")
        assertEquals(1, registry.all().size)
        val winner = registry.get(uuid)!!
        assertNull(registry.start(uuid) { snapshot("should-not-run") }, "a settled session never restarts")
        assertEquals(winner.sessionId, registry.get(uuid)?.sessionId)
    }

    @Test
    fun `the counter burn inside create runs exactly once across racing joins`() {
        val registry = GuestSessionRegistry()
        val uuid = UUID.randomUUID()
        val burns = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)

        (1..8).map {
            pool.submit(Callable {
                barrier.await()
                registry.start(uuid) {
                    burns.incrementAndGet()
                    snapshot("s")
                }
            })
        }.forEach { it.get() }
        pool.shutdown()

        assertEquals(1, burns.get(), "the budget burn is coupled to the single winning start")
    }

    @Test
    fun `approval latch blocks a guest start that races it`() {
        val registry = GuestSessionRegistry()
        val uuid = UUID.randomUUID()
        registry.approve(uuid)

        assertNull(registry.start(uuid) { snapshot("late") }, "an approved uuid never becomes a guest")
        assertFalse(registry.contains(uuid))
        assertTrue(registry.isAuthorized(uuid))
    }

    @Test
    fun `approval beats a start already in flight on another thread`() {
        val registry = GuestSessionRegistry()
        val uuid = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        val burns = AtomicInteger()

        val guestJob = pool.submit(Callable {
            barrier.await()
            // slow create: the approval lands while the guest start is mid-way
            registry.start(uuid) {
                Thread.sleep(50)
                burns.incrementAndGet()
                snapshot("s")
            }
        })
        val approveJob = pool.submit(Callable {
            barrier.await()
            registry.approve(uuid)
            true
        })

        guestJob.get()
        approveJob.get()
        pool.shutdown()

        // either the start predates the approval (wins, one burn) or the latch
        // blocks it entirely (zero burns) — never two sessions, never a start
        // that survives the approval
        if (registry.contains(uuid)) {
            assertEquals(1, burns.get())
        } else {
            assertEquals(0, burns.get())
            assertTrue(registry.isAuthorized(uuid))
        }
    }

    @Test
    fun `different uuids start independently`() {
        val registry = GuestSessionRegistry()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        assertEquals("s-a", registry.start(a) { snapshot("s-a") }?.sessionId)
        assertEquals("s-b", registry.start(b) { snapshot("s-b") }?.sessionId)
        assertEquals(2, registry.all().size)

        registry.remove(a)
        assertFalse(registry.contains(a))
        assertEquals("s-a2", registry.start(a) { snapshot("s-a2") }?.sessionId, "a removed slot can start again")
    }

    @Test
    fun `clearAuthorization lets a fresh guest start again`() {
        val registry = GuestSessionRegistry()
        val uuid = UUID.randomUUID()
        registry.approve(uuid)
        registry.clearAuthorization(uuid)

        assertEquals("s", registry.start(uuid) { snapshot("s") }?.sessionId)
    }

    @Test
    fun `a guarded remove never tears down a session started after the check`() {
        val registry = GuestSessionRegistry()
        val uuid = UUID.randomUUID()
        registry.start(uuid) { snapshot("old") }

        // the quit's identity check passed, but a same-uuid rejoin took the
        // slot over before the removal: the fresh session must survive
        assertNull(registry.remove(uuid) { false })
        assertTrue(registry.contains(uuid))
        assertEquals("old", registry.get(uuid)?.sessionId)
    }

    @Test
    fun `a guarded remove still works when the identity check holds`() {
        val registry = GuestSessionRegistry()
        val uuid = UUID.randomUUID()
        registry.start(uuid) { snapshot("old") }

        assertEquals("old", registry.remove(uuid) { true }?.sessionId)
        assertFalse(registry.contains(uuid))
    }
}
