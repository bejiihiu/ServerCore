package kz.bejiihiu.servercore.integration.flectonepulse

import net.flectone.pulse.annotation.Pulse
import net.flectone.pulse.listener.PulseListener
import net.flectone.pulse.model.event.Event
import net.flectone.pulse.model.event.lifecycle.EnableEvent
import net.flectone.pulse.model.event.lifecycle.ReloadEvent
import net.flectone.pulse.model.event.player.PlayerLoadEvent
import net.flectone.pulse.model.event.player.PlayerPersistAndDisposeEvent
import net.flectone.pulse.model.event.player.PlayerQuitEvent

/**
 * flectonepulse-side listener keeping servercore's local punishment cache and
 * telegram suppression decision in sync with flectonepulse's reality.
 *
 * the public api exposes no dedicated punishment events (verified against the
 * 1.15.2 event reference: message / player / lifecycle / module events only),
 * so punishment state is pull-based and these listeners do the invalidation:
 * whenever fp reloads a player's data we drop the cached answer and re-read
 * on the next query. handlers never touch the bukkit api — fp may call them
 * from its own threads.
 *
 * registered once through ListenerRegistry.registerPermanent, which is the only
 * registration fp keeps across its reloads. it also cannot be taken back: fp
 * has no unregister-one api, so this object outlives a servercore disable and
 * keeps receiving events. that is what the slot is for — every handler starts
 * by asking it for a live target and returns immediately when there is none,
 * so a callback after our shutdown is a pure no-op instead of a call into a
 * dead bridge (and never a `runDelayed` on a retired plugin).
 */
class FlectonePulseStateListener(
    private val slot: FlectonePulseListenerSlot,
) : PulseListener {

    @Pulse(priority = Event.Priority.MONITOR)
    fun onPlayerLoad(event: PlayerLoadEvent) {
        slot.current()?.invalidatePlayer(event.player().uuid())
    }

    @Pulse(priority = Event.Priority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        slot.current()?.invalidatePlayer(event.player().uuid())
    }

    @Pulse(priority = Event.Priority.MONITOR)
    fun onPlayerPersist(event: PlayerPersistAndDisposeEvent) {
        slot.current()?.invalidatePlayer(event.player().uuid())
    }

    @Pulse(priority = Event.Priority.MONITOR)
    fun onReload(event: ReloadEvent) {
        // end of a reload: cached punishment answers and the telegram probe may
        // both be stale. start is skipped — nothing has changed yet
        if (event.type() != ReloadEvent.Type.END) return
        slot.current()?.afterFpRestarted()
    }

    @Pulse(priority = Event.Priority.MONITOR)
    fun onEnable(event: EnableEvent) {
        // fp came up after us (late enable race): same refresh as a reload end
        if (event.type() != EnableEvent.Type.READY) return
        slot.current()?.afterFpRestarted()
    }
}
