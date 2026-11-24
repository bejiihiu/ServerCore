package kz.bejiihiu.servercore.spectator

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * the observation pair view: written by the hiding bridge, read by the packet
 * layer. it makes no lifecycle decisions of its own.
 */
class ObserverPairRegistryTest {

    private val registry = ObserverPairRegistry()

    @Test
    fun `register makes the pair visible through the source interface`() {
        val admin = UUID.randomUUID()
        val target = UUID.randomUUID()

        registry.register(admin, target)

        assertTrue(registry.isObserving(admin, target))
        assertTrue(registry.isObserver(admin))
        assertTrue(registry.isObserved(target))
        assertEquals(target, registry.observedTarget(admin))
        assertEquals(setOf(admin), registry.observersOf(target))
    }

    @Test
    fun `unregister only closes the exact pair`() {
        val admin = UUID.randomUUID()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        registry.register(admin, a)
        registry.unregister(admin, b)

        assertTrue(registry.isObserving(admin, a), "wrong pair close must not touch the live one")

        registry.unregister(admin, a)

        assertFalse(registry.isObserving(admin, a))
        assertNull(registry.observedTarget(admin))
    }

    @Test
    fun `an admin re-specing someone else replaces their old pair`() {
        val admin = UUID.randomUUID()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        registry.register(admin, a)
        registry.register(admin, b)

        assertFalse(registry.isObserving(admin, a))
        assertTrue(registry.isObserving(admin, b))
        assertFalse(registry.isObserved(a))
        assertTrue(registry.isObserved(b))
    }

    @Test
    fun `several admins may watch the same target`() {
        val adminA = UUID.randomUUID()
        val adminB = UUID.randomUUID()
        val target = UUID.randomUUID()

        registry.register(adminA, target)
        registry.register(adminB, target)

        assertEquals(setOf(adminA, adminB), registry.observersOf(target))
        assertTrue(registry.isObserving(adminA, target))
        assertTrue(registry.isObserving(adminB, target))
    }

    @Test
    fun `an admin never observes themselves`() {
        val admin = UUID.randomUUID()

        registry.register(admin, admin)

        assertFalse(registry.isObserving(admin, admin))
        assertFalse(registry.isObserver(admin))
    }

    @Test
    fun `allpairs snapshots the live set and empties once the bridge unregisters`() {
        val admin = UUID.randomUUID()
        val target = UUID.randomUUID()

        registry.register(admin, target)
        assertEquals(setOf(admin to target), registry.allPairs())

        registry.unregister(admin, target)

        assertTrue(registry.allPairs().isEmpty())
    }

    @Test
    fun `the registry satisfies the seam interface the packet layer expects`() {
        val source: SpecSessionSource = registry
        val admin = UUID.randomUUID()
        val target = UUID.randomUUID()

        assertFalse(source.isObserving(admin, target))
        registry.register(admin, target)
        assertTrue(source.isObserving(admin, target))
    }
}
