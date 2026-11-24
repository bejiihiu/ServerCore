package kz.bejiihiu.servercore.sync

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * entity id stability: server-side entity ids get reused for new spawns after
 * a despawn. the tracker must make sure a stale cached identity can never be
 * confused with the new owner of that id.
 */
class EntityIdentityTrackerTest {

    private val tracker = EntityIdentityTracker()

    @Test
    fun `fresh registration gets generation one and no evictions`() {
        val a = UUID.randomUUID()

        val reg = tracker.register(a, 42)

        assertEquals(EntityIdentity(a, 42, 1L), reg.identity)
        assertTrue(reg.evicted.isEmpty())
        assertEquals(EntityIdentity(a, 42, 1L), tracker.resolve(a, 42))
    }

    @Test
    fun `unchanged re-registration is idempotent and does not bump the generation`() {
        val a = UUID.randomUUID()
        val first = tracker.register(a, 42)

        val second = tracker.register(a, 42)

        assertEquals(first.identity, second.identity)
        assertEquals(1L, second.identity.generation)
        assertTrue(second.evicted.isEmpty())
    }

    @Test
    fun `id reuse by a different entity evicts the stale owner`() {
        val old = UUID.randomUUID()
        val neu = UUID.randomUUID()
        tracker.register(old, 42)
        val cachedStale = tracker.current(old)!!

        // no despawn call in between — exactly the missed-event case the
        // generation counters exist for
        val reg = tracker.register(neu, 42)

        assertEquals(listOf(EntityIdentity(old, 42, 1L)), reg.evicted)
        assertNull(tracker.current(old))
        assertFalse(tracker.isLive(cachedStale))
        assertEquals(EntityIdentity(neu, 42, 1L), tracker.resolve(neu, 42))
    }

    @Test
    fun `same uuid rebinding to a new id evicts the old id binding`() {
        val p = UUID.randomUUID()
        tracker.register(p, 10)

        val reg = tracker.register(p, 11)

        assertEquals(listOf(EntityIdentity(p, 10, 1L)), reg.evicted)
        assertEquals(EntityIdentity(p, 11, 2L), reg.identity)
        assertNull(tracker.resolve(p, 10))
        assertEquals(EntityIdentity(p, 11, 2L), tracker.resolve(p, 11))
    }

    @Test
    fun `despawn frees the binding and the id`() {
        val a = UUID.randomUUID()
        tracker.register(a, 7)

        tracker.despawn(a)

        assertNull(tracker.current(a))
        assertEquals(0, tracker.trackedCount())

        // a new entity can now claim the id without any eviction noise
        val b = UUID.randomUUID()
        val reg = tracker.register(b, 7)
        assertTrue(reg.evicted.isEmpty())
    }

    @Test
    fun `generations keep counting across repeated rebinds`() {
        val p = UUID.randomUUID()
        tracker.register(p, 1)
        tracker.register(p, 2)
        val third = tracker.register(p, 3)

        assertEquals(3L, third.identity.generation)
        // evictions are per-registration: this one killed the (p, 2) binding
        assertEquals(1, third.evicted.size)
    }
}
