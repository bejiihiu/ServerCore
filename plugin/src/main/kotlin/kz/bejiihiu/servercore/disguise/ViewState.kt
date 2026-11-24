package kz.bejiihiu.servercore.disguise

import java.util.UUID

// local copy of the esp branch's decision types. the canonical definition
// lives on feature/pe-esp; keep this signature-identical so the merge is a
// pure file dedup with no code changes

enum class EntityViewState {
    VISIBLE,
    DISGUISED,
    HIDDEN,
}

/** what the visibility engine calls for one (viewer, target) pair */
fun interface ViewStateApplier {
    fun apply(viewer: UUID, target: UUID, state: EntityViewState)
}

/** where the target stood when a state transition fired */
data class DisguiseSnapshot(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)
