package kz.bejiihiu.servercore.esp

import java.util.UUID

/**
 * the one canonical per-(viewer, target) client view state. every other
 * module aliases this type; there is exactly one enum in the codebase.
 *
 * [DEAD] is produced only by the packet-state sync layer: a dead target must
 * never keep rendering as a disguised zombie, so death pins the pair into
 * DEAD until respawn re-applies the real policy. the visibility engine never
 * emits it — its exclusions already fall back to vanilla for dead targets.
 */
enum class ViewState {
    /** normal entity representation */
    VISIBLE,

    /** the real player entity is swapped for a fake mob model, per-viewer */
    DISGUISED,

    /** entity removed from the viewer entirely (sneak hide, spec observation) */
    HIDDEN,

    /** target is dead: disguise forcibly removed, vanilla death is shown */
    DEAD,
}

/** the fake mob a disguised target shows as; [entityType] is a bukkit EntityType name */
data class DisguiseAssignment(val entityType: String)

/**
 * seam between the visibility engine and the packet layer. the real writer lives
 * in the packetevents module and registers itself at boot; until then the no-op
 * keeps this side compiling and testable standalone.
 *
 * contract: called from engine (global region) and raytrace (region) threads,
 * so implementations must be thread-safe and cheap. re-application of an
 * identical verdict must be tolerated.
 */
interface ViewStateApplier {
    fun apply(viewer: UUID, target: UUID, state: ViewState, assignment: DisguiseAssignment?)
}

/** fallback applier, does exactly nothing */
object NoopViewStateApplier : ViewStateApplier {
    override fun apply(viewer: UUID, target: UUID, state: ViewState, disguise: DisguiseAssignment?) {
        // deliberate no-op: the engine stays correct with no packets flowing at all
    }
}

/** hook for the /spec module: an admin under observation is hidden from their target */
fun interface SpecObserverSource {
    fun isObserving(uuid: UUID): Boolean

    companion object {
        /** default source that reports nobody observing */
        val NONE = SpecObserverSource { false }
    }
}
