package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.guest.AccountService
import kz.bejiihiu.servercore.guest.GuestMessageService
import kz.bejiihiu.servercore.guest.GuestProtectionListener
import kz.bejiihiu.servercore.guest.GuestSessionCounter
import kz.bejiihiu.servercore.guest.GuestSessionService
import kz.bejiihiu.servercore.guest.LocalAccountCache
import kz.bejiihiu.servercore.guest.LocalAccountResolver
import kz.bejiihiu.servercore.guest.SnapshotStore
import kz.bejiihiu.servercore.guest.WsAccountResolver
import kz.bejiihiu.servercore.locale.LocalePushListener
import kz.bejiihiu.servercore.ws.BackendGateway
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Guest mode: the durable stores, the account resolution hub, the session
 * machine and its message surface.
 *
 * [install] builds the graph, [start] wires what only makes sense once the
 * whole boot is past (restore recovery and the join/quit listeners), and
 * [stop] flushes the stores. The hub is parked on the context because the
 * transport listener, built earlier, attaches the link to it.
 */
class GuestInstaller(
    private val ctx: PluginContext,
    private val flectonePulse: FlectonePulseInstaller,
) {

    lateinit var sessions: GuestSessionService
        private set

    lateinit var snapshots: SnapshotStore
        private set

    lateinit var counter: GuestSessionCounter
        private set

    lateinit var accountsCache: LocalAccountCache
        private set

    lateinit var messages: GuestMessageService
        private set

    fun install() {
        val plugin = ctx.plugin
        val guestConfig = ctx.guestConfig

        snapshots = SnapshotStore(ctx.dataFolder.toPath().resolve(guestConfig.snapshotStoragePath), ctx.io)
        snapshots.load()
        // with restore-on-restart off the leftover snapshots of a previous run
        // are dropped here, otherwise they would still restore on the next
        // join through the store itself and the flag would do nothing
        if (!guestConfig.restoreOnRestart) snapshots.clearPending()

        counter = GuestSessionCounter(ctx.dataFolder.toPath().resolve("guest-session-counts.json"), ctx.io)
        counter.load()

        accountsCache = LocalAccountCache(ctx.dataFolder.toPath().resolve("account-cache.json"), ctx.io)
        accountsCache.load()

        val hub = BackendGateway()
        ctx.backendGateway = hub
        // locale channel: the backend pushes the canonical bundle on every
        // fresh connection. the listener applies it to the live store and
        // answers LOCALE_ACK through the same hub
        hub.addListener(
            LocalePushListener(ctx.locale, hub, ctx.logger) {
                // ambient identity follows the language: re-render the tab list
                // for everyone online instead of waiting for the next join
                Bukkit.getOnlinePlayers().forEach(ctx.ui::tabList)
            },
        )

        val wsResolver = WsAccountResolver(hub, accountsCache, guestConfig)
        val accounts = AccountService(
            hub,
            wsResolver,
            LocalAccountResolver(guestConfig, accountsCache),
        )
        messages = GuestMessageService(guestConfig, wsResolver, ctx.i18n, plugin)
        sessions = GuestSessionService(
            plugin, guestConfig, accounts, wsResolver, snapshots, counter, hub, messages,
            punishmentQuery = { flectonePulse.bridge.punishmentQuery() },
        )
        ctx.guestSessions = sessions

        // backend confirmed a login -> ungate the player
        wsResolver.onAuthApproved { uuid -> sessions.onAuthApproved(uuid) }
        // personal deep link arrived after the generic prompt went out
        wsResolver.onLinkAvailable { uuid -> sessions.onLinkAvailable(uuid) }
    }

    /** Final boot phase: recovery plus the listeners that answer joins. */
    fun start() {
        val plugin = ctx.plugin

        // restart recovery: durable snapshots become pending restores
        sessions.markPendingRestores(snapshots.pendingAll())

        val lifecycle = object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun onJoin(event: PlayerJoinEvent) = sessions.onJoin(event.player)

            @EventHandler(priority = EventPriority.MONITOR)
            fun onQuit(event: PlayerQuitEvent) = sessions.onQuit(event.player)
        }
        plugin.server.pluginManager.registerEvents(lifecycle, plugin)
        plugin.server.pluginManager.registerEvents(
            GuestProtectionListener(sessions, ctx.guestConfig, messages),
            plugin,
        )
    }

    fun stop() {
        // belt and braces: async snapshot writes may still be in flight.
        // lateinit-guarded: a boot that aborted early lands here with the
        // stores never constructed at all
        if (this::snapshots.isInitialized) snapshots.shutdown()
        if (this::counter.isInitialized) counter.shutdown()
        if (this::accountsCache.isInitialized) accountsCache.shutdown()
    }
}
