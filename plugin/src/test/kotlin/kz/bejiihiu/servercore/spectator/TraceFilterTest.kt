package kz.bejiihiu.servercore.spectator

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * the pure decision core of trace suppression and pair scoping. these rules
 * are what the scoped packet listener executes, so they carry the honesty
 * constraints of the heuristic: fail open, never blind the target on a maybe.
 */
class TraceFilterTest {

    private val adminPos = TracePos(100.0, 64.0, 100.0)

    @Test
    fun `a particle at the admin's position is admin-origin and dropped`() {
        val filter = TraceFilter(radius = 1.5, suppressActionBar = false)

        assertEquals(TraceDecision.DROP, filter.decide(TraceKind.PARTICLE, adminPos, TracePos(100.5, 64.2, 100.5)))
    }

    @Test
    fun `a particle far from the admin passes`() {
        val filter = TraceFilter(radius = 1.5, suppressActionBar = false)

        assertEquals(TraceDecision.KEEP, filter.decide(TraceKind.PARTICLE, adminPos, TracePos(120.0, 64.0, 100.0)))
    }

    @Test
    fun `the radius boundary is inclusive`() {
        val filter = TraceFilter(radius = 2.0, suppressActionBar = false)

        assertEquals(TraceDecision.DROP, filter.decide(TraceKind.PARTICLE, adminPos, TracePos(102.0, 64.0, 100.0)))
        assertEquals(TraceDecision.KEEP, filter.decide(TraceKind.PARTICLE, adminPos, TracePos(102.01, 64.0, 100.0)))
    }

    @Test
    fun `sounds follow the same proximity rule as particles`() {
        val filter = TraceFilter(radius = 1.5, suppressActionBar = false)

        assertEquals(TraceDecision.DROP, filter.decide(TraceKind.SOUND, adminPos, TracePos(100.0, 65.0, 100.0)))
        assertEquals(TraceDecision.KEEP, filter.decide(TraceKind.SOUND, adminPos, TracePos(200.0, 65.0, 200.0)))
    }

    @Test
    fun `a sound at the world origin is a ui play and never attributed`() {
        val filter = TraceFilter(radius = 16.0, suppressActionBar = false)

        assertEquals(TraceDecision.KEEP, filter.decide(TraceKind.SOUND, adminPos, TracePos(0.0, 0.0, 0.0)))
    }

    @Test
    fun `no captured admin position means keep, whatever the packet is`() {
        val filter = TraceFilter(radius = 1.5, suppressActionBar = true)

        assertEquals(TraceDecision.KEEP, filter.decide(TraceKind.PARTICLE, null, TracePos(100.0, 64.0, 100.0)))
        assertEquals(TraceDecision.KEEP, filter.decide(TraceKind.SOUND, null, null))
        assertEquals(TraceDecision.KEEP, filter.decide(TraceKind.ACTION_BAR, null, null))
    }

    @Test
    fun `actionbar is all or nothing per session, config owned`() {
        val on = TraceFilter(radius = 1.5, suppressActionBar = true)
        val off = TraceFilter(radius = 1.5, suppressActionBar = false)

        assertEquals(TraceDecision.DROP, on.decide(TraceKind.ACTION_BAR, adminPos, null))
        assertEquals(TraceDecision.KEEP, off.decide(TraceKind.ACTION_BAR, adminPos, null))
    }

    @Test
    fun `vertical distance counts into the radius`() {
        val filter = TraceFilter(radius = 1.5, suppressActionBar = false)

        assertEquals(TraceDecision.DROP, filter.decide(TraceKind.PARTICLE, adminPos, TracePos(100.0, 65.4, 100.0)))
        assertEquals(TraceDecision.KEEP, filter.decide(TraceKind.PARTICLE, adminPos, TracePos(100.0, 66.6, 100.0)))
    }
}

/**
 * scoping of entity packets about a hidden admin: in scope only when the
 * packet's owner is the admin currently watching the recipient.
 */
class ObserverScopeTest {

    private val sessions = SpecSessionRegistry()

    @Test
    fun `a packet about the watching admin toward their target is in scope`() {
        val admin = UUID.randomUUID()
        val target = UUID.randomUUID()
        sessions.register(admin, target)

        assertTrue(ObserverScope.isHiddenPair(admin, target, sessions))
    }

    @Test
    fun `everything else is out of scope`() {
        val admin = UUID.randomUUID()
        val target = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        sessions.register(admin, target)

        assertFalse(ObserverScope.isHiddenPair(null, target, sessions), "unattributable entity id")
        assertFalse(ObserverScope.isHiddenPair(stranger, target, sessions), "entity is not the watcher")
        assertFalse(ObserverScope.isHiddenPair(target, target, sessions), "own packets are never scoped")
        assertFalse(ObserverScope.isHiddenPair(admin, stranger, sessions), "admin hidden only from their own target")
        assertFalse(ObserverScope.isHiddenPair(target, admin, sessions), "the admin's client sees everything")
    }
}

/** shutdown restore ordering: admin first, then target, deterministic. */
class RestorePlanTest {

    @Test
    fun `pairs restore sorted by admin then target`() {
        val a1 = UUID.randomUUID()
        val a2 = UUID.randomUUID()
        val pairs = setOf(
            a2 to UUID.randomUUID(),
            a1 to UUID.randomUUID(),
        )

        val ordered = RestorePlan.ordered(pairs)

        val expected = pairs.sortedWith(compareBy({ it.first }, { it.second }))
        assertEquals(expected, ordered)
        assertEquals(2, ordered.size)
    }

    @Test
    fun `same admin restores their pairs in target order`() {
        val admin = UUID.randomUUID()
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        // one admin watches at most one target, but the plan stays correct
        // for any input: sort, don't assume
        val tA = if (t1 < t2) t1 else t2
        val tB = if (t1 < t2) t2 else t1

        val ordered = RestorePlan.ordered(setOf(admin to tB, admin to tA))

        assertEquals(listOf(admin to tA, admin to tB), ordered)
    }

    @Test
    fun `empty and single cases stay stable`() {
        assertTrue(RestorePlan.ordered(emptySet()).isEmpty())
        val single = setOf(UUID.randomUUID() to UUID.randomUUID())
        assertEquals(single.toList(), RestorePlan.ordered(single))
    }
}
