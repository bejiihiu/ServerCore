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
import kz.bejiihiu.servercore.placeholderapi.ServerCoreExpansion
import kz.bejiihiu.servercore.placeholderapi.ServerStateCache
import kz.bejiihiu.servercore.placeholderapi.PlaceholderConfig
import kz.bejiihiu.servercore.placeholderapi.PlaceholderResolver
import kz.bejiihiu.servercore.placeholderapi.PlaceholderText
import kz.bejiihiu.servercore.placeholderapi.StatePlayerListener
import kz.bejiihiu.servercore.placeholderapi.StateWsListener
import kz.bejiihiu.servercore.player.PlayerPresenceService
import kz.bejiihiu.servercore.sync.EntityIdentityTracker
import kz.bejiihiu.servercore.sync.InMemoryViewStateRegistry
import kz.bejiihiu.servercore.sync.PacketStateListener
import kz.bejiihiu.servercore.sync.PacketStateSync
import kz.bejiihiu.servercore.sync.SyncReconciler
import kz.bejiihiu.servercore.sync.VanillaOnlyResyncExecutor
import kz.bejiihiu.servercore.ui.UiKit
import kz.bejiihiu.servercore.util.I18n
import kz.bejiihiu.servercore.websocket.DelayScheduler
import kz.bejiihiu.servercore.websocket.GatewayClient
import kz.bejiihiu.servercore.websocket.GatewayListener
import kz.bejiihiu.servercore.websocket.Payload
import kz.bejiihiu.servercore.websocket.ServerStatsProvider
import kz.bejiihiu.servercore.ws.BackendGateway
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.newMessageId
import kotlinx.serialization.json.JsonObject
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

    lateinit var i18n: I18n
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
    private var packetSync: PacketStateSync? = null
    private var syncReconciler: SyncReconciler? = null
    private var papiExpansion: ServerCoreExpansion? = null
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

            // raw fan-out bridge: the ws/* modules speak WsEnvelope, the typed
            // transport speaks Payload. every validated inbound frame travels
            // through here as json, whatever its type. only safe after
            // backendGateway is assigned, which is guaranteed because the
            // client only starts receiving frames once started below
            override fun onWireMessage(
                wireType: String,
                payload: JsonObject,
                correlationId: String?,
                timestamp: Long,
            ) {
                backendGateway.receive(
                    WsEnvelope(
                        id = newMessageId(),
                        type = wireType,
                        timestamp = timestamp,
                        correlationId = correlationId,
                        payload = payload,
                    ),
                )
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
        i18n = I18n(this, guestConfig.lang)

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

        // packet state sync: every client-view state transition goes through
        // the double-load-guarded coordinator. the registry and the packet
        // writer are seams — the packetevents writer plugs into ResyncExecutor
        // when it lands; until then the vanilla-only fallback keeps registry
        // state correct without emitting packets.
        val viewStates = InMemoryViewStateRegistry()
        val entityIds = EntityIdentityTracker()
        packetSync = PacketStateSync.create(
            states = viewStates,
            resync = VanillaOnlyResyncExecutor(logger),
            identities = entityIds,
            logger = logger,
        )
        val sync = packetSync
        if (sync == null) {
            logger.severe("packet state sync unavailable (double load), listeners not registered")
        } else {
            server.pluginManager.registerEvents(PacketStateListener(sync, entityIds), this)
            syncReconciler = SyncReconciler(this, sync).also { it.start() }
        }

        // heartbeat keeps the backend's liveness picture fresh
        presenceService.startHeartbeat()
        client.start()

        registerPlaceholderExpansion()

        // boot sequence over the bus: starting now, running once the server
        // had a moment to settle
        opsBus.publish(ServerState.STARTING)
        server.globalRegionScheduler.runDelayed(this, { _ -> opsBus.publish(ServerState.RUNNING) }, 100L)

        logger.info("servercore ${pluginMeta.version} enabled (guest mode: ${if (guestConfig.enabled) "on" else "off"})")
    }

    /**
     * placeholderapi integration. papi is a soft-depend: not installed means
     * the whole state cache stays cold and the plugin runs exactly as before,
     * so this is a plain presence check, not a hard requirement.
     */
    private fun registerPlaceholderExpansion() {
        if (server.pluginManager.getPlugin("PlaceholderAPI") == null) {
            logger.info("placeholderapi not installed, servercore placeholders disabled")
            return
        }
        val stateCache = ServerStateCache()
        // backend facts: auth verdicts, application verdicts, account deletion
        backendGateway.addListener(StateWsListener(stateCache))
        // local facts: join/quit timestamps, live session time, cache seeding
        server.pluginManager.registerEvents(
            StatePlayerListener(stateCache, accountsCache),
            this,
        )
        val expansion = ServerCoreExpansion(
            plugin = this,
            resolver = PlaceholderResolver(
                provider = stateCache,
                config = PlaceholderConfig.load(config),
                strings = PlaceholderText.fromI18n(i18n),            ),
        )
        val registered = runCatching { expansion.register() }
            .onFailure { logger.warning("placeholderapi expansion registration threw: ${it.message}") }
            .getOrDefault(false)
        if (registered) {
            papiExpansion = expansion
            logger.info("placeholderapi expansion registered: %servercore_<status|authenticated|application_status|telegram_linked|last_seen|session_duration>%")
        } else {
            logger.warning("placeholderapi expansion registration failed, placeholders stay off")
        }
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
        // reload safety first: every modified client view goes back to vanilla
        // truth before anything else shuts down, so a reload leaves no ghosts
        papiExpansion?.let { expansion ->
            runCatching { expansion.unregister() }
                .onFailure { logger.warning("placeholderapi expansion unregister failed: ${it.message}") }
        }
        papiExpansion = null
        syncReconciler?.stop()
        packetSync?.let { sync ->
            sync.restoreAllForReload()
            sync.release()
        }
        wsGateway?.stop()
        updater?.stop()
        restartSchedule?.stop()
        // belt and braces: async snapshot writes may still be in flight
        snapshots.shutdown()
        counter.shutdown()
        accountsCache.shutdown()
        io.shutdown()
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
