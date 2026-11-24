package kz.bejiihiu.servercore.ui

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * a live countdown: bossbar carrying progress, title ticks on the last five
 * seconds, a locale label with the {time} placeholder. runs entirely on the
 * player's entity scheduler; cancelling is safe from any thread that holds
 * the handle (task.cancel is thread-safe), the bar itself is shown/hidden
 * from the entity thread.
 */
class Countdown(
    private val kit: UiKit,
    private val player: Player,
    private val totalSeconds: Int,
    private val labelKey: String,
    private val extraPlaceholders: Map<String, String> = emptyMap(),
    private val tickSubtitleKey: String? = null,
    private val onDone: () -> Unit = {},
) {

    private val bar: BossBar = BossBar.bossBar(
        Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS,
    )
    private var task: ScheduledTask? = null
    private var secondsLeft = totalSeconds
    private var started = false

    fun start() {
        if (started) return
        started = true
        player.showBossBar(bar)
        // 0 delay: first frame right away, then one tick per second
        task = player.scheduler.runAtFixedRate(kit.plugin, { t -> tick(t) }, null, 0, 20)
    }

    private fun tick(t: ScheduledTask) {
        if (secondsLeft <= 0) {
            t.cancel()
            player.hideBossBar(bar)
            onDone()
            return
        }
        bar.progress(CountdownRenderer.progress(secondsLeft, totalSeconds))
        bar.color(CountdownRenderer.barColor(secondsLeft, totalSeconds))
        bar.name(
            kit.locale.component(
                labelKey,
                extraPlaceholders + ("time" to CountdownRenderer.mmss(secondsLeft)),
            ),
        )
        if (secondsLeft <= 5 && tickSubtitleKey != null) {
            UiFx.title(
                player,
                Component.text(secondsLeft.toString(), CountdownRenderer.tickColor(secondsLeft)),
                kit.locale.component(tickSubtitleKey),
                stayMs = 900,
            )
        }
        secondsLeft--
    }

    /** stops the countdown and clears the bar; idempotent */
    fun cancel() {
        task?.cancel()
        task = null
        player.hideBossBar(bar)
    }
}
