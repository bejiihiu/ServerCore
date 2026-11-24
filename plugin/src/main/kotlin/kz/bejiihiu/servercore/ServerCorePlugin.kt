package kz.bejiihiu.servercore

import java.util.concurrent.Executors
import kz.bejiihiu.servercore.guest.AccountService
import kz.bejiihiu.servercore.guest.GuestConfig
import kz.bejiihiu.servercore.guest.GuestMessageService
import kz.bejiihiu.servercore.guest.GuestProtectionListener
import kz.bejiihiu.servercore.guest.GuestSessionCounter
import kz.bejiihiu.servercore.guest.GuestSessionService
import kz.bejiihiu.servercore.guest.LocalAccountCache
import kz.bejiihiu.servercore.guest.LocalAccountResolver
import kz.bejiihiu.servercore.guest.SnapshotStore
import kz.bejiihiu.servercore.guest.WsAccountResolver
import kz.bejiihiu.servercore.util.I18n
import kz.bejiihiu.servercore.ws.BackendGateway
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

// single entry point, folia-only. no main thread work here, ever
class ServerCorePlugin : JavaPlugin() {

    private lateinit var gateway: BackendGateway
    private lateinit var sessions: GuestSessionService
    private lateinit var snapshots: SnapshotStore
    private lateinit var accountsCache: LocalAccountCache
    private lateinit var counter: GuestSessionCounter
    private lateinit var io: java.util.concurrent.ExecutorService

    override fun onEnable() {
        saveDefaultConfig()

        io = Executors.newVirtualThreadPerTaskExecutor()
        val config = GuestConfig.load(config, logger)
        val i18n = I18n(this, config.lang)

        snapshots = SnapshotStore(dataFolder.toPath().resolve(config.snapshotStoragePath), io)
        snapshots.load()

        counter = GuestSessionCounter(dataFolder.toPath().resolve("guest-session-counts.json"), io)
        counter.load()

        accountsCache = LocalAccountCache(dataFolder.toPath().resolve("account-cache.json"), io)
        accountsCache.load()

        gateway = BackendGateway()

        val wsResolver = WsAccountResolver(gateway, accountsCache, config)
        val accounts = AccountService(
            gateway,
            wsResolver,
            LocalAccountResolver(config, accountsCache),
        )
        val messages = GuestMessageService(config, wsResolver, i18n)
        sessions = GuestSessionService(
            this, config, accounts, wsResolver, snapshots, counter, gateway, messages,
        )

        // backend confirmed a login -> ungate the player
        wsResolver.onAuthApproved { uuid -> sessions.onAuthApproved(uuid) }
        // personal deep link arrived after the generic prompt went out
        wsResolver.onLinkAvailable { uuid -> sessions.onLinkAvailable(uuid) }
        // reconnect: re-announce live guest sessions so both sides agree on reality
        gateway.addListener(object : kz.bejiihiu.servercore.ws.GatewayListener {
            override fun onMessage(envelope: kz.bejiihiu.servercore.ws.WsEnvelope) = Unit
            override fun onConnected() = sessions.reconcile()
        })

        // restart recovery: durable snapshots become pending restores
        sessions.markPendingRestores(snapshots.pendingAll())

        val lifecycle = object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun onJoin(event: PlayerJoinEvent) = sessions.onJoin(event.player)

            @EventHandler(priority = EventPriority.MONITOR)
            fun onQuit(event: PlayerQuitEvent) = sessions.onQuit(event.player)
        }
        server.pluginManager.registerEvents(lifecycle, this)
        server.pluginManager.registerEvents(
            GuestProtectionListener(sessions, config, messages),
            this,
        )

        logger.info("servercore ${pluginMeta.version} enabled (guest mode: ${if (config.enabled) "on" else "off"})")
    }

    override fun onDisable() {
        // belt and braces: async snapshot writes may still be in flight
        snapshots.shutdown()
        counter.shutdown()
        accountsCache.shutdown()
        io.shutdown()
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
