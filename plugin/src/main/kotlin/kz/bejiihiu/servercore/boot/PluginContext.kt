package kz.bejiihiu.servercore.boot

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger
import kz.bejiihiu.servercore.ServerCorePlugin
import kz.bejiihiu.servercore.config.PluginConfig
import kz.bejiihiu.servercore.guest.GuestConfig
import kz.bejiihiu.servercore.guest.GuestSessionService
import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.ui.UiKit
import kz.bejiihiu.servercore.util.I18n
import kz.bejiihiu.servercore.ws.BackendGateway

/**
 * The leaf services every installer is handed plus the few values that cross
 * subsystem boundaries. Subsystem objects themselves are not parked here: an
 * installer owns what it builds and exposes it narrowly, so the boot order
 * stays visible in one place.
 *
 * Installer contract: every `boot/<Feature>Installer` has one owner (the plugin field
 * assigned in `onEnable`) and three optional phases. `install()` runs once on
 * the enable path (the global region thread) and may be abandoned halfway —
 * a boot blocker disables the plugin and `stop()` then runs against whatever
 * was actually built, so nullable subsystem fields are the norm. `start()` is
 * for work that must wait until the rest of the graph exists (listeners,
 * transport connect). `stop()` runs once from `onDisable`, must tolerate a
 * failed or partial install, must not throw, and must finish before [io] is
 * shut down. Nothing here may block the enable path: long work goes to [io]
 * or a Folia scheduler.
 */
class PluginContext(
    val plugin: ServerCorePlugin,
    val io: ExecutorService,
    val locale: LocaleService,
    val ui: UiKit,
) {

    val logger: Logger get() = plugin.logger

    val dataFolder: File get() = plugin.dataFolder

    /** loaded by the config installer, right after the ops stack came up */
    lateinit var pluginConfig: PluginConfig

    /** loaded by the i18n installer: its language setting picks the bundle */
    lateinit var guestConfig: GuestConfig

    /** message layer every player-facing surface renders through */
    lateinit var i18n: I18n

    /** guest hub the transport attaches and detaches the link on */
    lateinit var backendGateway: BackendGateway

    /** guest session machine the transport re-syncs on every reconnect */
    lateinit var guestSessions: GuestSessionService

    /** live spec session count for the status frames, wired once the registry exists */
    var activeSpecSessions: () -> Int = { 0 }

    companion object {
        /**
         * Builds the core plumbing. Order matters: the io pool comes up before
         * anything can hand work to it, and the kit renders through the locale
         * service.
         */
        fun create(plugin: ServerCorePlugin): PluginContext {
            val io = Executors.newVirtualThreadPerTaskExecutor()
            val locale = LocaleService(plugin.dataFolder.toPath(), plugin.logger)
            return PluginContext(plugin, io, locale, UiKit(plugin, locale))
        }
    }
}
