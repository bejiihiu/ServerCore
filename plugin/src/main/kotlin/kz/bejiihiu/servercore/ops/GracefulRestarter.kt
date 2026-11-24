package kz.bejiihiu.servercore.ops

import kz.bejiihiu.servercore.ui.CountdownRenderer
import kz.bejiihiu.servercore.ui.Theme
import kz.bejiihiu.servercore.ui.UiFx
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
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

    /**
     * one bar per player, created on their own thread. a bossbar mutation
     * pushes a packet to every viewer, so a shared instance mutated from the
     * global countdown thread would send from a thread that owns nobody.
     */
    private val bars = java.util.concurrent.ConcurrentHashMap<UUID, BossBar>()

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
        val title = Theme.render(OpsText.text(locale, OpsText.KEY_RESTART_TITLE))
        val subtitle = Theme.render(
            OpsText.text(locale, OpsText.KEY_RESTART_COUNTDOWN, mapOf("seconds" to secondsRemaining.toString())),
        )
        val barName = Theme.render(OpsText.text(locale, OpsText.KEY_RESTART_BAR))
        val progress = (secondsRemaining.toFloat() / graceSeconds.coerceAtLeast(1)).coerceIn(0f, 1f)
        val barColor = CountdownRenderer.barColor(secondsRemaining.toInt(), graceSeconds)

        // title announces the tick, the bar carries the time; no third surface,
        // the design rules allow a pair and this is the pair for a deadline
        forPlayer { player ->
            val bar = bars.computeIfAbsent(player.uniqueId) {
                BossBar.bossBar(barName, progress, barColor, BossBar.Overlay.PROGRESS)
            }
            bar.name(barName)
            bar.progress(progress)
            bar.color(barColor)
            player.showBossBar(bar)
            UiFx.title(player, title, subtitle, stayMs = COUNTDOWN_STAY_MS)
        }
    }

    private fun kickEveryoneAndRestart(reason: RestartReason) {
        val kickMessage = Theme.render(OpsText.text(locale, OpsText.KEY_RESTART_KICK))
        val players: List<Player> = Bukkit.getOnlinePlayers().toList()

        logger.info("[ops] kicking ${players.size} player(s), then restarting ($reason)")

        for (player in players) {
            player.scheduler.run(plugin, { _ ->
                try {
                    // a bar left behind would survive the kick on the client
                    bars.remove(player.uniqueId)?.let { player.hideBossBar(it) }
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

    private companion object {
        /** title stay for a one-second tick: the fade tail bleeds into the next frame */
        const val COUNTDOWN_STAY_MS = 950L
    }
}
