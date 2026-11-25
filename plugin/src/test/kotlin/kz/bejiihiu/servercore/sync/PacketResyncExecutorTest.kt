package kz.bejiihiu.servercore.sync

import java.util.UUID
import java.util.logging.Logger
import kz.bejiihiu.servercore.esp.DisguiseAssignment
import kz.bejiihiu.servercore.esp.ViewState
import kz.bejiihiu.servercore.esp.ViewStateApplier
import kz.bejiihiu.servercore.packet.CachedViewStateStore
import kz.bejiihiu.servercore.packet.PacketStateRegistry
import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * the packet bridge in isolation: policy rows, applied packet rows and the
 * disguise calls it produces for every seam method. pure layers only, no
 * packetevents and no bukkit anywhere near the classpath.
 */
class PacketResyncExecutorTest {

    private val viewer = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val target = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val other = UUID.fromString("00000000-0000-0000-0000-000000000003")

    /** records disguise-applier calls as readable strings */
    private class RecordingDisguise : ViewStateApplier {
        val calls = mutableListOf<String>()

        override fun apply(viewer: UUID, target: UUID, state: ViewState, assignment: DisguiseAssignment?) {
            calls += "$state($viewer,$target)"
        }
    }

    private fun packetRegistry() = PacketStateRegistry(
        CachedViewStateStore(64, 64),
    )

    private fun bridge(
        states: StateInvalidator = InMemoryViewStateRegistry(),
        packets: PacketStateRegistry = packetRegistry(),
        disguise: ViewStateApplier? = RecordingDisguise(),
    ) = PacketResyncExecutor(packets, states, disguise)

    @Test
    fun `engine verdict records the policy row and drives the disguise`() {
        val states = InMemoryViewStateRegistry()
        val packets = packetRegistry()
        val disguise = RecordingDisguise()
        val exec = bridge(states, packets, disguise)

        exec.apply(viewer, target, ViewState.DISGUISED, DisguiseAssignment("ZOMBIE"))

        assertEquals(ViewState.DISGUISED, states.get(viewer, target))
        assertEquals(ViewState.DISGUISED, packets.state(viewer, target))
        assertEquals(listOf("DISGUISED($viewer,$target)"), disguise.calls)
    }

    @Test
    fun `visible verdict clears the override instead of piling up rows`() {
        val states = InMemoryViewStateRegistry()
        val packets = packetRegistry()
        val disguise = RecordingDisguise()
        val exec = bridge(states, packets, disguise)

        exec.apply(viewer, target, ViewState.DISGUISED, null)
        exec.apply(viewer, target, ViewState.VISIBLE, null)

        assertNull(states.get(viewer, target))
        assertEquals(ViewState.VISIBLE, packets.state(viewer, target))
        assertEquals(listOf("DISGUISED($viewer,$target)", "VISIBLE($viewer,$target)"), disguise.calls)
    }

    @Test
    fun `visible verdict on a never-touched pair writes no packet row`() {
        val packets = packetRegistry()
        val exec = bridge(packets = packets)

        exec.apply(viewer, target, ViewState.VISIBLE, null)

        assertNull(packets.state(viewer, target), "vanilla pairs must not create registry churn")
    }

    @Test
    fun `disguise verdict degrades to hidden when no disguise layer exists`() {
        val states = InMemoryViewStateRegistry()
        val packets = packetRegistry()
        val exec = bridge(states, packets, disguise = null)

        exec.apply(viewer, target, ViewState.DISGUISED, null)

        // fail closed, matching the engine's disguise-off pose
        assertEquals(ViewState.HIDDEN, packets.state(viewer, target))
        // the policy row keeps the original verdict so later resyncs degrade the same way
        assertEquals(ViewState.DISGUISED, states.get(viewer, target))
    }

