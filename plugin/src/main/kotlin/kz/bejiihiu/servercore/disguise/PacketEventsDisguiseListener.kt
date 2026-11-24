package kz.bejiihiu.servercore.disguise

import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * packet-side guard for disguised pairs, scoped per (viewer, target):
 *
 * - metadata of the real player is captured then dropped, the viewer must
 *   not learn skin layers / health / pose of someone their client sees as a mob
 * - equipment updates of the real player are captured then dropped, so the
 *   mob never ends up holding the player's sword mid-disguise
 * - a tracker resync re-sending the real player spawn is cancelled and the
 *   same synthetic mob is re-issued (seeded per pair, so no model swap)
 *
 * runs on netty threads by design: only map lookups and wrapper reads here,
 * entity-state reads go through the scheduler. dropped packets carry the
 * captured state into [CapturedStateStore] for the reveal replay.
 */
class PacketEventsDisguiseListener(
    private val registry: DisguiseRegistry,
    private val captured: CapturedStateStore,
    private val scheduler: DisguiseScheduler,
    private val renderer: DisguiseRenderer,
    private val onPlayerGone: (UUID) -> Unit,
) : PacketListener {

    override fun onPacketSend(event: PacketSendEvent) {
        // pe hands the player over as the platform object; on spigot that is the bukkit player.
        // explicit getter call: the private field shadows property syntax here
        val viewer = (event.getPlayer() as? Player)?.uniqueId ?: return
        when (event.packetType ?: return) {
            PacketType.Play.Server.ENTITY_METADATA -> suppressMetadata(viewer, event)
            PacketType.Play.Server.ENTITY_EQUIPMENT -> suppressEquipment(viewer, event)
            PacketType.Play.Server.SPAWN_ENTITY -> suppressRealSpawn(viewer, event)
            else -> return
        }
    }

    override fun onUserDisconnect(event: UserDisconnectEvent) {
        val uuid = event.user?.uuid ?: return
        onPlayerGone(uuid)
        captured.clearFor(uuid)
    }

    private fun suppressMetadata(viewer: UUID, event: PacketSendEvent) {
        val wrapper = WrapperPlayServerEntityMetadata(event)
        val record = registry.findByRealEntityId(viewer, wrapper.entityId) ?: return
        captured.recordMetadata(viewer, record.target, wrapper.entityMetadata)
        event.isCancelled = true
    }

    private fun suppressEquipment(viewer: UUID, event: PacketSendEvent) {
        val wrapper = WrapperPlayServerEntityEquipment(event)
        val record = registry.findByRealEntityId(viewer, wrapper.entityId) ?: return
        captured.recordEquipment(viewer, record.target, wrapper.equipment)
        event.isCancelled = true
    }

    private fun suppressRealSpawn(viewer: UUID, event: PacketSendEvent) {
        val wrapper = WrapperPlayServerSpawnEntity(event)
        val record = registry.findByRealEntityId(viewer, wrapper.entityId) ?: return
        // hidden pairs lose the real spawn silently; disguised pairs get the
        // same seeded mob re-issued so the resync is invisible
        event.isCancelled = true
        if (record.status != DisguiseStatus.DISGUISED) return
        scheduler.run(record.target) { entity ->
            if (registry.get(viewer, record.target) !== record) return@run
            renderer.spawnDisguise(
                viewer, record.target, record.realEntityId, record.syntheticEntityId,
                SyntheticMobProfile.forMobType(record.mobTypeName), entity.snapshot,
            )
        }
    }
}
