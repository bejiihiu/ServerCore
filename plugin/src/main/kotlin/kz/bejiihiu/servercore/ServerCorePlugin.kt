package kz.bejiihiu.servercore

<<<<<<< HEAD
import kz.bejiihiu.servercore.config.ConfigLoader
import kz.bejiihiu.servercore.player.PlayerPresenceService
import kz.bejiihiu.servercore.websocket.DelayScheduler
import kz.bejiihiu.servercore.websocket.GatewayClient
import kz.bejiihiu.servercore.websocket.GatewayListener
import kz.bejiihiu.servercore.websocket.Payload
import kz.bejiihiu.servercore.websocket.ServerStatsProvider
import net.kyori.adventure.text.Component
=======
import java.util.concurrent.Executors
import kz.bejiihiu.servercore.boot.JoinFlowListener
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
import kz.bejiihiu.servercore.locale.LocaleService
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
import kz.bejiihiu.servercore.ui.UiKit
import kz.bejiihiu.servercore.util.I18n
import kz.bejiihiu.servercore.ws.BackendGateway
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
>>>>>>> origin/main
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * single entry point, folia-only. onEnable builds the config, the gateway
 * client and the presence service; all scheduling goes through folia's
 * async/entity schedulers, never the bukkit one.
 */
class ServerCorePlugin : JavaPlugin() {

<<<<<<< HEAD
    private var gateway: GatewayClient? = null
    private var presence: PlayerPresenceService? = null

    override fun onEnable() {
        saveDefaultConfig()
        val config = try {
            ConfigLoader.load(config)
        } catch (e: IllegalArgumentException) {
            // bad config is a boot blocker: better dead than half-connected
            logger.severe("config error: ${e.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        val stats = object : ServerStatsProvider {
            override fun onlinePlayers(): Int = presence?.onlineCount() ?: 0
            override fun maxPlayers(): Int = server.maxPlayers
            override fun serverVersion(): String = server.getMinecraftVersion()
        }

        val listener = object : GatewayListener {
            override fun onConnected() {
                logger.info("backend gateway connected as ${config.gateway.serverId}")
            }

            override fun onDisconnected() {
                logger.warning("backend gateway disconnected, reconnect scheduled")
            }

            override fun onLinkToken(payload: Payload.LinkToken) {
                // the in-game deep-link delivery lands with the guest phase;
                // the token is valid and single-use either way
                logger.info("link token issued for ${payload.username}")
            }

            override fun onAccountDeleted(payload: Payload.AccountDeleted) {
                revokeDeletedPlayer(payload.minecraftUuid)
            }
        }

        // folia async scheduler drives reconnect delays; the client itself is
        // bukkit-free, so its callbacks may run on any async thread
        val delayScheduler = DelayScheduler { task: Runnable, delayMs: Long ->
            server.asyncScheduler.runDelayed(this, { _ -> task.run() }, delayMs, TimeUnit.MILLISECONDS)
        }

        val client = GatewayClient(
            config = config.gateway,
            listener = listener,
            scheduler = delayScheduler,
            stats = stats,
            presenceLookup = { uuid -> presence?.isOnline(uuid) ?: false },
        )
        gateway = client

        val presenceService = PlayerPresenceService(this, client)
        presence = presenceService
        server.pluginManager.registerEvents(presenceService, this)

        // heartbeat keeps the backend's liveness picture fresh
        presenceService.startHeartbeat()
        client.start()

        logger.info("servercore ${pluginMeta.version} enabled")
=======
    lateinit var ui: UiKit
        private set

    lateinit var locale: LocaleService
        private set

    private lateinit var gateway: BackendGateway
    private lateinit var sessions: GuestSessionService
    private lateinit var snapshots: SnapshotStore
    private lateinit var accountsCache: LocalAccountCache
    private lateinit var counter: GuestSessionCounter
    private lateinit var io: java.util.concurrent.ExecutorService
    private var updater: UpdaterService? = null
    private var restartSchedule: RestartScheduleTask? = null
    private lateinit var opsBus: OpsEventBus

    override fun onEnable() {
        saveDefaultConfig()

        io = Executors.newVirtualThreadPerTaskExecutor()

        locale = LocaleService(dataFolder.toPath(), logger)
        ui = UiKit(this, locale)

        // ops first: the bus is the seam the ws client hooks into (SERVER_STATUS
        // frames), and the updater needs the plugin meta version before anything else
        val ops = OpsConfig.load(config, logger)
        opsBus = OpsEventBus(
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

        // guest mode stack
        val guestConfig = GuestConfig.load(config, logger)
        val i18n = I18n(this, guestConfig.lang)

        snapshots = SnapshotStore(dataFolder.toPath().resolve(guestConfig.snapshotStoragePath), io)
        snapshots.load()

        counter = GuestSessionCounter(dataFolder.toPath().resolve("guest-session-counts.json"), io)
        counter.load()

        accountsCache = LocalAccountCache(dataFolder.toPath().resolve("account-cache.json"), io)
        accountsCache.load()

        gateway = BackendGateway()

        val wsResolver = WsAccountResolver(gateway, accountsCache, guestConfig)
        val accounts = AccountService(
            gateway,
            wsResolver,
            LocalAccountResolver(guestConfig, accountsCache),
        )
        val messages = GuestMessageService(guestConfig, wsResolver, i18n)
        sessions = GuestSessionService(
            this, guestConfig, accounts, wsResolver, snapshots, counter, gateway, messages,
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
            GuestProtectionListener(sessions, guestConfig, messages),
            this,
        )
        // demo surface for the ui toolkit until real flows consume it everywhere
        server.pluginManager.registerEvents(JoinFlowListener(ui), this)

        // boot sequence over the bus: starting now, running once the server
        // had a moment to settle
        opsBus.publish(ServerState.STARTING)
        server.globalRegionScheduler.runDelayed(this, { _ -> opsBus.publish(ServerState.RUNNING) }, 100L)

        logger.info("servercore ${pluginMeta.version} enabled (guest mode: ${if (guestConfig.enabled) "on" else "off"})")
>>>>>>> origin/main
    }

    /** backend revoked the account: end the local session, folia-correctly */
    private fun revokeDeletedPlayer(minecraftUuid: String) {
        val uuid = runCatching { UUID.fromString(minecraftUuid) }.getOrNull() ?: return
        val player = server.getPlayer(uuid) ?: return
        // entity work belongs on the entity scheduler, never on ws callbacks
        player.scheduler.run(
            this,
            { task ->
                player.kick(Component.text("Ваш аккаунт был удалён. Доступ к серверу отозван."))
                task.cancel()
            },
            null,
        )
        logger.info("account deleted, player kicked: $minecraftUuid")
    }

    override fun onDisable() {
<<<<<<< HEAD
        gateway?.stop()
=======
        updater?.stop()
        restartSchedule?.stop()
        // belt and braces: async snapshot writes may still be in flight
        snapshots.shutdown()
        counter.shutdown()
        accountsCache.shutdown()
        io.shutdown()
>>>>>>> origin/main
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
