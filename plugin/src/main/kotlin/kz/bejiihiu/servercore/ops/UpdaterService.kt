package kz.bejiihiu.servercore.ops

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * periodic self-update: query github releases, decide, download + verify +
 * swap, announce UPDATE_READY and (optionally) request a restart.
 *
 * threading: the fixed-rate timer runs on the folia async scheduler, and each
 * check hands off to a fresh virtual thread because every step blocks on
 * network/file io. nothing here ever touches a region thread.
 */
class UpdaterService(
    private val plugin: JavaPlugin,
    private val config: UpdateConfig,
    private val client: GithubReleaseClient,
    private val installer: UpdateInstaller,
    private val opsBus: OpsEventBus,
    /** invoked after a successful swap when auto-restart is enabled */
    private val onUpdated: () -> Unit,
    private val logger: Logger,
) {

    private var task: ScheduledTask? = null

    fun start() {
        if (!config.enabled) {
            logger.info("[ops] auto-update disabled by config")
            return
        }
        val intervalSeconds = config.checkIntervalMinutes * 60
        task = plugin.server.asyncScheduler.runAtFixedRate(
            plugin,
            { _ -> Thread.ofVirtual().name("servercore-update-check").start { checkNow() } },
            INITIAL_DELAY_SECONDS,
            intervalSeconds,
            TimeUnit.SECONDS,
        )
        logger.info("[ops] auto-update active: repo=${config.repo}, interval=${config.checkIntervalMinutes}min")
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    /** one full check cycle; safe to call repeatedly, all failures are logged */
    fun checkNow() {
        try {
            val currentRaw = plugin.pluginMeta.version
            val lookup = client.latestBuildRelease()

            when (val decision = UpdatePolicy.decide(currentRaw, when (lookup) {
                is ReleaseLookup.Found -> lookup.candidate
                else -> null
            })) {
                is UpdateDecision.NoRelease -> {
                    if (lookup is ReleaseLookup.Error) {
                        logger.warning("[ops] release check failed: ${(lookup as ReleaseLookup.Error).reason}")
                    }
                }

                is UpdateDecision.UpToDate -> logger.fine("[ops] up to date (${decision.javaClass.simpleName})")

                is UpdateDecision.Available -> applyUpdate(decision)
            }
        } catch (error: Exception) {
            logger.severe("[ops] update check crashed: ${error.message}")
        }
    }

    private fun applyUpdate(decision: UpdateDecision.Available) {
        val tag = decision.release.tag
        logger.info("[ops] update available: $tag (running: ${decision.currentRaw ?: "unknown"})")

        val result = installer.install(decision.release, client)
        if (result !is InstallResult.Installed) {
            val failure = result as InstallResult.Failed
            logger.severe("[ops] update aborted at ${failure.stage}: ${failure.error}")
            // swapped nothing - tell ops the server is still fine
            publishOnGlobal(ServerState.RUNNING, "update failed at ${failure.stage}: ${failure.error}")
            return
        }

        // swap done: announce before the restart so backend + telegram users know
        publishOnGlobal(ServerState.UPDATE_READY, tag)

        if (config.autoRestart) {
            onUpdated()
        } else {
            logger.info("[ops] update installed, auto-restart disabled - restart manually to load $tag")
        }
    }

    /** publishes on the global region thread: reading player counts is bukkit api */
    private fun publishOnGlobal(state: ServerState, detail: String?) {
        plugin.server.globalRegionScheduler.run(plugin) { _ -> opsBus.publish(state, detail) }
    }

    private companion object {
        /** first check 30s after boot: let the server settle in */
        const val INITIAL_DELAY_SECONDS = 30L
    }
}
