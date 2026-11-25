package kz.bejiihiu.servercore.spectator

import java.util.UUID
import kz.bejiihiu.servercore.packet.CachedViewStateStore
import kz.bejiihiu.servercore.packet.EntityViewState
import kz.bejiihiu.servercore.packet.PacketStateRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * the pairing invariants of the hiding layer: which pairs exist, what the
 * packet registry says about them, and that every begin has exactly one
 * matching teardown - all without a server, the wire is faked.
 */
class ObserverHiderTest {

    private val admin = UUID.fromString("00000000-0000-0000-0000-00000000a001")
    private val otherAdmin = UUID.fromString("00000000-0000-0000-0000-00000000a002")
    private val target = UUID.fromString("00000000-0000-0000-0000-00000000b001")
    private val secondTarget = UUID.fromString("00000000-0000-0000-0000-00000000b002")

    /** rows go through the real registry, so the assertions are about production code */
    private class RegistryRows : ObserverRows {
        val registry = PacketStateRegistry(CachedViewStateStore(64, 64))

        override fun set(viewer: UUID, target: UUID, state: EntityViewState) = registry.set(viewer, target, state)

        override fun bindEntityId(viewer: UUID, entityId: Int, target: UUID) =
            registry.bindEntityId(viewer, entityId, target)
    }

    private class FakeWire : ObserverWire {
        override var online = true
        val present = mutableSetOf<UUID>()
        val entityIds = mutableMapOf<UUID, Int>()
        val hidden = mutableListOf<Triple<UUID, Int, UUID>>()
        val restored = mutableListOf<Pair<UUID, UUID>>()
        val bestEffortRestored = mutableListOf<Pair<UUID, UUID>>()
        val sinks = mutableMapOf<UUID, (TracePos) -> Unit>()
        var poseTracked = 0
        var closedHandles = 0

        override fun entityId(player: UUID): Int? = if (player in present) entityIds[player] else null

        override fun isPresent(player: UUID): Boolean = player in present

        override fun hideFrom(viewer: UUID, entityId: Int, admin: UUID) {
            hidden.add(Triple(viewer, entityId, admin))
        }

        override fun restoreTo(viewer: UUID, admin: UUID, claimRow: (entityId: Int) -> Unit) {
            restored.add(viewer to admin)
            claimRow(entityIds.getValue(admin))
        }

        override fun restoreBestEffort(viewer: UUID, admin: UUID, claimRow: (entityId: Int) -> Unit) {
            bestEffortRestored.add(viewer to admin)
            claimRow(entityIds.getValue(admin))
        }

        override fun trackPose(admin: UUID, sink: (pos: TracePos) -> Unit): AutoCloseable? {
            if (admin !in present) return null
            poseTracked++
            sinks[admin] = sink
            return AutoCloseable { closedHandles++ }
        }
    }

    private class Harness {
        val rows = RegistryRows()
        val wire = FakeWire()
        val pairs = ObserverPairRegistry()
        val hider = ObserverHider(pairs, rows, wire)
    }

    private fun harness(): Harness = Harness().apply {
        wire.present.addAll(listOf(admin, otherAdmin, target, secondTarget))
        wire.entityIds[admin] = 42
        wire.entityIds[otherAdmin] = 43
    }

    @Test
    fun `begin registers the pair, hides the row and sends one hide burst`() {
        val h = harness()

        h.hider.begin(admin, target)

        assertEquals(listOf(Triple(target, 42, admin)), h.wire.hidden)
        assertEquals(EntityViewState.HIDDEN, h.rows.registry.state(target, admin))
        assertEquals(target, h.pairs.observedTarget(admin))
        assertEquals(setOf(admin), h.pairs.observersOf(target))
    }

    @Test
    fun `begin twice is idempotent for the pair and the row`() {
        val h = harness()

        h.hider.begin(admin, target)
        h.hider.begin(admin, target)

        // the hide sequence is re-asserted (that is the resync policy), the
        // pair and the row stay single
        assertEquals(2, h.wire.hidden.size)
        assertEquals(EntityViewState.HIDDEN, h.rows.registry.state(target, admin))
        assertEquals(target, h.pairs.observedTarget(admin))
        assertTrue(h.wire.restored.isEmpty())
        assertEquals(1, h.wire.poseTracked, "pose tracking must not stack")
    }

    @Test
    fun `end restores the client, the row and the pair`() {
        val h = harness()
        h.hider.begin(admin, target)
        h.wire.sinks.getValue(admin).invoke(TracePos(1.0, 64.0, 2.0))
        assertEquals(TracePos(1.0, 64.0, 2.0), h.hider.poseOf(admin))

        h.hider.end(admin, target)

        assertEquals(listOf(target to admin), h.wire.restored)
        assertEquals(EntityViewState.VISIBLE, h.rows.registry.state(target, admin))
        assertEquals(admin, h.rows.registry.targetOf(target, 42), "the restored id stays addressable")
        assertNull(h.pairs.observedTarget(admin))
        assertNull(h.hider.poseOf(admin), "pose tracking dies with the session")
        assertEquals(1, h.wire.closedHandles)
    }

