package kz.bejiihiu.servercore.esp

import java.util.UUID

/** what a viewer's client should currently hold for a target */
enum class ViewState {
    /** normal entity representation */
    VISIBLE,

    /** the real player entity is swapped for a fake mob model, per-viewer */
    DISGUISED,

    /** entity removed from the viewer entirely (sneak hide, spec observation) */
    HIDDEN,
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
    fun apply(viewer: UUID, target: UUID, state: ViewState, disguise: DisguiseAssignment?)
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
