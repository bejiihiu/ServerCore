package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.ops.GithubReleaseClient
import kz.bejiihiu.servercore.ops.GracefulRestarter
import kz.bejiihiu.servercore.ops.OpsConfig
import kz.bejiihiu.servercore.ops.OpsEventBus
import kz.bejiihiu.servercore.ops.RestartExecutor
import kz.bejiihiu.servercore.ops.RestartReason
import kz.bejiihiu.servercore.ops.RestartScheduleTask
import kz.bejiihiu.servercore.ops.ServerState
import kz.bejiihiu.servercore.ops.UpdateInstaller
import kz.bejiihiu.servercore.ops.UpdaterService
import org.bukkit.Bukkit

/**
 * The ops stack: the event bus every SERVER_STATUS frame rides on, the graceful
 * restarter shared by cron and update triggers, the auto-updater and the daily
 * restart schedule.
 *
 * The flectonepulse bridge is started here on purpose: its telegram
 * suppression flag is read per publish, so the bridge has to be live by the
 * time the first status frame goes out, and the bus has to exist first.
 */
class OpsInstaller(
    private val ctx: PluginContext,
    private val flectonePulse: FlectonePulseInstaller,
) {

    lateinit var bus: OpsEventBus
        private set

    private var updater: UpdaterService? = null
    private var schedule: RestartScheduleTask? = null

    fun install() {
        val plugin = ctx.plugin
        val ops = OpsConfig.load(plugin.config, ctx.logger)

        // the bus is the seam the ws client hooks into, and the updater needs
        // the plugin meta version before anything else
        bus = OpsEventBus(
            ctx.logger,
            onlinePlayers = { Bukkit.getOnlinePlayers().size },
            maxPlayers = { Bukkit.getMaxPlayers() },
            version = { plugin.pluginMeta.version },
            telegramSuppressed = { flectonePulse.bridge.telegramSuppressed() },
        )
        flectonePulse.bridge.start()

        // graceful restart stack (shared by cron + updater triggers)
        val executor = RestartExecutor(plugin, ops.restart.pterodactyl, ctx.logger)
        val restarter = GracefulRestarter(
            plugin,
            bus,
            executor,
            locale = ops.restart.locale,
            graceSeconds = ops.restart.graceSeconds,
            logger = ctx.logger,
        )

        // auto-update: staging dir + backup dir hang off the data folder
        val installer = UpdateInstaller(
            currentJar = plugin.jarFile,
            pluginDataFolder = ctx.dataFolder,
            logger = ctx.logger,
        )
        val updaterService = UpdaterService(
            plugin = plugin,
            config = ops.update,
            client = GithubReleaseClient(ops.update.repo, ops.update.githubTokenEnv, ctx.logger),
            installer = installer,
            opsBus = bus,
            onUpdated = { restarter.begin(RestartReason.UPDATE) },
            logger = ctx.logger,
        )
        updaterService.start()
        updater = updaterService

        // daily cron restarts, zone-rule based (europe/moscow by default)
        val scheduleTask = RestartScheduleTask(plugin, ops.restart.dailyTimes, ops.restart.zone, restarter, ctx.logger)
        scheduleTask.start()
        schedule = scheduleTask
    }

    /** Final boot phase: starting now, running once the server settled a moment. */
    fun announce() {
        bus.publish(ServerState.STARTING)
        ctx.plugin.server.globalRegionScheduler.runDelayed(
            ctx.plugin,
            { _ -> bus.publish(ServerState.RUNNING) },
            100L,
        )
    }

    fun stop() {
        updater?.stop()
        schedule?.stop()
    }
}
