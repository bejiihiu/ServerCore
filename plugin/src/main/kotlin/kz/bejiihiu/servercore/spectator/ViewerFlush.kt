package kz.bejiihiu.servercore.spectator

/**
 * the cadence of the live inventory viewer, as pure decisions.
 *
 * one flush cycle per viewer is just a question answered from a handful of
 * facts: is anybody still here, does the session still point at this target,
 * is the safety backstop due, did the target change, is the throttle open. the
 * loop in [InventoryViewerService] only gathers those facts, applies the
 * decision and does the scheduling - which keeps the rules (keep-alive,
 * backstop, throttle retry) testable without a server.
 */
internal sealed interface ViewerFlushStep {

    /** the viewer was already closed, forget the state */
    data object Drop : ViewerFlushStep

    /** tear the viewer down, [reason] lands in the log */
    data class Close(val reason: String) : ViewerFlushStep

    /** rebuild the whole window, bypasses the throttle */
    data object FullPush : ViewerFlushStep

    /** send only the changed slots */
    data object DiffPush : ViewerFlushStep

    /** nothing to do this cycle */
    data object Idle : ViewerFlushStep
}

internal data class ViewerFlushProbe(
    val closed: Boolean,
    val adminPresent: Boolean,
    val targetPresent: Boolean,
    val keepAliveDue: Boolean,
    /** the session still points at this viewer's target */
    val sessionAlive: Boolean,
    /** the slow safety backstop elapsed since the last full resync */
    val backstopDue: Boolean,
    val needsFull: Boolean,
    val dirty: Boolean,
    val throttleReady: Boolean,
)

internal object ViewerFlush {

    const val PLAYERS_LEFT = "player left"

    const val SESSION_MOVED = "spec session no longer targets this player"

    fun decide(probe: ViewerFlushProbe): ViewerFlushStep = when {
        probe.closed -> ViewerFlushStep.Drop
        // either party gone: nobody left to draw for
        !probe.adminPresent || !probe.targetPresent -> ViewerFlushStep.Close(PLAYERS_LEFT)
        // the session owns the truth about who is watched
        probe.keepAliveDue && !probe.sessionAlive -> ViewerFlushStep.Close(SESSION_MOVED)
        // a pending full resync wins over any diff: it carries everything the
        // diff would have carried plus the window identity the client may lack
        probe.backstopDue || probe.needsFull -> ViewerFlushStep.FullPush
        !probe.dirty -> ViewerFlushStep.Idle
        // throttled: the change stays pending, the next cycle retries
        !probe.throttleReady -> ViewerFlushStep.Idle
        else -> ViewerFlushStep.DiffPush
    }
}

/** what a diff push sends once the target's fresh snapshot landed */
internal sealed interface ViewerPush {

    /** per-slot updates are meaningless, rebuild the window instead */
    data object NeedsFull : ViewerPush

    /** nothing moved */
    data object Nothing : ViewerPush

    data class Slots(val indexes: List<Int>) : ViewerPush
}

internal object ViewerPushPlan {

    /**
     * a layout that moved (size mismatch) or a window identity that is not on
     * the wire yet cannot be addressed per slot; both fall back to the full
     * window packet, which is always correct.
     */
    fun forSnapshot(old: List<*>, new: List<*>, windowKnown: Boolean, stateKnown: Boolean): ViewerPush {
        if (SlotDiff.needsFullResync(old, new)) return ViewerPush.NeedsFull
        val changed = SlotDiff.changedSlots(old, new)
        if (changed.isEmpty()) return ViewerPush.Nothing
        if (!windowKnown || !stateKnown) return ViewerPush.NeedsFull
        return ViewerPush.Slots(changed)
    }
}