    @Test
    fun `dead verdict applies as plain vanilla`() {
        val states = InMemoryViewStateRegistry()
        val packets = packetRegistry()
        val disguise = RecordingDisguise()
        val exec = bridge(states, packets, disguise)

        exec.apply(viewer, target, ViewState.DISGUISED, null)
        exec.apply(viewer, target, ViewState.DEAD, null)

        assertEquals(ViewState.VISIBLE, packets.state(viewer, target))
        assertEquals(listOf("DISGUISED($viewer,$target)", "VISIBLE($viewer,$target)"), disguise.calls)
    }

    @Test
    fun `a stale engine settle cannot overwrite the dead pin`() {
        val states = InMemoryViewStateRegistry()
        val packets = packetRegistry()
        val disguise = RecordingDisguise()
        val exec = bridge(states, packets, disguise)

        exec.apply(viewer, target, ViewState.DISGUISED, null)
        exec.apply(viewer, target, ViewState.DEAD, null)
        disguise.calls.clear()

        // the engine's snapshot predates the death event: its settle says
        // disguised again. the pin must win, respawn clears it - not a laggy
        // snapshot
        exec.apply(viewer, target, ViewState.DISGUISED, null)

        assertEquals(ViewState.DEAD, states.get(viewer, target), "pin must survive a stale settle")
        assertEquals(ViewState.VISIBLE, packets.state(viewer, target), "client keeps vanilla death")
        assertTrue(disguise.calls.isEmpty(), "no disguise work may run for a dead pair")
    }

    @Test
    fun `resync target re-executes the policy row and reports it`() {
        val states = InMemoryViewStateRegistry()
        val disguise = RecordingDisguise()
        val exec = bridge(states, disguise = disguise)

        exec.apply(viewer, target, ViewState.HIDDEN, null)
        disguise.calls.clear()

        val applied = exec.resyncTarget(viewer, target)

        assertEquals(ViewState.HIDDEN, applied)
        assertEquals(ViewState.HIDDEN, states.get(viewer, target))
        assertEquals(listOf("HIDDEN($viewer,$target)"), disguise.calls)
    }

    @Test
    fun `resync target without a policy row applies nothing`() {
        val disguise = RecordingDisguise()
        val exec = bridge(disguise = disguise)

        assertNull(exec.resyncTarget(viewer, target))
        assertTrue(disguise.calls.isEmpty())
    }

    @Test
    fun `restore vanilla reveals the pair and clears the applied override`() {
        val states = InMemoryViewStateRegistry()
        val packets = packetRegistry()
        val disguise = RecordingDisguise()
        val exec = bridge(states, packets, disguise)

        exec.apply(viewer, target, ViewState.DISGUISED, null)
        disguise.calls.clear()

        exec.restoreVanilla(viewer, target)

        assertEquals(ViewState.VISIBLE, packets.state(viewer, target))
        assertEquals(listOf("VISIBLE($viewer,$target)"), disguise.calls)
    }

    @Test
    fun `reset viewer re-asserts every policy pair of that viewer`() {
        val states = InMemoryViewStateRegistry()
        val disguise = RecordingDisguise()
        val exec = bridge(states, disguise = disguise)

        exec.apply(viewer, target, ViewState.HIDDEN, null)
        exec.apply(viewer, other, ViewState.DISGUISED, null)
        exec.apply(other, target, ViewState.HIDDEN, null) // other viewer, untouched
        disguise.calls.clear()

        exec.resetViewer(viewer)

        // only the resetting viewer's pairs re-run, with their own policy
        assertEquals(
            listOf("HIDDEN($viewer,$target)", "DISGUISED($viewer,$other)"),
            disguise.calls,
        )
        // and the bystander's pair is untouched
        assertEquals(ViewState.HIDDEN, states.get(other, target))
    }

    @Test
    fun `self pairs are ignored on the engine side too`() {
        val states = InMemoryViewStateRegistry()
        val disguise = RecordingDisguise()
        val exec = bridge(states, disguise = disguise)

        exec.apply(viewer, viewer, ViewState.DISGUISED, null)

        assertNull(states.get(viewer, viewer))
        assertTrue(disguise.calls.isEmpty())
    }
}
