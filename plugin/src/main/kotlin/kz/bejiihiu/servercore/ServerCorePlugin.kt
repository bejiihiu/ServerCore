package kz.bejiihiu.servercore

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kz.bejiihiu.servercore.boot.JoinFlowListener
import kz.bejiihiu.servercore.config.ConfigLoader
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
import kz.bejiihiu.servercore.player.PlayerPresenceService
import kz.bejiihiu.servercore.protection.AllowedGamemodeOverrideRegistry
import kz.bejiihiu.servercore.protection.CommandBlockAuditor
import kz.bejiihiu.servercore.protection.CommandProtectionListener
import kz.bejiihiu.servercore.protection.CommandProtectionService
import kz.bejiihiu.servercore.protection.CommandTreeSources
import kz.bejiihiu.servercore.protection.GamemodeGuardListener
import kz.bejiihiu.servercore.protection.OpLockdown
import kz.bejiihiu.servercore.protection.ProtectionConfig
import kz.bejiihiu.servercore.protection.ProtectionLifecycle
import kz.bejiihiu.servercore.protection.ServerCoreCommands
import kz.bejiihiu.servercore.ui.UiKit
import kz.bejiihiu.servercore.util.I18n
import kz.bejiihiu.servercore.websocket.DelayScheduler
import kz.bejiihiu.servercore.websocket.GatewayClient
import kz.bejiihiu.servercore.websocket.GatewayListener
import kz.bejiihiu.servercore.websocket.Payload
import kz.bejiihiu.servercore.websocket.ServerStatsProvider
import kz.bejiihiu.servercore.ws.BackendGateway
import com.mojang.brigadier.CommandDispatcher
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * single entry point, folia-only. all scheduling goes through folia's
 * async/entity schedulers, never the bukkit one.
 */
class ServerCorePlugin : JavaPlugin() {

    lateinit var ui: UiKit
        private set

    lateinit var locale: LocaleService
        private set

    private var wsGateway: GatewayClient? = null
    private var presence: PlayerPresenceService? = null
    private lateinit var backendGateway: BackendGateway
    private lateinit var sessions: GuestSessionService
    private lateinit var snapshots: SnapshotStore
    private lateinit var accountsCache: LocalAccountCache
    private lateinit var counter: GuestSessionCounter
    private lateinit var io: java.util.concurrent.ExecutorService
    private var updater: UpdaterService? = null
    private var restartSchedule: RestartScheduleTask? = null
    private lateinit var opsBus: OpsEventBus
    private var gamemodeGuard: GamemodeGuardListener? = null
    private var commandBlockAuditor: CommandBlockAuditor? = null

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

