package kz.bejiihiu.servercore.sync

import java.util.UUID
import java.util.logging.Logger
import kz.bejiihiu.servercore.esp.DisguiseAssignment
import kz.bejiihiu.servercore.esp.ViewState
import kz.bejiihiu.servercore.esp.ViewStateApplier
import kz.bejiihiu.servercore.packet.PacketStateRegistry

/**
 * the packetevents writer behind the [ResyncExecutor] seam, and at the same
 * time the bridge the esp engine's verdicts flow through: the class implements
 * the engine's [ViewStateApplier] contract too, so one object is the single
 * path a visibility decision takes from decision to client.
 *
 * two state stores are kept aligned here, on purpose:
 * - [states] (the sync module's policy registry) holds the *policy* rows —
 *   what the engine decided for a pair, overrides only, VISIBLE pairs get no
 *   row. the sync coordinator reads and invalidates these.
 * - the packet [registry] holds the *applied* picture — what the client
 *   currently holds, so the shared entity listener suppresses spawns and
 *   entity data for hidden pairs even before the packet work lands.
 *
 * threading: callers come from folia region threads (engine cycle, sync
 * transitions) and netty threads never touch this class. entity work inside
 * the disguise applier hops to schedulers on its own; the packet registry is
 * concurrent. nothing here touches a bukkit world directly.
 */
class PacketResyncExecutor(
    private val registry: PacketStateRegistry,
    private val states: StateInvalidator,
    /** the disguise service, null when the disguise layer is off or pe is absent */
    private val disguise: ViewStateApplier?,
    private val logger: Logger? = null,
) : ResyncExecutor, ViewStateApplier {

    // ---- engine verdict side ----

    /**
     * one engine verdict for one ordered pair: record the policy row (visible
     * clears it — the registry holds overrides only), then execute.
     */
    override fun apply(viewer: UUID, target: UUID, state: ViewState, assignment: DisguiseAssignment?) {
        if (viewer == target) return
        // engine snapshots trail the death event by up to a check interval, so
        // a settle computed from a pre-death snapshot must not overwrite the
        // DEAD pin or put a disguise back onto a corpse. respawn is the only
        // path allowed to clear the pin, and it runs through resyncTarget
        if (state != ViewState.DEAD && states.get(viewer, target) == ViewState.DEAD) return
        when (state) {
            ViewState.VISIBLE -> states.clear(viewer, target)
            else -> states.set(viewer, target, state)
        }
        execute(viewer, target, state)
    }

    // ---- resync executor side ----

    override fun resetViewer(viewer: UUID) {
        // the contract says drop the applied overrides and let vanilla resend.
        // dropping without re-asserting would flash every hidden pair visible
        // while the client re-tracks, so the drop and the re-assert collapse
        // into one idempotent step: policy rows stay, execution re-runs
        reassertViewer(viewer)
    }

    override fun resyncViewer(viewer: UUID) {
        reassertViewer(viewer)
    }

    override fun resetTarget(viewer: UUID, target: UUID) {
        // drop the applied overrides for this pair: vanilla truth goes out,
        // the caller's resyncTarget re-applies the policy right after
        restoreVanilla(viewer, target)
    }

    override fun resyncTarget(viewer: UUID, target: UUID): ViewState? {
        val state = states.get(viewer, target) ?: return null
        return execute(viewer, target, state)
    }

    override fun restoreVanilla(viewer: UUID, target: UUID) {
        execute(viewer, target, ViewState.VISIBLE)
    }

    // ---- internals ----

    private fun reassertViewer(viewer: UUID) {
        states.targetsOf(viewer).forEach { target ->
            states.get(viewer, target)?.let { execute(viewer, target, it) }
        }
    }

    /**
     * push one state into the applied picture: packet registry row first
     * (suppression must be in place before any packet work), then the
     * disguise applier for the actual client-side spawns/destroys. returns
     * the state that was actually applied, so the caller can keep its cached
     * row in sync with reality — a DEAD pin, for instance, lands on the
     * client as plain vanilla and is reported as such.
     */
    private fun execute(viewer: UUID, target: UUID, state: ViewState): ViewState {
        // no disguise layer: a decided disguise degrades to hiding. fail
        // closed, the same pose the engine holds while geometry is unknown
        val effective = if (state == ViewState.DISGUISED && disguise == null) ViewState.HIDDEN else state
        // the packet layer has no DEAD concept: a dead pair is applied as the
        // real (dead) entity, which is exactly what vanilla should show
        val applied = if (effective == ViewState.DEAD) ViewState.VISIBLE else effective
        when (applied) {
            ViewState.VISIBLE ->
                // only meaningful when an override was applied before; writing
                // a plain visible row for every vanilla pair is pure churn
                if (registry.state(viewer, target) != null) {
                    registry.set(viewer, target, ViewState.VISIBLE)
                }
            ViewState.HIDDEN, ViewState.DISGUISED -> registry.set(viewer, target, applied)
            // unreachable: DEAD is mapped to VISIBLE above, the branch only
            // keeps the when exhaustive over the canonical enum
            ViewState.DEAD -> {}
        }
        when (applied) {
            ViewState.VISIBLE -> disguise?.apply(viewer, target, ViewState.VISIBLE, null)
            else -> disguise?.apply(viewer, target, applied, null)
        }
        if (state != applied) {
            logger?.fine("verdict $state for $viewer -> $target applied as $applied")
        }
        return applied
    }
}
