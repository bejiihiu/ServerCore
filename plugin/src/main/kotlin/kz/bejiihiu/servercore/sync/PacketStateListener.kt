package kz.bejiihiu.servercore.sync

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * bukkit-side adapter: every spec-mandated state transition source feeds
 * [PacketStateSync] here. the listeners are deliberately thin — read the
 * uuid, translate to a [SyncReason], done. no bukkit entity is kept, nothing
 * blocking runs here, so region-thread events are safe by construction.
 *
 * equipment change and entity change have no bukkit events; the packetevents
 * layer reports them through [PacketStateSync.onTransition] directly.
 *
 * MONITOR priority on purpose: the sync reacts after gameplay plugins made
 * their decisions, it never races them or changes the event outcome.
 */
class PacketStateListener(
    private val sync: PacketStateSync,
    private val identities: EntityIdentityTracker,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        // identity registration first: the reuse guard needs the (uuid, entity
        // id) binding before anything re-applies state against it. a fresh
        // connection binds a new id (generation bump evicts stale owners), the
        // join event runs on the thread that owns the player, and an entity id
        // is a stable int for the life of the connection
        sync.onIdentityRegistered(identities.register(uuid, event.player.entityId))
        sync.onTransition(SyncReason.RECONNECT, uuid)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        // rows survive quit on purpose: the registry is what a reconnect
        // restores the client view from
        sync.onTransition(SyncReason.QUIT, event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        sync.onTransition(SyncReason.WORLD_CHANGE, event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTeleport(event: PlayerTeleportEvent) {
        sync.onTransition(SyncReason.TELEPORT, event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        sync.onTransition(SyncReason.RESPAWN, event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        sync.onTransition(SyncReason.DEATH, event.entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        sync.onTransition(SyncReason.GAMEMODE_CHANGE, event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemove(event: EntityRemoveEvent) {
        val uuid = event.entity.uniqueId
        // only tracked identities matter here; random world entities (items,
        // frames, mobs the packet layer never touched) are ignored for free
        if (identities.current(uuid) != null) {
            sync.onIdentityDespawned(uuid)
        }
    }
}
