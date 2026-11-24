package kz.bejiihiu.servercore.spectator

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import java.util.UUID
import kz.bejiihiu.servercore.packet.EntityViewState
import kz.bejiihiu.servercore.packet.PacketStateRegistry

/**
 * packetevents listener scoped to active observation pairs. the very first
 * check is a membership test: a client that is not being watched by anyone
 * costs one map lookup and nothing else, so this listener never fights the
 * shared entity listener or the disguise layer for unrelated traffic.
 *
 * what it adds on top of the shared entity listener (which already cancels
 * spawn/metadata/equipment/movement for HIDDEN rows):
 * - spawn packets about the hidden admin get a second, row-independent guard,
 *   and re-assert the HIDDEN row when the shared bookkeeping lost it
 * - player info updates about the hidden admin toward the target (the client
 *   must not re-learn the tab entry it was told to forget)
 * - admin-origin traces: particles, sounds, actionbars — the [TraceFilter]
 *   heuristic owns that decision
 * - the entity packets the shared listener has no opinion about (head look,
 *   animation, velocity, effects, item collect, damage) when they concern a
 *   hidden admin's entity id
 *
 * priority NORMAL with later registration than the shared listener means this
 * runs after it inside the same pipeline event: by the time a spawn packet
 * reaches this listener the shared side has already bound the entity id, so
 * the row re-assert below is deterministic.
 */
class SpecTraceListener(
    private val sessions: SpecSessionRegistry,
    private val registry: PacketStateRegistry,
    private val poses: (UUID) -> TracePos?,
    private val filter: TraceFilter,
) : PacketListenerAbstract(PacketListenerPriority.NORMAL) {

    override fun onPacketSend(event: PacketSendEvent) {
        val viewer = event.user.uuid
        // scope gate: only the client of a watched player pays for anything
        if (!sessions.isObserved(viewer)) return

        when (event.packetType) {
            PacketType.Play.Server.SPAWN_ENTITY -> {
                val spawned = WrapperPlayServerSpawnEntity(event).uuid.orElse(null) ?: return
                guardSpawn(event, viewer, spawned)
            }

            PacketType.Play.Server.SPAWN_PLAYER ->
                guardSpawn(event, viewer, WrapperPlayServerSpawnPlayer(event).uuid)

            PacketType.Play.Server.PLAYER_INFO_UPDATE -> {
                val packet = WrapperPlayServerPlayerInfoUpdate(event)
                val watchers = sessions.observersOf(viewer)
                if (packet.entries.any { watchers.contains(it.profileId) }) event.setCancelled(true)
            }

            PacketType.Play.Server.PARTICLE ->
                decide(event, TraceKind.PARTICLE, viewer, WrapperPlayServerParticle(event).position)

            PacketType.Play.Server.SOUND_EFFECT ->
                decide(event, TraceKind.SOUND, viewer, WrapperPlayServerSoundEffect(event).position)

            PacketType.Play.Server.ACTION_BAR ->
                decide(event, TraceKind.ACTION_BAR, viewer, null)

            PacketType.Play.Server.ENTITY_METADATA ->
                guardEntityData(event, viewer, WrapperPlayServerEntityMetadata(event).entityId)

            PacketType.Play.Server.ENTITY_EQUIPMENT ->
                guardEntityData(event, viewer, WrapperPlayServerEntityEquipment(event).entityId)

            PacketType.Play.Server.ENTITY_TELEPORT ->
                guardEntityData(event, viewer, WrapperPlayServerEntityTeleport(event).entityId)

            PacketType.Play.Server.ENTITY_POSITION_SYNC ->
                guardEntityData(event, viewer, WrapperPlayServerEntityPositionSync(event).id)

            PacketType.Play.Server.ENTITY_HEAD_LOOK ->
                guardEntityData(event, viewer, WrapperPlayServerEntityHeadLook(event).entityId)

            PacketType.Play.Server.ENTITY_ANIMATION ->
                guardEntityData(event, viewer, WrapperPlayServerEntityAnimation(event).entityId)

            PacketType.Play.Server.ENTITY_VELOCITY ->
                guardEntityData(event, viewer, WrapperPlayServerEntityVelocity(event).entityId)

            PacketType.Play.Server.ENTITY_EFFECT ->
                guardEntityData(event, viewer, WrapperPlayServerEntityEffect(event).entityId)

            PacketType.Play.Server.REMOVE_ENTITY_EFFECT ->
                guardEntityData(event, viewer, WrapperPlayServerRemoveEntityEffect(event).entityId)

            PacketType.Play.Server.COLLECT_ITEM -> {
                // the hidden admin can appear as either side of a pickup
                val packet = WrapperPlayServerCollectItem(event)
                guardEntityData(event, viewer, packet.collectedEntityId)
                if (!event.isCancelled) guardEntityData(event, viewer, packet.collectorEntityId)
            }

            PacketType.Play.Server.DAMAGE_EVENT ->
                guardEntityData(event, viewer, WrapperPlayServerDamageEvent(event).entityId)

            PacketType.Play.Server.HURT_ANIMATION ->
                guardEntityData(event, viewer, WrapperPlayServerHurtAnimation(event).entityId)

            else -> {}
        }
    }

    private fun guardSpawn(event: PacketSendEvent, viewer: UUID, spawned: UUID) {
        if (spawned == viewer) return
        if (!sessions.isObserving(spawned, viewer)) return
        // belt and braces: the row is the shared listener's decision input,
        // re-assert it even when some race dropped it, bind the id so later
        // data packets stay attributable, then kill the packet. whether this
        // runs before or after the shared listener stops mattering: both
        // orders leave the registry in the same shape
        registry.set(viewer, spawned, EntityViewState.HIDDEN)
        registry.bindEntityId(viewer, readSpawnEntityId(event), spawned)
        event.setCancelled(true)
    }

    private fun readSpawnEntityId(event: PacketSendEvent): Int = when (event.packetType) {
        PacketType.Play.Server.SPAWN_ENTITY -> WrapperPlayServerSpawnEntity(event).entityId
        else -> WrapperPlayServerSpawnPlayer(event).entityId
    }

    private fun guardEntityData(event: PacketSendEvent, viewer: UUID, entityId: Int) {
        val owner = registry.targetOf(viewer, entityId) ?: return
        if (!ObserverScope.isHiddenPair(owner, viewer, sessions)) return
        event.setCancelled(true)
    }

    private fun decide(event: PacketSendEvent, kind: TraceKind, viewer: UUID, position: Vector3d?) {
        val packetPos = position?.let { TracePos(it.x, it.y, it.z) }
        // several admins may watch the same target: drop when any of their
        // captured positions matches the heuristic
        val drop = sessions.observersOf(viewer).any { admin ->
            filter.decide(kind, poses(admin), packetPos) == TraceDecision.DROP
        }
        if (drop) event.setCancelled(true)
    }
}
