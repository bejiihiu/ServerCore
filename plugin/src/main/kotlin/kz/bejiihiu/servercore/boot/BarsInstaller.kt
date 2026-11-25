package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.bars.BarCommands
import kz.bejiihiu.servercore.bars.BarMetric
import kz.bejiihiu.servercore.bars.BarRenderer
import kz.bejiihiu.servercore.bars.BarSnapshots
import kz.bejiihiu.servercore.bars.BarValueSource
import kz.bejiihiu.servercore.bars.BarsConfig
import kz.bejiihiu.servercore.bars.BarsState
import kz.bejiihiu.servercore.bars.BarsStore
import kz.bejiihiu.servercore.bars.InfoBarService
import kz.bejiihiu.servercore.bars.LocalNetView
import kz.bejiihiu.servercore.bars.MsptMetric
import kz.bejiihiu.servercore.bars.NetMetric
import kz.bejiihiu.servercore.bars.NetSnapshotListener
import kz.bejiihiu.servercore.bars.NetworkStateCache
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
 * renderer and the /rambar, /tpsbar, /msptbar, /playersbar, /worldbar and
 * /netbar commands.
 *
 * Sits with the other player-facing surfaces (after the ui kit and the guest
 * stack): it needs the locale layer for its templates and the player's entity
 * scheduler for every frame. The network bar is the one exception that reads
 * the websocket hub, and only as a listener on the already-installed gateway
 * — a broken bar therefore still never delays the backend connection.
 */
class BarsInstaller(private val ctx: PluginContext) {

    private var service: InfoBarService? = null

    /** last readings, for placeholderapi; null when the whole stack is off */
    private var snapshots: BarSnapshots? = null

    /** config flag, kept so the self-check can tell "off" from "failed" */
    var enabledByConfig: Boolean = false
        private set

    /** how many bar types the config actually turned on */
    var enabledBarCount: Int = 0
        private set

    /** true while the renderer and its refresh tasks are up */
    val active: Boolean get() = service != null

    fun install() {
        val config = BarsConfig.load(ctx.plugin.config, ctx.logger)
        enabledByConfig = config.enabled
        enabledBarCount = config.enabledBars().size
        if (!config.enabled) return

        // the network view: fed by the backend's aggregate, rendered from a
        // cached snapshot. listening needs the hub the guest installer built,
        // not a live link — a frame that arrives later still lands here
        val netCache = NetworkStateCache()
        ctx.backendGateway.addListener(NetSnapshotListener(netCache))

        val metrics = buildList<BarMetric> {
            if (config.ram.enabled) add(RamMetric(config.ram.thresholds))
            if (config.tps.enabled) add(TpsMetric(config.tps.thresholds))
            if (config.mspt.enabled) add(MsptMetric(config.mspt.thresholds, config.updateIntervalTicks))
            if (config.players.enabled) add(PlayersMetric())
            if (config.world.enabled) add(WorldMetric(ctx.locale, config.world.showWeather))
            if (config.net.enabled) {
                add(
                    NetMetric(
                        source = netCache,
                        staleAfterMs = config.net.staleAfterMs,
                        local = { LocalNetView(ctx.plugin.server.onlinePlayers.size) },
                    ),
                )
            }
        }
        if (metrics.isEmpty()) {
            ctx.logger.info("bars: enabled but every bar is switched off")
            return
        }

        val store = BarsStore(ctx.dataFolder.toPath().resolve("bars.json"), ctx.io)
        val readingSnapshots = BarSnapshots()
        val barService = InfoBarService(
            plugin = ctx.plugin,
            config = config,
            selections = BarsState(store.load()),
            store = store,
            metrics = metrics,
            renderer = BarRenderer(ctx.locale),
            snapshots = readingSnapshots,
        )
        barService.start()
        service = barService
        snapshots = readingSnapshots

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

    /** the placeholder source, when the bar stack is actually running */
    fun placeholderValues(): BarValueSource? = snapshots

    fun stop() {
        service?.stop()
        service = null
        snapshots = null
    }
}
