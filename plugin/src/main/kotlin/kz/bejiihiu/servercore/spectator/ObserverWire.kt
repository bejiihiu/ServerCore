package kz.bejiihiu.servercore.spectator

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kz.bejiihiu.servercore.packet.EntityViewState
import kz.bejiihiu.servercore.packet.PacketService
import kz.bejiihiu.servercore.packet.PacketStateRegistry
import kz.bejiihiu.servercore.sync.StateInvalidator
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * the row view of the hiding layer: the per-viewer packet state registry plus
 * the optional sync mirror. the hiding bridge is the single writer here.
 */
interface ObserverRows {
    fun set(viewer: UUID, target: UUID, state: EntityViewState)

    fun bindEntityId(viewer: UUID, entityId: Int, target: UUID)
}

/** production rows: packet registry as the authority, sync registry as the mirror */
class RegistryObserverRows(
    private val registry: PacketStateRegistry,
    private val syncStates: StateInvalidator? = null,
) : ObserverRows {

    override fun set(viewer: UUID, target: UUID, state: EntityViewState) {
        registry.set(viewer, target, state)
        // packet + sync state are the same canonical type now, one row each
        syncStates?.set(viewer, target, state)
    }

    override fun bindEntityId(viewer: UUID, entityId: Int, target: UUID) {
        registry.bindEntityId(viewer, entityId, target)
    }
}

/**
 * player and wire side of [ObserverHider]: live handles, entity ids, the
 * scheduler hops and the actual packet bursts. everything here needs a running
 * server; the hider's lifecycle decisions do not, which is why they live apart.
 */
interface ObserverWire {

    /** true while the packet layer is attached to packetevents */
    val online: Boolean

    /** the player's entity id, null when the server no longer tracks them */
    fun entityId(player: UUID): Int?

    /** true while [player] is online and tracked */
    fun isPresent(player: UUID): Boolean

    /**
     * bind the id and remove the admin from the viewer's client: entity
     * destroy plus tab removal, both silent (our own packets must not flow
     * back through the shared entity listener).
     */
    fun hideFrom(viewer: UUID, entityId: Int, admin: UUID)

    /**
     * capture the admin live on their own entity scheduler and send the full
     * restore burst. [claimRow] runs right before the burst, on the admin's
     * thread, with the admin's entity id - the caller flips the registry row
     * there so the shared listener lets the burst through. when nothing can be
     * captured the row is never touched.
     */
    fun restoreTo(viewer: UUID, admin: UUID, claimRow: (entityId: Int) -> Unit)

    /**
     * shutdown-flavoured restore: no scheduler hop (schedulers are going
     * down), the last captured snapshot stands in for a live capture.
     */
    fun restoreBestEffort(viewer: UUID, admin: UUID, claimRow: (entityId: Int) -> Unit)

    /** start pose tracking for trace attribution; null when the admin is gone */
    fun trackPose(admin: UUID, sink: (TracePos) -> Unit): AutoCloseable?
}

/**
 * packetevents implementation of the wire, folia-correct: every player read
 * happens on that player's own entity scheduler and every send goes through
 * packetevents (netty-safe from any thread).
 *
 * the restore is vanilla-faithful: tab entry back first (the client resolves
 * the skin from it), then spawn, fresh metadata, a full six-slot equipment
 * resync and head rotation.
 */
