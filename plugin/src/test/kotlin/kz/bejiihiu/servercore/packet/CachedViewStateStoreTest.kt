package kz.bejiihiu.servercore.packet

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CachedViewStateStoreTest {

    private val viewer = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val target = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val other = UUID.fromString("00000000-0000-0000-0000-000000000003")

    /** controllable clock, no sleeping in tests */
    private class FakeClock(var millis: Long = 0) {
        operator fun invoke(): Long = millis
        fun advance(by: Long) { millis += by }
    }

    private fun store(
        clock: FakeClock,
        ttl: Long = 60_000,
        maxViewers: Int = 16,
        maxPerViewer: Int = 16,
    ) = CachedViewStateStore(ttl, maxViewers, maxPerViewer, { clock() })

    @Test
    fun `entries expire after the ttl and stay expired`() {
        val clock = FakeClock()
        val store = store(clock, ttl = 100)
        store.set(viewer, target, EntityViewState.HIDDEN)

        clock.advance(99)
        assertEquals(EntityViewState.HIDDEN, store.get(viewer, target))
        // the read refreshed the entry, so the next span starts from here
        clock.advance(101)
        assertNull(store.get(viewer, target))
    }

    @Test
    fun `per-viewer capacity evicts the stalest pair first`() {
        val clock = FakeClock()
        val store = store(clock, maxPerViewer = 2)
        store.set(viewer, target, EntityViewState.VISIBLE)
        clock.advance(10)
        store.set(viewer, other, EntityViewState.DISGUISED)
        clock.advance(10)
        val third = UUID.randomUUID()
        store.set(viewer, third, EntityViewState.HIDDEN)

        // target was touched first, it is the one that goes
        assertNull(store.get(viewer, target))
        assertEquals(EntityViewState.DISGUISED, store.get(viewer, other))
        assertEquals(EntityViewState.HIDDEN, store.get(viewer, third))
    }

    @Test
    fun `a read keeps a hot pair alive past its neighbours`() {
        val clock = FakeClock()
        val store = store(clock, maxPerViewer = 2)
        store.set(viewer, target, EntityViewState.VISIBLE) // t=0
        clock.advance(10)
        store.set(viewer, other, EntityViewState.DISGUISED) // t=10
        clock.advance(10)
        store.get(viewer, target) // t=20, target is now the youngest-but-one
        clock.advance(1)
        val third = UUID.randomUUID()
        store.set(viewer, third, EntityViewState.HIDDEN) // t=21

        assertNull(store.get(viewer, other), "untouched pair is the one evicted")
        assertEquals(EntityViewState.VISIBLE, store.get(viewer, target))
        assertEquals(EntityViewState.HIDDEN, store.get(viewer, third))
    }

    @Test
    fun `viewer capacity evicts the whole stalest viewer map`() {
        val clock = FakeClock()
        val store = store(clock, maxViewers = 2)
        store.set(viewer, target, EntityViewState.VISIBLE)
        clock.advance(10)
        store.set(other, target, EntityViewState.DISGUISED)
        clock.advance(10)
        val newest = UUID.randomUUID()
        store.set(newest, target, EntityViewState.HIDDEN)

        assertNull(store.get(viewer, target), "idlest viewer's state is evicted wholesale")
        assertEquals(EntityViewState.DISGUISED, store.get(other, target))
        assertEquals(EntityViewState.HIDDEN, store.get(newest, target))
        assertEquals(2, store.size())
    }

    @Test
    fun `removeTarget and removeViewer behave independently`() {
        val clock = FakeClock()
        val store = store(clock)
        store.set(viewer, target, EntityViewState.HIDDEN)
        store.set(other, target, EntityViewState.VISIBLE)

        // wiping the target takes every viewer's pair with it
        store.removeTarget(target)
        assertEquals(0, store.size())

        // wiping one viewer leaves the other viewer's pairs alone
        store.set(viewer, target, EntityViewState.HIDDEN)
        store.set(other, target, EntityViewState.VISIBLE)
        store.removeViewer(viewer)
        assertEquals(EntityViewState.VISIBLE, store.get(other, target))
        assertNull(store.get(viewer, target))
    }

    @Test
    fun `expired entries are swept during eviction, not counted as capacity`() {
        val clock = FakeClock()
        val store = store(clock, ttl = 100, maxPerViewer = 2)
        store.set(viewer, target, EntityViewState.VISIBLE)
        clock.advance(200) // target expired
        store.set(viewer, other, EntityViewState.DISGUISED)
        clock.advance(10)
        val third = UUID.randomUUID()
        store.set(viewer, third, EntityViewState.HIDDEN)

        // the sweep had room for all three: expired target didn't occupy a slot
        assertEquals(EntityViewState.DISGUISED, store.get(viewer, other))
        assertEquals(EntityViewState.HIDDEN, store.get(viewer, third))
        assertNull(store.get(viewer, target))
    }
}
