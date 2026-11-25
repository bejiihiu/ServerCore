package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.UUID
import net.flectone.pulse.platform.registry.ListenerRegistry

/**
 * everything the permanent flectonepulse listener is allowed to touch: one
 * live bridge and nothing else. an unbound slot closes the whole surface, so a
 * callback that arrives after our shutdown has nothing to call.
 */
interface FlectonePulseListenerTarget {

    /** flectonepulse reloaded this player's data: drop the cached answers */
    fun invalidatePlayer(uuid: UUID)

    /** flectonepulse reloaded or finished enabling: caches are stale and the services moved */
    fun afterFpRestarted()
}

/**
 * the indirection the permanent flectonepulse listener keeps between
 * flectonepulse and the bridge that lives right now.
 *
 * flectonepulse has no api to drop a single listener: [ListenerRegistry]
 * exposes `registerPermanent` and `unregisterAll`, and `unregisterAll` also
 * takes flectonepulse's own listeners down, so it is not ours to call. a
 * listener handed to flectonepulse is therefore there for as long as its
 * registry lives — verified against the 1.15.2 source, where a reload only
 * clears the dispatch map and re-registers the permanent set afterwards; the
 * set itself is never trimmed. plain `register` is not an alternative: those
 * handlers are dropped by a reload and never come back.
 *
 * so the listener holds nothing but this slot, and [unbind] — run from the
 * bridge teardown — clears it. the callbacks flectonepulse keeps delivering
 * after our disable then find nothing to do: no cache, no scheduler, no
 * service of a disabled plugin is touched, and nothing throws into
 * flectonepulse's event thread. binding the next bridge on re-enable makes the
 * same surviving listener live again, which is why an enable/disable cycle in
 * one classloader never needs a second registration.
 *
 * what this cannot fix: the listener object itself, and with it this class's
 * classloader, stays referenced by flectonepulse's registry until flectonepulse
 * is recreated. it is inert, but a full plugin reload leaves one such object
 * behind. that is the price of flectonepulse's api, not a leak we can plug.
 */
class FlectonePulseListenerSlot {

    @Volatile
    private var target: FlectonePulseListenerTarget? = null

    private var registeredWith: ListenerRegistry? = null

    private val listener = FlectonePulseStateListener(this)

    /** the live target, or null after [unbind] — callers must do nothing then */
    fun current(): FlectonePulseListenerTarget? = target

    fun bind(target: FlectonePulseListenerTarget) {
        this.target = target
    }

    /** identity-scoped: a bridge that already lost the slot cannot clear a newer one */
    fun unbind(target: FlectonePulseListenerTarget) {
        if (this.target === target) this.target = null
    }

    /**
     * registers the one permanent listener, at most once per registry instance.
     * flectonepulse's registry bean is a singleton that survives
     * `/flectonepulse reload`, so the same instance means "our listener is
     * still in there" — registering again would append a second copy of every
     * handler, since the permanent set dedupes the listener but the dispatch
     * registration runs anyway. a different instance means flectonepulse was
     * recreated and its fresh, empty registry needs the listener once more.
     */
    fun ensureRegistered(registry: ListenerRegistry) {
        synchronized(this) {
            if (registeredWith === registry) return
            registry.registerPermanent(listener)
            registeredWith = registry
        }
    }
}
