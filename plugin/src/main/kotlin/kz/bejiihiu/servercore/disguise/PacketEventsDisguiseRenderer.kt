package kz.bejiihiu.servercore.disguise

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * keeps the last server-broadcast metadata/equipment of a disguised real
 * player, per pair, so a reveal can put the real entity back exactly as the
 * server last saw it. capture happens in the suppression listener; nothing
 * captured here ever reaches the viewer while the pair stays disguised.
 */
class CapturedStateStore {

    private val metadata = ConcurrentHashMap<DisguisePairKey, List<EntityData<*>>>()
    private val equipment = ConcurrentHashMap<DisguisePairKey, List<Equipment>>()

    fun recordMetadata(viewer: UUID, target: UUID, entries: List<EntityData<*>>) {
        metadata[DisguisePairKey(viewer, target)] = entries
    }

    fun recordEquipment(viewer: UUID, target: UUID, entries: List<Equipment>) {
        equipment[DisguisePairKey(viewer, target)] = entries
    }

    /** replay-once: taken out on reveal, gone afterwards */
    fun drainMetadata(viewer: UUID, target: UUID): List<EntityData<*>>? = metadata.remove(DisguisePairKey(viewer, target))

    fun drainEquipment(viewer: UUID, target: UUID): List<Equipment>? = equipment.remove(DisguisePairKey(viewer, target))

    fun clearFor(uuid: UUID) {
        metadata.keys.removeIf { it.viewer == uuid || it.target == uuid }
        equipment.keys.removeIf { it.viewer == uuid || it.target == uuid }
    }
}

/**
 * packetevents implementation of [DisguiseRenderer]: builds the synthetic
 * spawn / metadata / equipment packets and the real-entity destroy, and
 * sends them strictly to one viewer's channel.
 */
class PacketEventsDisguiseRenderer(
    private val captured: CapturedStateStore,
) : DisguiseRenderer {

    override fun spawnDisguise(
        viewer: UUID,
        target: UUID,
        realEntityId: Int,
        syntheticEntityId: Int,
        profile: SyntheticMobProfile,
        snapshot: DisguiseSnapshot,
    ) {
        val out = PacketEvents.getAPI().playerManager
        val v = Bukkit.getPlayer(viewer) ?: return
        val type = resolveEntityType(profile.mobTypeName) ?: return

        // 1) the synthetic mob appears where the player stood
        out.sendPacket(
            v,
            WrapperPlayServerSpawnEntity(
                syntheticEntityId,
                UUID.randomUUID(),
                type,
                Location(snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch),
                snapshot.yaw,
                0,
                null,
            ),
        )
        // 2) plausible mob defaults, deliberately zero player fields
        out.sendPacket(
            v,
            WrapperPlayServerEntityMetadata(syntheticEntityId, profile.metadata.map { it.toEntityData() }),
        )
        // 3) every wearable slot explicitly empty, so no real armor or held
        //    item can show up on the mob
        out.sendPacket(
            v,
            WrapperPlayServerEntityEquipment(
                syntheticEntityId,
                profile.clearedEquipment.map { Equipment(it.toPacketSlot(), ItemStack.EMPTY) },
            ),
        )
        // 4) the real player disappears for this viewer only; the server
        //    entity stays untouched
        out.sendPacket(v, WrapperPlayServerDestroyEntities(realEntityId))
    }

    override fun destroyEntities(viewer: UUID, entityIds: IntArray) {
        if (entityIds.isEmpty()) return
        val v = Bukkit.getPlayer(viewer) ?: return
        PacketEvents.getAPI().playerManager.sendPacket(v, WrapperPlayServerDestroyEntities(*entityIds))
    }

    override fun revealPlayer(viewer: UUID, target: UUID, realEntityId: Int, snapshot: DisguiseSnapshot) {
        val out = PacketEvents.getAPI().playerManager
        val v = Bukkit.getPlayer(viewer) ?: return
        val t = Bukkit.getPlayer(target) ?: return // target gone: nothing to respawn

        // the real player back at the captured spot; metadata + equipment the
        // listener captured while hidden are replayed so the client does not
        // render a default-skinned stranger
        out.sendPacket(
            v,
            WrapperPlayServerSpawnEntity(
                realEntityId,
                t.uniqueId,
                EntityTypes.PLAYER,
                Location(snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch),
                snapshot.yaw,
                0,
                null,
            ),
        )
        captured.drainMetadata(viewer, target)?.let {
            out.sendPacket(v, WrapperPlayServerEntityMetadata(realEntityId, it))
        }
        captured.drainEquipment(viewer, target)?.let {
            out.sendPacket(v, WrapperPlayServerEntityEquipment(realEntityId, it))
        }
    }

    private fun resolveEntityType(name: String): EntityType? {
        val normalized = name.trim().lowercase().removePrefix("minecraft:")
        return EntityTypes.getByName(normalized)
            ?: EntityTypes.getByName("minecraft:$normalized")
    }

    private fun MobMetadataEntry.toEntityData(): EntityData<*> = when (kind) {
        DisguiseMetadataKind.BYTE -> EntityData(index, EntityDataTypes.BYTE, value as Byte)
        DisguiseMetadataKind.INT -> EntityData(index, EntityDataTypes.INT, value as Int)
        DisguiseMetadataKind.FLOAT -> EntityData(index, EntityDataTypes.FLOAT, value as Float)
    }

    private fun DisguiseEquipmentSlot.toPacketSlot(): EquipmentSlot = when (this) {
        DisguiseEquipmentSlot.MAIN_HAND -> EquipmentSlot.MAIN_HAND
        DisguiseEquipmentSlot.OFF_HAND -> EquipmentSlot.OFF_HAND
        DisguiseEquipmentSlot.BOOTS -> EquipmentSlot.BOOTS
        DisguiseEquipmentSlot.LEGGINGS -> EquipmentSlot.LEGGINGS
        DisguiseEquipmentSlot.CHESTPLATE -> EquipmentSlot.CHEST_PLATE
        DisguiseEquipmentSlot.HELMET -> EquipmentSlot.HELMET
        DisguiseEquipmentSlot.BODY -> EquipmentSlot.BODY
    }
}
