package kz.bejiihiu.servercore.packet

import java.util.UUID

/**
 * the seam every packet-behaviour module hangs off: the raw packet listener
 * parses and routes, the applier decides. visibility engine, disguise layer
 * and the state-sync matrix all plug in here without touching packet code.
 */
interface ViewStateApplier {
    /**
     * a spawn packet for [target] is about to reach [viewer]'s client.
     * return the state to record for the pair, or null to leave the current
     * state alone (a fresh pair then defaults to visible).
     */
    fun onSpawn(viewer: UUID, target: UUID, entityId: Int): EntityViewState?

    /**
     * an entity data packet (metadata, equipment) about [target] is about to
     * reach [viewer]. true strips the packet from this client's stream.
     */
    fun shouldSuppressEntityData(viewer: UUID, target: UUID): Boolean

    /** [targets] no longer exist on this viewer's client */
    fun onDestroy(viewer: UUID, targets: Collection<UUID>) {}
}

/**
 * baseline behaviour, good enough to ship on its own:
 * - spawns keep whatever state already exists, fresh pairs become visible
 * - hidden targets get their data packets stripped — a client that was never
 *   shown the entity must not learn anything about it either
 * - destroys are bookkeeping only, the listener already cleaned the registry
 */
class DefaultViewStateApplier(private val registry: PacketStateRegistry) : ViewStateApplier {

    override fun onSpawn(viewer: UUID, target: UUID, entityId: Int): EntityViewState? = null

    override fun shouldSuppressEntityData(viewer: UUID, target: UUID): Boolean =
        registry.state(viewer, target) == EntityViewState.HIDDEN
}
