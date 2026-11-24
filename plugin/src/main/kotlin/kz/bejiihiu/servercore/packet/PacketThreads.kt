package kz.bejiihiu.servercore.packet

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * folia threading helpers for the packet layer.
 *
 * packetevents sends and listener callbacks are netty-side and safe from any
 * thread. what is NOT safe is touching players/worlds/entities: on folia that
 * belongs to region and entity schedulers. anything that needs a player object
 * goes through [forPlayer], everything background goes through [async].
 */
class PacketThreads(private val plugin: JavaPlugin) {

    /** fire-and-forget background work, folia's async pool */
    fun async(task: Runnable) {
        plugin.server.asyncScheduler.runNow(plugin) { task.run() }
    }

    /**
     * run [task] on the player's own entity scheduler — the only correct place
     * to touch a player on folia. the uuid lookup itself is a read-only index
     * query, the actual player access happens inside the scheduler task.
     * silently no-ops for offline players.
     */
    fun forPlayer(uuid: UUID, task: (Player) -> Unit) {
        val player = plugin.server.getPlayer(uuid) ?: return
        player.scheduler.run(plugin, { t -> task(player); t.cancel() }, null)
    }

    /**
     * delay then run async, in ticks — the periodic visibility check loop of
     * later modules hangs off this.
     */
    fun asyncLater(delayTicks: Long, task: Runnable) {
        plugin.server.asyncScheduler.runDelayed(
            plugin,
            { task.run() },
            delayTicks * 50L,
            TimeUnit.MILLISECONDS,
        )
    }
}
