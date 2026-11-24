package kz.bejiihiu.servercore.spectator

import java.util.UUID
import kz.bejiihiu.servercore.sync.PacketStateSync
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * folia event seam: either side of an active observation pair hit a state
 * transition. policy mirrors the sync module's matrix, applied to the
 * observation pairs only:
 * - death / respawn / world change / cross-world teleport of either side →
 *   full hide re-assert per affected pair (the hide sequence is idempotent)
 * - join of either side → re-assert too, a fresh client rebuilt its tab list
 *   and its world picture from scratch
 * - quit of either side → silent registry cleanup, nothing restores into a
 *   leaving client
 *
 * transitions are deliberately NOT forwarded to [PacketStateSync] here: its
 * own listeners already deliver every one of these events, a second forward
 * would just double the work. the observation pairs meet the sync module
 * through its registry seam instead — the hider writes its rows into the
 * same StateInvalidator the sync coordinator reads, so a merged build sees
 * one picture of the world.
 *
 * MONITOR everywhere: this module reacts, it never vetoes a transition.
 */
class SpecResyncListener(
    private val hider: ObserverHider,
    private val sessions: SpecSessionRegistry,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = hider.resync(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        // the leaving admin's client needs nothing, the target's client keeps
        // no admin entity by construction: registry cleanup only
        sessions.detach(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) = resync(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = resync(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = resync(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTeleport(event: PlayerTeleportEvent) {
        // same-world teleports leave both clients' pictures intact, the
        // cross-world ones rebuild the target's world server-side
        if (event.from.world != event.to?.world) resync(event.player.uniqueId)
    }

    private fun resync(uuid: UUID) = hider.resync(uuid)
}
