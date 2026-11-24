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
 * registered through ListenerRegistry.registerPermanent so it survives
 * /flectonepulse reload.
 */
class FlectonePulseStateListener(
    private val cache: PunishmentCache,
    private val onReloadFinished: () -> Unit,
) : PulseListener {

    @Pulse(priority = Event.Priority.MONITOR)
    fun onPlayerLoad(event: PlayerLoadEvent) {
        cache.invalidate(event.player().uuid())
    }

    @Pulse(priority = Event.Priority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        cache.invalidate(event.player().uuid())
    }

    @Pulse(priority = Event.Priority.MONITOR)
    fun onPlayerPersist(event: PlayerPersistAndDisposeEvent) {
        cache.invalidate(event.player().uuid())
    }

    @Pulse(priority = Event.Priority.MONITOR)
    fun onReload(event: ReloadEvent) {
        // end of a reload: the injector was rebuilt, cached punishment answers
        // and the telegram probe may both be stale
        if (event.type() != ReloadEvent.Type.END) return
        cache.invalidateAll()
        onReloadFinished()
    }

    @Pulse(priority = Event.Priority.MONITOR)
    fun onEnable(event: EnableEvent) {
        // fp came up after us (late enable race): same refresh as a reload end
        if (event.type() != EnableEvent.Type.READY) return
        cache.invalidateAll()
        onReloadFinished()
    }
}
