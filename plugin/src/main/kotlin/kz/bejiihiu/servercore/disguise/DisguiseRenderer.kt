package kz.bejiihiu.servercore.disguise

import java.util.UUID

/**
 * the packet side of a disguise. implementors turn these calls into
 * client-bound packets for exactly one viewer; the real server entity is
 * never modified from here.
 */
interface DisguiseRenderer {

    /**
     * full disguise sequence for one viewer: synthetic mob spawn + plausible
     * mob metadata + all equipment slots cleared, then the real player entity
     * destroyed for this viewer only. spawn goes out before the destroy so
     * the viewer never sees a gap.
     */
    fun spawnDisguise(
        viewer: UUID,
        target: UUID,
        realEntityId: Int,
        syntheticEntityId: Int,
        profile: SyntheticMobProfile,
        snapshot: DisguiseSnapshot,
    )

    /** client-only removal of the given ids, no respawn of anything */
    fun destroyEntities(viewer: UUID, entityIds: IntArray)

    /**
     * de-disguise: the real player entity back for this viewer, with the
     * server-broadcast metadata/equipment the suppression listener captured
     * while the pair was disguised. callers order this after destroying the
     * synthetic entity, so the reveal replaces it without flicker.
     */
    fun revealPlayer(viewer: UUID, target: UUID, realEntityId: Int, snapshot: DisguiseSnapshot)
}
