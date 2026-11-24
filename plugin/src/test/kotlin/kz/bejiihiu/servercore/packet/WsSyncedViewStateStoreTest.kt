package kz.bejiihiu.servercore.packet

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WsSyncedViewStateStoreTest {

    private val viewer = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val target = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private class RecordingSink : ViewStateSink {
        val events = mutableListOf<String>()
        override fun onStateSet(viewer: UUID, target: UUID, state: EntityViewState) {
            events.add("set:$viewer:$target:$state")
        }
        override fun onStateRemoved(viewer: UUID, target: UUID) {
            events.add("removed:$viewer:$target")
        }
        override fun onViewerRemoved(viewer: UUID) {
            events.add("viewer:$viewer")
        }
        override fun onTargetRemoved(target: UUID) {
            events.add("target:$target")
        }
    }

    @Test
    fun `reads and writes delegate to the backing store`() {
        val backing = CachedViewStateStore(60_000L, 16, 16)
        val store = WsSyncedViewStateStore(backing)

        store.set(viewer, target, EntityViewState.DISGUISED)
        assertEquals(EntityViewState.DISGUISED, store.get(viewer, target))
        assertEquals(EntityViewState.DISGUISED, backing.get(viewer, target))
        store.remove(viewer, target)
        assertNull(store.get(viewer, target))
    }

    @Test
    fun `every local mutation is offered to the sink`() {
        val backing = CachedViewStateStore(60_000L, 16, 16)
        val sink = RecordingSink()
        val store = WsSyncedViewStateStore(backing, sink)

        store.set(viewer, target, EntityViewState.HIDDEN)
        store.remove(viewer, target)
        store.set(viewer, target, EntityViewState.VISIBLE)
        store.removeViewer(viewer)

        assertEquals(
            listOf(
                "set:$viewer:$target:HIDDEN",
                "removed:$viewer:$target",
                "set:$viewer:$target:VISIBLE",
                "viewer:$viewer",
            ),
            sink.events,
        )
    }

    @Test
    fun `removing a target is announced once, not per viewer`() {
        val backing = CachedViewStateStore(60_000L, 16, 16)
        val sink = RecordingSink()
        val store = WsSyncedViewStateStore(backing, sink)
        val otherViewer = UUID.randomUUID()

        store.set(viewer, target, EntityViewState.VISIBLE)
        store.set(otherViewer, target, EntityViewState.HIDDEN)
        sink.events.clear()

        store.removeTarget(target)
        assertEquals(listOf("target:$target"), sink.events)
    }

    @Test
    fun `backend never sends view state - reads never touch the network`() {
        // the sink only ever sees writes; a store with a sink that records reads
        // would violate the local-only read contract, so reads stay in backing
        val backing = CachedViewStateStore(60_000L, 16, 16)
        val sink = RecordingSink()
        val store = WsSyncedViewStateStore(backing, sink)
        store.set(viewer, target, EntityViewState.VISIBLE)

        repeat(3) { store.get(viewer, target) }
        assertTrue(sink.events.size == 1, "reads must not produce sink events")
    }
}
