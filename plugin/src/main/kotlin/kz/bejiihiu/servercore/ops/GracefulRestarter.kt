package kz.bejiihiu.servercore.ops

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration
import java.util.logging.Logger

/** why a restart was triggered - selects the backend detail string */
enum class RestartReason(val detail: String) {
    SCHEDULED("SCHEDULED"),
    UPDATE("UPDATE"),
    MANUAL("MANUAL"),
}

/**
 * graceful restart: announce (backend + telegram), count down in-game with
 * title + actionbar so nobody loses progress, kick the rest, then hand over
 * to the [RestartExecutor].
 *
 * countdown driver ticks on the global region scheduler; every player-facing
 * action is re-dispatched onto that player's entity scheduler, which is the
 * only thread allowed to touch the player under folia.
 */
class GracefulRestarter(
    private val plugin: JavaPlugin,
    private val opsBus: OpsEventBus,
    private val executor: RestartExecutor,
    private val locale: String,
    private val graceSeconds: Int,
    private val logger: Logger,
) {

    private var inProgress = false

    /** kicks off the full flow; hops onto the global region scheduler first */
    fun begin(reason: RestartReason) {
        plugin.server.globalRegionScheduler.run(plugin) { _ -> beginOnGlobal(reason) }
    }

    private fun beginOnGlobal(reason: RestartReason) {
        if (inProgress) {
            logger.info("[ops] restart already in progress, ignoring $reason trigger")
            return
        }
        inProgress = true
        logger.info("[ops] graceful restart started ($reason), grace ${graceSeconds}s")

        opsBus.publish(ServerState.RESTARTING, reason.detail)

        val startedAt = System.nanoTime()

        // one tick per second on the global scheduler drives the countdown
        plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { task ->
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000L
                val remaining = graceSeconds - elapsedSeconds

                if (remaining > 0) {
                    showCountdown(remaining)
                } else {
                    task.cancel()
                    kickEveryoneAndRestart(reason)
                }
            },
            0L, // fire immediately: players learn about the restart right away
            20L, // 20 ticks = 1s
        )
    }

    private fun showCountdown(secondsRemaining: Long) {
        val title = OpsText.text(locale, OpsText.KEY_RESTART_TITLE)
        val subtitle = OpsText.text(locale, OpsText.KEY_RESTART_COUNTDOWN, mapOf("seconds" to secondsRemaining.toString()))
        val actionbar = OpsText.text(locale, OpsText.KEY_RESTART_ACTIONBAR)

        forPlayer { player ->
            player.showTitle(
                Title.title(
                    Component.text(title),
                    Component.text(subtitle),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1100), Duration.ofMillis(200)),
                ),
            )
            player.sendActionBar(Component.text(actionbar))
        }
    }

    private fun kickEveryoneAndRestart(reason: RestartReason) {
        val kickMessage = Component.text(OpsText.text(locale, OpsText.KEY_RESTART_KICK))
        val players: List<Player> = Bukkit.getOnlinePlayers().toList()

        logger.info("[ops] kicking ${players.size} player(s), then restarting ($reason)")

        for (player in players) {
            player.scheduler.run(plugin, { _ ->
                try {
                    player.kick(kickMessage)
                } catch (error: Exception) {
                    // player already gone - not a reason to skip the restart
                    logger.warning("[ops] kick failed for ${player.name}: ${error.message}")
                }
            }, null)
        }

        // give the kicks a beat to flush over the wire, then pull the trigger
        plugin.server.globalRegionScheduler.runDelayed(
            plugin,
            { _ -> executor.requestRestart(reason.detail) },
            40L, // 2s
        )
    }

    private fun forPlayer(action: (Player) -> Unit) {
        for (player in Bukkit.getOnlinePlayers()) {
            player.scheduler.run(plugin, { _ -> action(player) }, null)
        }
    }
}
