package kz.bejiihiu.servercore.packet

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketStateRegistryTest {

    private val viewer = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val target = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val other = UUID.fromString("00000000-0000-0000-0000-000000000003")

    private fun registry() = PacketStateRegistry(CachedViewStateStore(60_000L, 64, 64))

    @Test
    fun `spawned entity ids resolve to their target per viewer`() {
        val reg = registry()
        reg.bindEntityId(viewer, 152, target)
        assertEquals(target, reg.targetOf(viewer, 152))
        assertNull(reg.targetOf(other, 152), "entity ids are connection-scoped")
    }

    @Test
    fun `fresh pair defaults to visible and state is readable`() {
        val reg = registry()
        assertNull(reg.state(viewer, target))
        reg.set(viewer, target, EntityViewState.VISIBLE)
        assertEquals(EntityViewState.VISIBLE, reg.state(viewer, target))
        reg.set(viewer, target, EntityViewState.HIDDEN)
        assertEquals(EntityViewState.HIDDEN, reg.state(viewer, target))
    }

    @Test
    fun `unbind drops state only when the target has no ids left`() {
        val reg = registry()
        reg.bindEntityId(viewer, 1, target)
        reg.bindEntityId(viewer, 2, target)
        reg.set(viewer, target, EntityViewState.DISGUISED)

        // first id destroyed: passenger-style double binding keeps the state alive
        var dropped = reg.unbindEntityIds(viewer, intArrayOf(1))
        assertTrue(dropped.isEmpty())
        assertEquals(EntityViewState.DISGUISED, reg.state(viewer, target))
        assertEquals(target, reg.targetOf(viewer, 2))

        // last id destroyed: state goes with it
        dropped = reg.unbindEntityIds(viewer, intArrayOf(2))
        assertEquals(setOf(target), dropped)
        assertNull(reg.state(viewer, target))
        assertNull(reg.targetOf(viewer, 2))
    }

    @Test
    fun `unbind with unknown ids is a silent no-op`() {
        val reg = registry()
        assertTrue(reg.unbindEntityIds(viewer, intArrayOf(7, 8, 9)).isEmpty())
    }

    @Test
    fun `removeViewer forgets both ids and state`() {
        val reg = registry()
        reg.bindEntityId(viewer, 5, target)
        reg.set(viewer, target, EntityViewState.HIDDEN)

        reg.removeViewer(viewer)
        assertNull(reg.targetOf(viewer, 5))
        assertNull(reg.state(viewer, target))
    }

    @Test
    fun `removeTarget cleans every viewer's picture of the entity`() {
        val reg = registry()
        reg.bindEntityId(viewer, 5, target)
        reg.bindEntityId(other, 6, target)
        reg.set(viewer, target, EntityViewState.DISGUISED)
        reg.set(other, target, EntityViewState.HIDDEN)

        reg.removeTarget(target)
        assertNull(reg.targetOf(viewer, 5))
        assertNull(reg.targetOf(other, 6))
        assertNull(reg.state(viewer, target))
        assertNull(reg.state(other, target))
    }

    @Test
    fun `clear wipes everything`() {
        val reg = registry()
        reg.bindEntityId(viewer, 1, target)
        reg.set(viewer, target, EntityViewState.VISIBLE)
        reg.bindEntityId(other, 2, other)
        reg.set(other, other, EntityViewState.HIDDEN)

        reg.clear()
        assertEquals(0, reg.size())
        assertFalse(reg.state(viewer, target) != null || reg.targetOf(other, 2) != null)
    }

    @Test
    fun `concurrent writers do not corrupt the index`() {
        val reg = registry()
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(200) { i ->
                    val id = t * 1000 + i
                    reg.bindEntityId(viewer, id, target)
                    reg.set(viewer, target, EntityViewState.VISIBLE)
                    reg.unbindEntityIds(viewer, intArrayOf(id))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(0, reg.size())
    }
}
