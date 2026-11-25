package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.flectone.pulse.model.event.lifecycle.EnableEvent
import net.flectone.pulse.model.event.lifecycle.ReloadEvent
import net.flectone.pulse.model.event.player.PlayerLoadEvent
import net.flectone.pulse.model.event.player.PlayerPersistAndDisposeEvent
import net.flectone.pulse.model.event.player.PlayerQuitEvent

/**
 * the permanent flectonepulse listener outlives a servercore disable and there
 * is no api to unregister it, so the only thing keeping it harmless is the slot
 * it reads before touching anything. these tests pin that contract: unbound
 * callbacks do nothing, a re-enabled bridge is served through the very same
 * registration, and registration never doubles.
 */
class FlectonePulseListenerSlotTest {

    private val player = testFPlayer(UUID.randomUUID(), id = 7)

    @Test
    fun `callbacks after unbind are a no-op`() {
        val slot = FlectonePulseListenerSlot()
        val target = RecordingPunishmentTarget()
        slot.bind(target)
        slot.unbind(target)
        val listener = FlectonePulseStateListener(slot)

        assertNull(slot.current(), "a stopped bridge must leave nothing bound")

        // what fp keeps calling after our disable: no throw, no cache, no
        // scheduler, no service of a dead plugin
        listener.onPlayerLoad(PlayerLoadEvent(player))
        listener.onPlayerQuit(PlayerQuitEvent(player))
        listener.onPlayerPersist(PlayerPersistAndDisposeEvent(player))
        listener.onReload(ReloadEvent(null, ReloadEvent.Type.END))
        listener.onEnable(EnableEvent(EnableEvent.Type.READY, null))

        assertTrue(target.invalidated.isEmpty())
        assertEquals(0, target.restarts)
    }

    @Test
    fun `re-enabling rebinds the registered listener instead of registering another`() {
        val slot = FlectonePulseListenerSlot()
        val registry = FakeListenerRegistry()

        val first = RecordingPunishmentTarget()
        slot.bind(first)
        slot.ensureRegistered(registry)

        // our disable, then an enable in the same classloader
        slot.unbind(first)
        val second = RecordingPunishmentTarget()
        slot.bind(second)
        slot.ensureRegistered(registry)

        assertEquals(1, registry.permanentListeners.size, "one permanent listener per classloader")

        val listener = registry.permanentListeners.single() as FlectonePulseStateListener
        listener.onPlayerLoad(PlayerLoadEvent(player))
        assertTrue(first.invalidated.isEmpty(), "the dead bridge must not be touched")
        assertEquals(listOf(player.uuid()), second.invalidated, "the live bridge gets the invalidation")

        assertEquals(0, registry.unregisterAllCalls, "unregisterAll would drop fp's own listeners")
    }

    @Test
    fun `registration is idempotent for the same registry instance`() {
        val slot = FlectonePulseListenerSlot()
        val registry = FakeListenerRegistry()

        // connect, fp reload (the registry bean survives, so the same instance),
        // late-enable refresh
        repeat(3) { slot.ensureRegistered(registry) }

        assertEquals(1, registry.permanentListeners.size)
    }

    @Test
    fun `a recreated fp registry gets the listener again`() {
        val slot = FlectonePulseListenerSlot()
        val beforeReload = FakeListenerRegistry()
        val afterFpRestart = FakeListenerRegistry()

        slot.ensureRegistered(beforeReload)
        slot.ensureRegistered(beforeReload)
        slot.ensureRegistered(afterFpRestart)

        assertEquals(1, beforeReload.permanentListeners.size)
        assertEquals(
            1,
            afterFpRestart.permanentListeners.size,
            "a fresh registry is empty, so it needs its own registration",
        )
        assertSame(beforeReload.permanentListeners.single(), afterFpRestart.permanentListeners.single())
    }

    @Test
    fun `a bridge that lost the slot cannot clear a newer one`() {
        val slot = FlectonePulseListenerSlot()
        val old = RecordingPunishmentTarget()
        val fresh = RecordingPunishmentTarget()

        slot.bind(old)
        slot.bind(fresh)
        slot.unbind(old)

        assertSame(fresh, slot.current())
    }
}
