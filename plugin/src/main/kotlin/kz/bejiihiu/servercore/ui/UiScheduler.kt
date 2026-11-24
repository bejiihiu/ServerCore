package kz.bejiihiu.servercore.ui

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * folia threading helpers for the ui layer.
 *
 * rule of thumb used across every component:
 * - anything owned by a player runs on that player's entity scheduler
 * - world placement (hologram spawn) runs on the region owning the location
 * - nothing here ever assumes a main thread — folia has none
 */
object UiScheduler {

    /**
     * runs [task] on the thread that owns the player, right now (next tick at
     * the latest). every surface that touches a player goes through here or
     * through a delayed/repeating sibling — folia has no main thread, and a
     * packet sent from a ws or async callback is a crash waiting to happen.
     * a retired player silently drops the task, which is exactly what a quit
     * race wants.
     */
    fun Player.uiNow(plugin: Plugin, task: () -> Unit) {
        scheduler.run(plugin, { task() }, null)
    }

    /** one delayed task on the thread that owns the player */
    fun Player.uiDelayed(plugin: Plugin, delayTicks: Long, task: (ScheduledTask) -> Unit): ScheduledTask? =
        scheduler.runDelayed(plugin, task, null, delayTicks)

    /** repeating task on the thread that owns the player, period in ticks */
    fun Player.uiRepeat(
        plugin: Plugin,
        initialDelayTicks: Long,
        periodTicks: Long,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask? = scheduler.runAtFixedRate(plugin, task, null, initialDelayTicks, periodTicks)
}
