package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.bars.BarCommands
import kz.bejiihiu.servercore.bars.BarMetric
import kz.bejiihiu.servercore.bars.BarRenderer
import kz.bejiihiu.servercore.bars.BarsConfig
import kz.bejiihiu.servercore.bars.BarsState
import kz.bejiihiu.servercore.bars.BarsStore
import kz.bejiihiu.servercore.bars.InfoBarService
import kz.bejiihiu.servercore.bars.MsptMetric
import kz.bejiihiu.servercore.bars.PlayersMetric
import kz.bejiihiu.servercore.bars.RamMetric
import kz.bejiihiu.servercore.bars.TpsMetric
import kz.bejiihiu.servercore.bars.WorldMetric
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Server info bars: the per-player toggle store, the metric providers, the
 * renderer and the /rambar, /tpsbar, /msptbar, /playersbar and /worldbar
 * commands.
 *
 * Sits with the other player-facing surfaces (after the ui kit and the guest
 * stack, before the transport goes live): it needs the locale layer for its
 * templates and the player's entity scheduler for every frame, and nothing
 * here depends on the websocket link — the network bar is a later phase. A
 * broken bar therefore never delays the backend connection.
 */
class BarsInstaller(private val ctx: PluginContext) {

    private var service: InfoBarService? = null

    fun install() {
        val config = BarsConfig.load(ctx.plugin.config, ctx.logger)
        if (!config.enabled) return

        val metrics = buildList<BarMetric> {
            if (config.ram.enabled) add(RamMetric(config.ram.thresholds))
            if (config.tps.enabled) add(TpsMetric(config.tps.thresholds))
            if (config.mspt.enabled) add(MsptMetric(config.mspt.thresholds, config.updateIntervalTicks))
            if (config.players.enabled) add(PlayersMetric())
            if (config.world.enabled) add(WorldMetric(ctx.locale, config.world.showWeather))
        }
        if (metrics.isEmpty()) {
            ctx.logger.info("bars: enabled but every bar is switched off")
            return
        }

        val store = BarsStore(ctx.dataFolder.toPath().resolve("bars.json"), ctx.io)
        val barService = InfoBarService(
            plugin = ctx.plugin,
            config = config,
            selections = BarsState(store.load()),
            store = store,
            metrics = metrics,
            renderer = BarRenderer(ctx.locale),
        )
        barService.start()
        service = barService

        ctx.plugin.server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler(priority = EventPriority.MONITOR)
                fun onJoin(event: PlayerJoinEvent) = barService.onJoin(event.player)

                @EventHandler(priority = EventPriority.MONITOR)
                fun onQuit(event: PlayerQuitEvent) = barService.onQuit(event.player)
            },
            ctx.plugin,
        )

        BarCommands(ctx.plugin, config, barService, ctx.locale).attach()

        ctx.logger.info(
            "bars: ${config.enabledBars().joinToString { it.key }} on ${config.surface.key} " +
                "every ${config.updateIntervalTicks} tick(s)",
        )
    }

    fun stop() {
        service?.stop()
    }
}
