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
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kz.bejiihiu.servercore.packet.EntityViewState
import kz.bejiihiu.servercore.packet.PacketService
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * the hidden observer packet layer for /spec sessions.
 *
 * while an admin observes a target, the admin's client-side entity is removed
 * from the target's view (destroy), every spawn/data packet about the admin is
 * suppressed toward the target (the shared entity listener plus the scoped
 * [SpecTraceListener], keyed off the HIDDEN row written here), and the admin
 * drops out of the target's tab list. on session end everything goes back:
 * player info add, spawn player with fresh metadata and a full equipment
 * resync — captured live off the admin's own entity scheduler, folia-correct.
 *
 * all of this is per-viewer by construction: only the observed target's
 * client is touched, nobody else's picture of the admin changes.
 *
 * wrapper names were checked against the packetevents 2.14.0 sources
 * (PLAYER_INFO_REMOVE / PLAYER_INFO_UPDATE, SPAWN_PLAYER, ENTITY_METADATA,
 * ENTITY_EQUIPMENT, ENTITY_HEAD_LOOK, DESTROY_ENTITIES).
 */
class ObserverHider(
    private val plugin: JavaPlugin,
    private val packets: PacketService,
    private val sessions: SpecSessionRegistry,
    private val filter: TraceFilter,
    /**
     * optional sync seam: when the state-sync module is up, observation rows
     * are mirrored into its registry so a future merge sees one picture.
     * standalone (null) the packet registry alone carries the state.
     */
    private val syncStates: kz.bejiihiu.servercore.sync.StateInvalidator? = null,
) {

    /** last captured admin position, keyed by admin uuid, feeds trace attribution */
    private val poses = ConcurrentHashMap<UUID, TracePos>()

    /** per-admin pose refresh task, cancelled when their session ends */
    private val poseTasks = ConcurrentHashMap<UUID, ScheduledTask>()

    /** last fully captured admin snapshot per admin, the shutdown-restore fallback */
    private val snapshots = ConcurrentHashMap<UUID, Snapshot>()

    // ---- session lifecycle ----

    /** open the hiding for a pair. called by the session owner at spec start. */
    fun begin(admin: UUID, target: UUID) {
        if (admin == target) return
        sessions.register(admin, target)
        mirror(target, admin, EntityViewState.HIDDEN)
        trackPose(admin)
        hide(admin, target)
    }

    /** close the hiding for a pair and restore the target's client. */
    fun end(admin: UUID, target: UUID) {
        sessions.unregister(admin, target)
        stopPoseTracking(admin)
        mirror(target, admin, EntityViewState.VISIBLE)
        restore(admin, target)
    }

    /**
     * either side of an active pair hit a state transition (death, respawn,
     * world change, reconnect). the hide sequence is idempotent, so a full
     * re-assert is the whole policy: destroy is harmless on a client that
     * holds no entity, the row write is idempotent, the tab remove is
     * idempotent. restore deliberately does NOT run here — a transitioning
     * client gets the hide, the session owner decides when it ends.
     */
    fun resync(uuid: UUID) {
        sessions.observedTarget(uuid)?.let { hide(uuid, it) }
        sessions.observersOf(uuid).forEach { hide(it, uuid) }
    }

    /**
     * plugin disable: restore every active pair in deterministic order
     * (admin, then target — same policy as the sync module), then wipe the
     * registries. restore is best-effort this late in the lifecycle; see
     * [restoreBestEffort].
     */
    fun shutdown() {
        RestorePlan.ordered(sessions.allPairs()).forEach { (admin, target) ->
            sessions.unregister(admin, target)
            stopPoseTracking(admin)
            mirror(target, admin, EntityViewState.VISIBLE)
            restoreBestEffort(admin, target)
        }
        poseTasks.values.forEach { it.cancel() }
        poseTasks.clear()
        poses.clear()
        snapshots.clear()
    }

    // ---- hide / restore ----

    /**
     * idempotent hide sequence, safe to re-run from any resync path. the
     * destroy and the tab removal go out silently: our own packets must not
     * flow back through the shared entity listener, whose destroy bookkeeping
     * would otherwise unbind the id and drop the very row this sequence just
     * wrote — a race between a netty write and our registry writes is exactly
     * the kind of thing that resurrects the admin for one tick.
     */
    fun hide(admin: UUID, target: UUID) {
        if (!packets.online) return
        val adminPlayer = plugin.server.getPlayer(admin) ?: return
        val viewer = plugin.server.getPlayer(target) ?: return
        // entity id is fixed for the life of the connection, an int read
        // off-scheduler is safe
        val entityId = adminPlayer.entityId
        packets.registry.set(target, admin, EntityViewState.HIDDEN)
        packets.registry.bindEntityId(target, entityId, admin)
        emitSilently(viewer, WrapperPlayServerDestroyEntities(entityId))
        emitSilently(viewer, WrapperPlayServerPlayerInfoRemove(admin))
    }

    /**
     * full vanilla-faithful restore: tab entry back first (the client resolves
     * the skin from the player info entry), then spawn, fresh metadata, a
     * full six-slot equipment resync and head rotation. the admin state is
     * captured live on the admin's own entity scheduler — the only correct
     * folia thread for touching their location, inventory and metadata.
     */
    fun restore(admin: UUID, target: UUID) {
        if (!packets.online) return
        val viewer = plugin.server.getPlayer(target) ?: return
        packets.threads.forPlayer(admin) { a ->
            val snap = capture(a) ?: return@forPlayer
            snapshots[admin] = snap
            // the row flips before the burst so the shared listener lets
            // every packet of the restore through
            packets.registry.set(target, admin, EntityViewState.VISIBLE)
            packets.registry.bindEntityId(target, snap.entityId, admin)
            emit(viewer, tabAdd(snap))
            // 1.15+ spawn packets carry no metadata, the list stays empty and
            // the real values ride the metadata packet right after
            emit(viewer, WrapperPlayServerSpawnPlayer(snap.entityId, admin, snap.location, emptyList()))
            emit(viewer, WrapperPlayServerEntityMetadata(snap.entityId, snap.metadata))
            emit(viewer, WrapperPlayServerEntityEquipment(snap.entityId, snap.equipment))
            emit(viewer, WrapperPlayServerEntityHeadLook(snap.entityId, snap.yaw))
        }
    }

    /**
     * shutdown-flavoured restore. schedulers are going down, so the live
     * capture cannot rely on a scheduler hop: it runs in place inside a
     * catch, falls back to the last captured snapshot, and degrades to a
     * tab-only restore when even that is missing. what stays unrecovered in
     * the worst case is an entity spawn at a slightly stale position — after
     * a reload the vanilla tracker re-syncs every real position anyway, so
     * the residue is cosmetic and transient.
     */
    private fun restoreBestEffort(admin: UUID, target: UUID) {
        if (!packets.online) return
        val viewer = plugin.server.getPlayer(target) ?: return
        val live = plugin.server.getPlayer(admin)
        val snap = live?.let { runCatching { capture(it) }.getOrNull() } ?: snapshots[admin]
        if (snap == null) {
            // nothing ever captured: at least the tab entry had a name to
            // come back with, without it there is nothing honest to restore
            return
        }
        packets.registry.set(target, admin, EntityViewState.VISIBLE)
        packets.registry.bindEntityId(target, snap.entityId, admin)
        emit(viewer, tabAdd(snap))
        emit(viewer, WrapperPlayServerSpawnPlayer(snap.entityId, admin, snap.location, emptyList()))
        emit(viewer, WrapperPlayServerEntityMetadata(snap.entityId, snap.metadata))
        emit(viewer, WrapperPlayServerEntityEquipment(snap.entityId, snap.equipment))
        emit(viewer, WrapperPlayServerEntityHeadLook(snap.entityId, snap.yaw))
    }

    // ---- trace attribution (read by the scoped listener) ----

    fun poseOf(admin: UUID): TracePos? = poses[admin]

    // ---- internals ----

    private fun mirror(viewer: UUID, target: UUID, state: EntityViewState) {
        packets.registry.set(viewer, target, state)
        syncStates?.set(viewer, target, when (state) {
            EntityViewState.VISIBLE -> kz.bejiihiu.servercore.sync.ViewState.VISIBLE
            EntityViewState.DISGUISED -> kz.bejiihiu.servercore.sync.ViewState.DISGUISED
            EntityViewState.HIDDEN -> kz.bejiihiu.servercore.sync.ViewState.HIDDEN
        })
    }

    private fun trackPose(admin: UUID) {
        if (poseTasks.containsKey(admin)) return
        val player = plugin.server.getPlayer(admin) ?: return
        val task = player.scheduler.runAtFixedRate(
            plugin,
            { _ ->
                val p = plugin.server.getPlayer(admin) ?: return@runAtFixedRate
                // own entity scheduler: the only correct thread for a location read
                val loc = p.location
                poses[admin] = TracePos(loc.x, loc.y, loc.z)
            },
            null,
            POSE_PERIOD_TICKS,
            POSE_PERIOD_TICKS,
        )
        if (task != null) poseTasks[admin] = task
    }

    private fun stopPoseTracking(admin: UUID) {
        poseTasks.remove(admin)?.cancel()
        poses.remove(admin)
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
     * silent send: bypasses every packetevents listener. reserved for the
     * hide direction, where the shared listener's destroy bookkeeping would
     * race with the row this class just wrote.
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