        // presence + gateway client: liveness, PLAYER_JOINED/LEFT, account events.
        // bad config is a boot blocker: better dead than half-connected
        val gatewayConfig = try {
            ConfigLoader.load(config)
        } catch (e: IllegalArgumentException) {
            logger.severe("config error: ${e.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        val stats = object : ServerStatsProvider {
            override fun onlinePlayers(): Int = presence?.onlineCount() ?: 0
            override fun maxPlayers(): Int = server.maxPlayers
            override fun serverVersion(): String = server.getMinecraftVersion()
        }

        val gatewayListener = object : GatewayListener {
            override fun onConnected() {
                logger.info("backend gateway connected as ${gatewayConfig.gateway.serverId}")
                // re-announce live guest sessions so both sides agree on reality
                sessions.reconcile()
            }

            override fun onDisconnected() {
                logger.warning("backend gateway disconnected, reconnect scheduled")
            }

            override fun onLinkToken(payload: Payload.LinkToken) {
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
            config = gatewayConfig.gateway,
            listener = gatewayListener,
            scheduler = delayScheduler,
            stats = stats,
            presenceLookup = { uuid -> presence?.isOnline(uuid) ?: false },
        )
        wsGateway = client

        val presenceService = PlayerPresenceService(this, client)
        presence = presenceService
        server.pluginManager.registerEvents(presenceService, this)

        // guest mode stack (uses its own local gateway handle for envelope-level
        // helpers; both handles share the same backend connection semantics)
        val guestConfig = GuestConfig.load(config, logger)
        val i18n = I18n(this, guestConfig.lang)

        snapshots = SnapshotStore(dataFolder.toPath().resolve(guestConfig.snapshotStoragePath), io)
        snapshots.load()

        counter = GuestSessionCounter(dataFolder.toPath().resolve("guest-session-counts.json"), io)
        counter.load()

        accountsCache = LocalAccountCache(dataFolder.toPath().resolve("account-cache.json"), io)
        accountsCache.load()

        backendGateway = BackendGateway()

        val wsResolver = WsAccountResolver(backendGateway, accountsCache, guestConfig)
        val accounts = AccountService(
            backendGateway,
            wsResolver,
            LocalAccountResolver(guestConfig, accountsCache),
        )
        val messages = GuestMessageService(guestConfig, wsResolver, i18n)
        sessions = GuestSessionService(
            this, guestConfig, accounts, wsResolver, snapshots, counter, backendGateway, messages,
        )

        // backend confirmed a login -> ungate the player
        wsResolver.onAuthApproved { uuid -> sessions.onAuthApproved(uuid) }
        // personal deep link arrived after the generic prompt went out
        wsResolver.onLinkAvailable { uuid -> sessions.onLinkAvailable(uuid) }

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

        // protection stack: command denial, gamemode guard, op lockdown.
        // guest sessions keep their players in spectator, so that specific
        // shape is sanctioned through the guard's seam
        val protectionConfig = ProtectionConfig.load(config, logger)
        if (protectionConfig.enabled) {
            setupProtection(protectionConfig, i18n, sessions)
        } else {
            logger.warning("protection disabled in config: command and gamemode guards are off")
        }

        // heartbeat keeps the backend's liveness picture fresh
        presenceService.startHeartbeat()
        client.start()

        // boot sequence over the bus: starting now, running once the server
        // had a moment to settle
        opsBus.publish(ServerState.STARTING)
        server.globalRegionScheduler.runDelayed(this, { _ -> opsBus.publish(ServerState.RUNNING) }, 100L)

        logger.info("servercore ${pluginMeta.version} enabled (guest mode: ${if (guestConfig.enabled) "on" else "off"})")
    }

    /**
     * wires the whole protection stack: command denial + alias expansion,
     * gamemode guard with sweep, command block audit, op lockdown and the
     * /servercore admin command.
     */
    private fun setupProtection(protectionConfig: ProtectionConfig, i18n: I18n, sessions: GuestSessionService) {
        val dispatcherRef = AtomicReference<CommandDispatcher<CommandSourceStack>?>(null)
        val aliasTree = CommandTreeSources.combined(
            commandMap = { runCatching { Bukkit.getCommandMap() }.getOrNull() },
            dispatcherRef = dispatcherRef,
        )

        val service = CommandProtectionService(protectionConfig, { aliasTree }, logger)
        service.refreshAliases()
        server.pluginManager.registerEvents(
            CommandProtectionListener(protectionConfig, service, i18n, logger),
            this,
        )

        val registry = AllowedGamemodeOverrideRegistry()
        val guard = GamemodeGuardListener(
            plugin = this,
            config = protectionConfig,
            registry = registry,
            logger = logger,
            // guests live in spectator for their whole session; that state is
            // sanctioned by the session service, not by op status
            extraSanction = { uuid, mode ->
                mode == org.bukkit.GameMode.SPECTATOR && sessions.isRestricted(uuid)
            },
        )
        server.pluginManager.registerEvents(guard, this)
        guard.start()
        gamemodeGuard = guard

        val auditor = CommandBlockAuditor(this, protectionConfig, service, logger)
        server.pluginManager.registerEvents(auditor, this)
        auditor.start()
        commandBlockAuditor = auditor

        val opLockdown = OpLockdown(this, logger)
        opLockdown.verifyOps()
        ProtectionLifecycle(
            plugin = this,
            dispatcherRef = dispatcherRef,
            onAliasesChanged = { service.refreshAliases() },
            serverCoreCommands = ServerCoreCommands(this, opLockdown),
        ).attach()
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
        wsGateway?.stop()
        updater?.stop()
        restartSchedule?.stop()
        gamemodeGuard?.stop()
        commandBlockAuditor?.stop()
        // belt and braces: async snapshot writes may still be in flight
        snapshots.shutdown()
        counter.shutdown()
        accountsCache.shutdown()
        io.shutdown()
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