    @Test
    fun `end without a begin is safe and leaves no pair behind`() {
        val h = harness()

        h.hider.end(admin, target)

        // the row is written as visible regardless: VISIBLE is the only honest
        // answer for a pair that was never hidden
        assertEquals(EntityViewState.VISIBLE, h.rows.registry.state(target, admin))
        assertEquals(listOf(target to admin), h.wire.restored)
        assertTrue(h.wire.hidden.isEmpty())
        assertNull(h.pairs.observedTarget(admin))
    }

    @Test
    fun `end after both parties quit still clears the pair without a burst`() {
        val h = harness()
        h.hider.begin(admin, target)
        // both sides disconnect while the session teardown is still in flight
        h.wire.present.remove(admin)
        h.wire.present.remove(target)

        h.hider.end(admin, target)

        assertTrue(h.wire.restored.isEmpty(), "nothing to draw for, no burst")
        assertEquals(EntityViewState.VISIBLE, h.rows.registry.state(target, admin))
        assertNull(h.pairs.observedTarget(admin))
    }

    @Test
    fun `an offline admin gets no hide burst and no pose tracker`() {
        val h = harness()
        h.wire.present.remove(admin)

        h.hider.begin(admin, target)

        assertTrue(h.wire.hidden.isEmpty())
        assertEquals(0, h.wire.poseTracked)
        assertNull(h.hider.poseOf(admin))
    }

    @Test
    fun `an idle packet layer hides nothing`() {
        val h = harness()
        h.wire.online = false

        h.hider.begin(admin, target)

        // the pair bookkeeping is independent of the wire, the burst is not
        assertEquals(EntityViewState.HIDDEN, h.rows.registry.state(target, admin))
        assertTrue(h.wire.hidden.isEmpty())
        assertEquals(target, h.pairs.observedTarget(admin))
    }

    @Test
    fun `resync re-asserts the hide from either side of the pair`() {
        val h = harness()
        h.hider.begin(admin, target)
        h.wire.hidden.clear()

        h.hider.resync(target) // the observed player transitioned
        h.hider.resync(admin) // the observing admin transitioned

        assertEquals(2, h.wire.hidden.size)
        assertTrue(h.wire.hidden.all { it == Triple(target, 42, admin) })
        assertTrue(h.wire.restored.isEmpty(), "a resync never ends the session")
    }

    @Test
    fun `shutdown restores every pair in deterministic order and drops the trackers`() {
        val h = harness()
        h.hider.begin(otherAdmin, target)
        h.hider.begin(admin, target)

        h.hider.shutdown()

        // sorted by admin uuid, then target - a reload must not depend on map order
        assertEquals(listOf(target to admin, target to otherAdmin), h.wire.bestEffortRestored)
        assertEquals(2, h.wire.closedHandles)
        assertNull(h.hider.poseOf(admin))
        assertNull(h.hider.poseOf(otherAdmin))
        assertTrue(h.pairs.allPairs().isEmpty())
    }

    @Test
    fun `an admin never observes themselves`() {
        val h = harness()

        h.hider.begin(admin, admin)

        assertTrue(h.wire.hidden.isEmpty())
        assertNull(h.rows.registry.state(admin, admin))
        assertNull(h.pairs.observedTarget(admin))
    }

    @Test
    fun `a late teardown keeps the live pair, its tracker and its client picture`() {
        val h = harness()
        h.hider.begin(admin, target, "session-1")
        h.wire.sinks.getValue(admin).invoke(TracePos(1.0, 64.0, 2.0))

        // the admin switches targets: the new session opens before the old
        // teardown lands
        h.hider.begin(admin, secondTarget, "session-2")
        h.hider.end(admin, target, "session-1")

        // the live pair is untouched
        assertEquals(secondTarget, h.pairs.observedTarget(admin), "the live pair survives")
        assertTrue(h.pairs.owns(admin, secondTarget, "session-2"), "the live session still owns the pair")
        // trace attribution belongs to the live session now
        assertEquals(1, h.wire.poseTracked, "the tracker is opened once, not re-opened")
        assertEquals(0, h.wire.closedHandles, "a stale teardown must not close the live tracker")
        assertEquals(TracePos(1.0, 64.0, 2.0), h.hider.poseOf(admin), "pose data survives the stale teardown")
        // and the old target gets its picture back: skipping that would leave
        // the admin invisible to a player who is no longer being observed
        assertEquals(listOf(target to admin), h.wire.restored)
        assertEquals(EntityViewState.VISIBLE, h.rows.registry.state(target, admin))
        assertEquals(EntityViewState.HIDDEN, h.rows.registry.state(secondTarget, admin))
    }

    @Test
    fun `a late teardown for the same target cannot close the newer session`() {
        val h = harness()
        h.hider.begin(admin, target, "session-1")

        // the same admin re-specs the same player: a new session, same pair
        h.hider.begin(admin, target, "session-2")
        h.hider.end(admin, target, "session-1")

        assertTrue(h.pairs.owns(admin, target, "session-2"), "pair identity alone would see no change here")
        assertEquals(0, h.wire.closedHandles, "the live tracker stays open")
        assertTrue(h.wire.restored.isEmpty(), "the live hide must not be undone by the old session")
        assertEquals(EntityViewState.HIDDEN, h.rows.registry.state(target, admin))

        // the session that actually owns the pair still tears it down
        h.hider.end(admin, target, "session-2")

        assertNull(h.pairs.observedTarget(admin))
        assertEquals(listOf(target to admin), h.wire.restored)
        assertEquals(1, h.wire.closedHandles)
    }
}
