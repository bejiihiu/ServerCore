package kz.bejiihiu.servercore.protection

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Logger
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * two layers of gamemode protection:
 *
 * 1. every PlayerGameModeChangeEvent is checked at LOWEST — changes outside a
 *    sanctioned flow are cancelled, so the switch never happens.
 * 2. a periodic sweep catches drift that skipped the event (nms-level writes,
 *    a plugin firing its own event, race windows). it fans out to each
 *    player's entity scheduler and reverts non-survival modes to survival.
 *
 * sanctioning = an entry in the override registry plus an optional
 * plugin-internal predicate wired at boot (guest sessions hold their players
 * in spectator, so guest mode is sanctioned through that seam without the
 * guard knowing guest internals).
 */
class GamemodeGuardListener(
    private val plugin: JavaPlugin,
    private val config: ProtectionConfig,
    private val registry: AllowedGamemodeOverrideRegistry,
    private val logger: Logger,
    private val extraSanction: (UUID, GameMode) -> Boolean = { _, _ -> false },
) : Listener {

    private var sweepTask: ScheduledTask? = null

    fun start() {
        val period = config.gamemodeSweepIntervalSeconds * 20
        if (period <= 0) return
        sweepTask = plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            Consumer { _ -> sweep() },
            period,
            period,
        )
    }

    fun stop() {
        sweepTask?.cancel()
        sweepTask = null
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (!config.enforceGamemode) return
        val uuid = event.player.uniqueId
        val sanctioned = registry.isSanctioned(uuid, event.newGameMode) || extraSanction(uuid, event.newGameMode)
        if (GamemodeGuardPolicy.shouldAllowChange(event.newGameMode, event.cause, sanctioned)) return
        event.isCancelled = true
        logger.warning(
            "denied gamemode change to ${event.newGameMode} for ${event.player.name} (cause ${event.cause})",
        )
    }

    /** called from the global scheduler; entity work is pushed back to owners */
    fun sweep() {
        if (!config.enforceGamemode) return
        registry.sweep()
        for (player in plugin.server.onlinePlayers) {
            val mode = player.gameMode
            if (mode == GameMode.SURVIVAL) continue
            if (player.isDead) continue // a dead spectator is vanilla death flow
            if (isSanctioned(player.uniqueId, mode)) continue
            player.scheduler.run(
                plugin,
                Consumer { task ->
                    revertIfDrifting(player)
                    task.cancel()
                },
                null,
            )
        }
    }

    private fun revertIfDrifting(player: Player) {
        if (!player.isOnline || player.isDead) return
        val mode = player.gameMode
        if (!GamemodeGuardPolicy.shouldRevert(mode, isSanctioned(player.uniqueId, mode))) return
        player.gameMode = GameMode.SURVIVAL
        logger.warning("reverted ${player.name} from $mode to survival (no sanctioned override)")
    }

    private fun isSanctioned(uuid: UUID, mode: GameMode): Boolean =
        registry.isSanctioned(uuid, mode) || extraSanction(uuid, mode)
}
