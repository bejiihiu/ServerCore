package kz.bejiihiu.servercore

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
import org.bukkit.plugin.java.JavaPlugin

// single entry point, folia-only. no main thread work here, ever
class ServerCorePlugin : JavaPlugin() {

    private var updater: UpdaterService? = null
    private var restartSchedule: RestartScheduleTask? = null

    override fun onEnable() {
        logger.info("servercore ${pluginMeta.version} enabled")
        saveDefaultConfig()

        val ops = OpsConfig.load(config, logger)

        // the ops event bus is the seam the ws client will hook into: it
        // registers an OpsEventSink that signs + sends SERVER_STATUS frames.
        // until then events are logged, never lost silently.
        val opsBus = OpsEventBus(
            logger,
            onlinePlayers = { Bukkit.getOnlinePlayers().size },
            maxPlayers = { Bukkit.getMaxPlayers() },
            version = { pluginMeta.version },
        )

        // graceful restart stack (shared by cron + updater triggers)
        val executor = RestartExecutor(this, ops.restart.pterodactyl, logger)
        val restarter = GracefulRestarter(
            this,
            opsBus,
            executor,
            locale = ops.restart.locale,
            graceSeconds = ops.restart.graceSeconds,
            logger = logger,
        )

        // auto-update: staging dir + backup dir hang off the data folder
        val installer = UpdateInstaller(
            currentJar = file,
            pluginDataFolder = dataFolder,
            logger = logger,
        )
        val updaterService = UpdaterService(
            plugin = this,
            config = ops.update,
            client = GithubReleaseClient(ops.update.repo, ops.update.githubTokenEnv, logger),
            installer = installer,
            opsBus = opsBus,
            onUpdated = { restarter.begin(RestartReason.UPDATE) },
            logger = logger,
        )
        updaterService.start()
        updater = updaterService

        // daily cron restarts, zone-rule based (europe/moscow by default)
        val schedule = RestartScheduleTask(this, ops.restart.dailyTimes, ops.restart.zone, restarter, logger)
        schedule.start()
        restartSchedule = schedule

        // boot sequence over the bus: starting now, running once the server
        // had a moment to settle
        opsBus.publish(ServerState.STARTING)
        server.globalRegionScheduler.runDelayed(this, { _ -> opsBus.publish(ServerState.RUNNING) }, 100L)
    }

    override fun onDisable() {
        updater?.stop()
        restartSchedule?.stop()
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
