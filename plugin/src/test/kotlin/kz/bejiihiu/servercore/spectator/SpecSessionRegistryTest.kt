package kz.bejiihiu.servercore.spectator

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * the local session registry: the truth source the hiding layer reads and the
 * seam the /spec command module will drive.
 */
class SpecSessionRegistryTest {

    private val registry = SpecSessionRegistry()

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
    fun `detach cleans both directions of one player`() {
        val admin = UUID.randomUUID()
        val target = UUID.randomUUID()
        val otherAdmin = UUID.randomUUID()

        registry.register(admin, target)
        registry.register(otherAdmin, target)

        // target leaves: every watching pair dies, the admins' own sessions go too
        registry.detach(target)

        assertFalse(registry.isObserving(admin, target))
        assertFalse(registry.isObserving(otherAdmin, target))
        assertTrue(registry.allPairs().isEmpty())

        // admin leaves: only their own pair dies
        registry.register(admin, target)
        registry.detach(admin)

        assertFalse(registry.isObserved(target))
        assertTrue(registry.allPairs().isEmpty())
    }

    @Test
    fun `clear wipes everything and allpairs snapshots the live set`() {
        val admin = UUID.randomUUID()
        val target = UUID.randomUUID()

        registry.register(admin, target)
        assertEquals(setOf(admin to target), registry.allPairs())

        registry.clear()

        assertTrue(registry.allPairs().isEmpty())
    }

    @Test
    fun `the registry satisfies the seam interface the spec-core side expects`() {
        val source: SpecSessionSource = registry
        val admin = UUID.randomUUID()
        val target = UUID.randomUUID()

        assertFalse(source.isObserving(admin, target))
        registry.register(admin, target)
        assertTrue(source.isObserving(admin, target))
    }
}
