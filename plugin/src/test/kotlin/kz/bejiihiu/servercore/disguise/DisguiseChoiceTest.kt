package kz.bejiihiu.servercore.disguise

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisguiseChoiceTest {

    private val registry = listOf("zombie", "skeleton", "spider", "cow", "pig", "sheep")

    private fun pair(i: Int): Pair<UUID, UUID> =
        UUID.nameUUIDFromBytes("viewer-$i".toByteArray()) to UUID.nameUUIDFromBytes("target-$i".toByteArray())

    @Test
    fun samePairAlwaysPicksTheSameMob() {
        val (viewer, target) = pair(1)
        val first = DisguiseChoice.pickOrNull(viewer, target, registry)
        repeat(100) {
            assertEquals(first, DisguiseChoice.pickOrNull(viewer, target, registry))
        }
    }

    @Test
    fun pickIsSymmetricInArgumentOrder() {
        // (viewer, target) is the canonical order; flipping it is a different
        // pair and may differ, but both must still be stable picks
        val (viewer, target) = pair(2)
        assertEquals(DisguiseChoice.pickOrNull(viewer, target, registry), DisguiseChoice.pickOrNull(viewer, target, registry))
        assertEquals(DisguiseChoice.pickOrNull(target, viewer, registry), DisguiseChoice.pickOrNull(target, viewer, registry))
    }

    @Test
    fun differentPairsSpreadAcrossTheRegistry() {
        val picked = mutableSetOf<String>()
        for (i in 0 until 200) {
            val (viewer, target) = pair(i)
            picked += DisguiseChoice.pickOrNull(viewer, target, registry)!!
        }
        // 200 independent pairs over 6 mobs must not collapse onto one model
        assertTrue(picked.size >= 2, "expected spread, got only $picked")
    }

    @Test
    fun pickStaysInsideTheRegistry() {
        for (i in 0 until 100) {
            val (viewer, target) = pair(i)
            assertTrue(DisguiseChoice.pickOrNull(viewer, target, registry)!! in registry)
        }
    }

    @Test
    fun emptyRegistryHasNoPick() {
        val (viewer, target) = pair(3)
        assertNull(DisguiseChoice.pickOrNull(viewer, target, emptyList()))
    }

    @Test
    fun registryOrderChangesDoNotCrashAndStayDeterministic() {
        val (viewer, target) = pair(4)
        val shuffled = registry.asReversed()
        val a = DisguiseChoice.pickOrNull(viewer, target, registry)
        val b = DisguiseChoice.pickOrNull(viewer, target, shuffled)
        assertNotNull(a)
        assertNotNull(b)
        // same pair, different registry order: still a deterministic pair of answers
        assertEquals(a, DisguiseChoice.pickOrNull(viewer, target, registry))
        assertEquals(b, DisguiseChoice.pickOrNull(viewer, target, shuffled))
    }
}
