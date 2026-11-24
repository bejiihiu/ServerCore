package kz.bejiihiu.servercore.sync

import java.util.Random
import java.util.UUID
import java.util.logging.Logger
import kz.bejiihiu.servercore.esp.EspRules
import kz.bejiihiu.servercore.esp.PairFacts
import kz.bejiihiu.servercore.esp.PairVisibilityDecider
import kz.bejiihiu.servercore.packet.CachedViewStateStore
import kz.bejiihiu.servercore.packet.PacketStateRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * the whole chain, smoke-tested across the merged branches with no runtime:
 *
 * engine decision (pure decider) -> applier call (packet bridge) ->
 * registry row (sync policy + packet applied) -> resync invalidation
 * (the sync coordinator re-applies through the same bridge).
 */
class UnifiedChainTest {

    private val viewer = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val target = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private class RecordingDisguise : kz.bejiihiu.servercore.esp.ViewStateApplier {
        val calls = mutableListOf<String>()
        override fun apply(viewer: UUID, target: UUID, state: kz.bejiihiu.servercore.esp.ViewState, disguise: kz.bejiihiu.servercore.esp.DisguiseAssignment?) {
            calls += "$state($viewer,$target)"
        }
    }

    private var sync: PacketStateSync? = null

    @AfterEach
    fun tearDown() {
        sync?.release()
        sync = null
    }

    @Test
    fun `decider verdict lands as a policy row and death invalidates it into packets`() {
        val states = InMemoryViewStateRegistry()
        val packets = PacketStateRegistry(
            CachedViewStateStore(64, 64),
        )
        val disguise = RecordingDisguise()
        val bridge = PacketResyncExecutor(packets, states, disguise, Logger.getGlobal())
        val syncInstance = PacketStateSync.create(states, bridge, EntityIdentityTracker())!!
            .also { sync = it }

        // 1) engine decision: blocked sight behind the managed radius disguises
        val rules = EspRules.defaults()
        val verdict = PairVisibilityDecider(rules).decide(
            PairFacts(
                sameWorld = true,
                distanceSquared = 30.0 * 30.0,
                losBlocked = true,
                targetSneaking = false,
                viewerSpectator = false,
                targetSpectator = false,
                viewerDead = false,
                targetDead = false,
                bothGuests = false,
                targetSpecObserving = false,
            ),
            previous = null,
            rng = Random(1),
        )
        assertEquals(kz.bejiihiu.servercore.esp.ViewState.DISGUISED, verdict.state)

        // 2) applier call: the bridge takes the verdict
        bridge.apply(viewer, target, verdict.state, verdict.disguise)

        // 3) registry rows: policy + applied picture agree
        assertEquals(ViewState.DISGUISED, states.get(viewer, target))
        assertEquals(ViewState.DISGUISED, packets.state(viewer, target))
        assertEquals(listOf("DISGUISED($viewer,$target)"), disguise.calls)

        // 4) resync invalidation: the target dies, the coordinator forces the
        // pair back to vanilla through the very same bridge
        disguise.calls.clear()
        syncInstance.onTransition(SyncReason.DEATH, target)

        assertEquals(listOf("VISIBLE($viewer,$target)"), disguise.calls)
        assertEquals(ViewState.DEAD, states.get(viewer, target))
        assertEquals(ViewState.VISIBLE, packets.state(viewer, target))
    }

    @Test
    fun `respawn re-applies policy through the bridge after the dead pin clears`() {
        val states = InMemoryViewStateRegistry()
        val packets = PacketStateRegistry(
            CachedViewStateStore(64, 64),
        )
        val disguise = RecordingDisguise()
        val bridge = PacketResyncExecutor(packets, states, disguise)
        val syncInstance = PacketStateSync.create(states, bridge, EntityIdentityTracker())!!
            .also { sync = it }

        // the real lifecycle: disguised pair, death pins it DEAD, respawn clears
        bridge.apply(viewer, target, ViewState.DISGUISED, null)
        syncInstance.onTransition(SyncReason.DEATH, target)
        assertEquals(ViewState.DEAD, states.get(viewer, target))
        disguise.calls.clear()

        syncInstance.onTransition(SyncReason.RESPAWN, target)

        // dead pin cleared: the writer applied vanilla and the row follows it
        assertEquals(ViewState.VISIBLE, states.get(viewer, target))
        assertEquals(ViewState.VISIBLE, packets.state(viewer, target))
        assertEquals(listOf("VISIBLE($viewer,$target)"), disguise.calls)
    }

    @Test
    fun `reconnect resends the viewer view as real packet-side re-assertions`() {
        val states = InMemoryViewStateRegistry()
        val packets = PacketStateRegistry(
            CachedViewStateStore(64, 64),
        )
        val disguise = RecordingDisguise()
        val bridge = PacketResyncExecutor(packets, states, disguise)
        val syncInstance = PacketStateSync.create(states, bridge, EntityIdentityTracker())!!
            .also { sync = it }

        bridge.apply(viewer, target, ViewState.HIDDEN, null)
        bridge.apply(viewer, viewer, ViewState.HIDDEN, null) // ignored, self pair
        disguise.calls.clear()

        syncInstance.onTransition(SyncReason.RECONNECT, viewer)

        // viewer side: fresh client, every policy pair re-asserted
        assertTrue(disguise.calls.contains("HIDDEN($viewer,$target)"))
        assertEquals(ViewState.HIDDEN, packets.state(viewer, target))
    }

    @Test
    fun `full reload restore walks every pair back to vanilla through the bridge`() {
        val states = InMemoryViewStateRegistry()
        val packets = PacketStateRegistry(
            CachedViewStateStore(64, 64),
        )
        val disguise = RecordingDisguise()
        val bridge = PacketResyncExecutor(packets, states, disguise)
        val syncInstance = PacketStateSync.create(states, bridge, EntityIdentityTracker())!!
            .also { sync = it }

        bridge.apply(viewer, target, ViewState.DISGUISED, null)

        syncInstance.onTransition(SyncReason.PLUGIN_RELOAD, viewer)

        assertEquals(listOf("DISGUISED($viewer,$target)", "VISIBLE($viewer,$target)"), disguise.calls)
        assertEquals(0, states.rowCount())
        assertNull(states.get(viewer, target))
    }
}
