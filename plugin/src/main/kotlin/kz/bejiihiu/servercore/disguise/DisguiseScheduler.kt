package kz.bejiihiu.servercore.disguise

import java.util.UUID

/**
 * region-captured view of the target player: entity id + where they stood.
 * captured on the target's own entity scheduler, safe to hand anywhere after.
 */
data class TargetEntity(val entityId: Int, val snapshot: DisguiseSnapshot)

/**
 * runs work in a player's own region context. folia rule: entity state
 * (location, entity id freshness) is only safe to touch on the owning
 * entity's scheduler; packet sending itself is netty-safe from any thread.
 */
fun interface DisguiseScheduler {
    /** no-op when the player is offline - callers must tolerate that */
    fun run(target: UUID, block: (TargetEntity) -> Unit)
}
