package kz.bejiihiu.servercore.packet

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import java.util.UUID

/**
 * the packetevents-facing layer: parses the handful of entity packets the
 * system cares about and hands decisions to a [ViewStateApplier]. keeps every
 * packetevents type mention in one place so unit tests can cover the logic
 * without the library on the classpath.
 *
 * exact 2.14 packet names (checked against the source): SPAWN_ENTITY,
 * SPAWN_PLAYER, DESTROY_ENTITIES, ENTITY_METADATA, ENTITY_EQUIPMENT,
 * ENTITY_TELEPORT, ENTITY_POSITION_SYNC.
 */
class EntityPacketListener(
    private val registry: PacketStateRegistry,
    private val applier: () -> ViewStateApplier,
) : PacketListenerAbstract(PacketListenerPriority.NORMAL) {

    override fun onPacketSend(event: PacketSendEvent) {
        when (event.packetType) {
            PacketType.Play.Server.SPAWN_ENTITY -> {
                val packet = WrapperPlayServerSpawnEntity(event)
                val target = packet.uuid.orElse(null) ?: return
                handleSpawn(event, event.user.uuid, packet.entityId, target)
            }

            PacketType.Play.Server.SPAWN_PLAYER -> {
                val packet = WrapperPlayServerSpawnPlayer(event)
                handleSpawn(event, event.user.uuid, packet.entityId, packet.uuid)
            }

            PacketType.Play.Server.ENTITY_METADATA -> {
                val packet = WrapperPlayServerEntityMetadata(event)
                handleEntityData(event, event.user.uuid, packet.entityId)
            }

            PacketType.Play.Server.ENTITY_EQUIPMENT -> {
                val packet = WrapperPlayServerEntityEquipment(event)
                handleEntityData(event, event.user.uuid, packet.entityId)
            }

            // a client that never saw the entity must not learn where it went either
            PacketType.Play.Server.ENTITY_TELEPORT -> {
                val packet = WrapperPlayServerEntityTeleport(event)
                handleEntityData(event, event.user.uuid, packet.entityId)
            }

            PacketType.Play.Server.ENTITY_POSITION_SYNC -> {
                val packet = WrapperPlayServerEntityPositionSync(event)
                handleEntityData(event, event.user.uuid, packet.id)
            }

            PacketType.Play.Server.DESTROY_ENTITIES -> {
                val packet = WrapperPlayServerDestroyEntities(event)
                val targets = registry.unbindEntityIds(event.user.uuid, packet.entityIds)
                applier().onDestroy(event.user.uuid, targets)
            }

            else -> return
        }
    }

    private fun handleSpawn(event: PacketSendEvent, viewer: UUID, entityId: Int, target: UUID) {
        // our own spawn is not ours to manage
        if (viewer == target) return
        registry.bindEntityId(viewer, entityId, target)
        applier().onSpawn(viewer, target, entityId)?.let { registry.set(viewer, target, it) }
        // a fresh PLAYER pair defaults to visible: record it explicitly so the
        // pair always has a state and hidden ones lose the spawn right here.
        // generic entity spawns get no row: rows only exist for suppression
        // and mobs are never suppressed — auto-rows for every mob would churn
        // the store and evict real player rows under the per-viewer cap
        if (event.packetType == PacketType.Play.Server.SPAWN_PLAYER &&
            registry.state(viewer, target) == null
        ) {
            registry.set(viewer, target, EntityViewState.VISIBLE)
        }
        if (registry.state(viewer, target) == EntityViewState.HIDDEN) event.setCancelled(true)
    }

    private fun handleEntityData(event: PacketSendEvent, viewer: UUID, entityId: Int) {
        val target = registry.targetOf(viewer, entityId) ?: return
        if (target == viewer) return
        if (applier().shouldSuppressEntityData(viewer, target)) event.setCancelled(true)
    }

    /**
     * a disconnecting viewer's id bindings and rows die with the connection -
     * nobody else cleans them, and the fresh client on reconnect gets its
     * picture rebuilt from the sync registry's policy rows, not from here.
     */
    override fun onUserDisconnect(event: UserDisconnectEvent) {
        registry.removeViewer(event.user.uuid)
    }
}