class PacketObserverWire(
    private val plugin: JavaPlugin,
    private val packets: PacketService,
) : ObserverWire {

    /** last fully captured admin snapshot per admin, the shutdown-restore fallback */
    private val snapshots = ConcurrentHashMap<UUID, Snapshot>()

    override val online: Boolean get() = packets.online

    override fun entityId(player: UUID): Int? = plugin.server.getPlayer(player)?.entityId

    override fun isPresent(player: UUID): Boolean = plugin.server.getPlayer(player) != null

    override fun hideFrom(viewer: UUID, entityId: Int, admin: UUID) {
        val viewerPlayer = plugin.server.getPlayer(viewer) ?: return
        packets.registry.bindEntityId(viewer, entityId, admin)
        emitSilently(viewerPlayer, WrapperPlayServerDestroyEntities(entityId))
        emitSilently(viewerPlayer, WrapperPlayServerPlayerInfoRemove(admin))
    }

    override fun restoreTo(viewer: UUID, admin: UUID, claimRow: (entityId: Int) -> Unit) {
        val viewerPlayer = plugin.server.getPlayer(viewer) ?: return
        packets.threads.forPlayer(admin) { a ->
            val snap = capture(a) ?: return@forPlayer
            snapshots[admin] = snap
            // the row flips before the burst so the shared listener lets
            // every packet of the restore through
            claimRow(snap.entityId)
            emit(viewerPlayer, tabAdd(snap))
            // 1.15+ spawn packets carry no metadata, the list stays empty and
            // the real values ride the metadata packet right after
            emit(viewerPlayer, WrapperPlayServerSpawnPlayer(snap.entityId, admin, snap.location, emptyList()))
            emit(viewerPlayer, WrapperPlayServerEntityMetadata(snap.entityId, snap.metadata))
            emit(viewerPlayer, WrapperPlayServerEntityEquipment(snap.entityId, snap.equipment))
            emit(viewerPlayer, WrapperPlayServerEntityHeadLook(snap.entityId, snap.yaw))
        }
    }

    override fun restoreBestEffort(viewer: UUID, admin: UUID, claimRow: (entityId: Int) -> Unit) {
        val viewerPlayer = plugin.server.getPlayer(viewer) ?: return
        val live = plugin.server.getPlayer(admin)
        val snap = live?.let { runCatching { capture(it) }.getOrNull() } ?: snapshots[admin]
        if (snap == null) {
            // nothing ever captured: at least the tab entry had a name to
            // come back with, without it there is nothing honest to restore
            return
        }
        claimRow(snap.entityId)
        emit(viewerPlayer, tabAdd(snap))
        emit(viewerPlayer, WrapperPlayServerSpawnPlayer(snap.entityId, admin, snap.location, emptyList()))
        emit(viewerPlayer, WrapperPlayServerEntityMetadata(snap.entityId, snap.metadata))
        emit(viewerPlayer, WrapperPlayServerEntityEquipment(snap.entityId, snap.equipment))
        emit(viewerPlayer, WrapperPlayServerEntityHeadLook(snap.entityId, snap.yaw))
    }

    override fun trackPose(admin: UUID, sink: (TracePos) -> Unit): AutoCloseable? {
        val player = plugin.server.getPlayer(admin) ?: return null
        val task = player.scheduler.runAtFixedRate(
            plugin,
            { _ ->
                val p = plugin.server.getPlayer(admin) ?: return@runAtFixedRate
                // own entity scheduler: the only correct thread for a location read
                val loc = p.location
                sink(TracePos(loc.x, loc.y, loc.z))
            },
            null,
            POSE_PERIOD_TICKS,
            POSE_PERIOD_TICKS,
        ) ?: return null
        return AutoCloseable { task.cancel() }
    }

    /** live read of everything a faithful restore needs, on the admin's thread */
    private fun capture(admin: Player): Snapshot? {
        val metadata = runCatching { SpigotConversionUtil.getEntityMetadata(admin) }.getOrNull()
        val profile = runCatching { admin.playerProfile }.getOrNull()
        if (metadata == null || profile == null) return null
        val inv = admin.inventory
        val equipment = listOf(
            Equipment(EquipmentSlot.MAIN_HAND, SpigotConversionUtil.fromBukkitItemStack(inv.itemInMainHand)),
            Equipment(EquipmentSlot.OFF_HAND, SpigotConversionUtil.fromBukkitItemStack(inv.itemInOffHand)),
            Equipment(EquipmentSlot.HELMET, SpigotConversionUtil.fromBukkitItemStack(inv.helmet)),
            Equipment(EquipmentSlot.CHEST_PLATE, SpigotConversionUtil.fromBukkitItemStack(inv.chestplate)),
            Equipment(EquipmentSlot.LEGGINGS, SpigotConversionUtil.fromBukkitItemStack(inv.leggings)),
            Equipment(EquipmentSlot.BOOTS, SpigotConversionUtil.fromBukkitItemStack(inv.boots)),
        )
        val textures = profile.properties.map { TextureProperty(it.name, it.value, it.signature) }
        val loc = admin.location
        return Snapshot(
            admin = admin.uniqueId,
            entityId = admin.entityId,
            name = admin.name,
            location = SpigotConversionUtil.fromBukkitLocation(loc),
            yaw = loc.yaw,
            metadata = metadata,
            equipment = equipment,
            gameMode = GameMode.getById(admin.gameMode.value),
            latency = admin.ping,
            textures = textures,
        )
    }

    private fun tabAdd(snap: Snapshot): WrapperPlayServerPlayerInfoUpdate {
        val profile = UserProfile(snap.admin, snap.name, snap.textures)
        val entry = WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            profile, true, snap.latency, snap.gameMode, null, null,
        )
        return WrapperPlayServerPlayerInfoUpdate(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, entry)
    }

    /** regular send: listener-visible, fine for restore traffic */
    private fun emit(viewer: Player, wrapper: PacketWrapper<*>) {
        runCatching {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper)
        }
    }

    /**
     * silent send: bypasses every packetevents listener. reserved for the hide
     * direction, where the shared listener's destroy bookkeeping would race
     * with the row this sequence just wrote.
     */
    private fun emitSilently(viewer: Player, wrapper: PacketWrapper<*>) {
        runCatching {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, wrapper)
        }
    }

    private data class Snapshot(
        val admin: UUID,
        val entityId: Int,
        val name: String,
        val location: Location,
        val yaw: Float,
        val metadata: List<EntityData<*>>,
        val equipment: List<Equipment>,
        val gameMode: GameMode,
        val latency: Int,
        val textures: List<TextureProperty>,
    )

    private companion object {
        const val POSE_PERIOD_TICKS = 10L
    }
}
