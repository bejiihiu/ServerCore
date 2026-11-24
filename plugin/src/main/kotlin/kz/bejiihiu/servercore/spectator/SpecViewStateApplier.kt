package kz.bejiihiu.servercore.spectator

import java.util.UUID
import kz.bejiihiu.servercore.esp.DisguiseAssignment
import kz.bejiihiu.servercore.esp.ViewState

/**
 * the esp engine's applier contract, implemented for the admin-observer case.
 *
 * while a spec session is active, the view state of the observing admin as
 * seen by the observed target is HIDDEN — full stop. the engine's own verdict
 * for that pair is overridden here because a spectator admin falls into the
 * vanilla spectator branch of the decider and comes out VISIBLE, which would
 * punch straight through the hide.
 *
 * every other pair is passed straight to the delegate untouched, so this
 * composes with the real packet writer instead of replacing it: wire it as
 * the engine's applier with the previous applier as the delegate.
 */
class SpecViewStateApplier(
    private val sessions: SpecSessionRegistry,
    private val hider: ObserverHider,
    private val delegate: kz.bejiihiu.servercore.esp.ViewStateApplier,
) : kz.bejiihiu.servercore.esp.ViewStateApplier {

    override fun apply(viewer: UUID, target: UUID, state: ViewState, disguise: DisguiseAssignment?) {
        val observed = sessions.observedTarget(target)
        if (observed != null && viewer == observed) {
            // the observed player holds no representation of the admin at
            // all, whatever the engine decided for the pair; hide() is
            // idempotent so engine re-applies are free
            hider.hide(admin = target, target = viewer)
            return
        }
        delegate.apply(viewer, target, state, disguise)
    }
}
