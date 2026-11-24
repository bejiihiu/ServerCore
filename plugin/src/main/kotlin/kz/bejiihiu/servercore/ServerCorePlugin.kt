package kz.bejiihiu.servercore

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kz.bejiihiu.servercore.boot.JoinFlowListener
import kz.bejiihiu.servercore.config.ConfigLoader
import kz.bejiihiu.servercore.esp.EspConfig
import kz.bejiihiu.servercore.esp.EspModule
import kz.bejiihiu.servercore.esp.NoopViewStateApplier
import kz.bejiihiu.servercore.esp.SpecObserverSource
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
import kz.bejiihiu.servercore.disguise.DisguiseStack
import kz.bejiihiu.servercore.integration.flectonepulse.FlectonePulseBridge
import kz.bejiihiu.servercore.integration.flectonepulse.FlectonePulseConfig
import kz.bejiihiu.servercore.integration.luckperms.GroupsDebugHandler
import kz.bejiihiu.servercore.integration.luckperms.LuckPermsConfig
import kz.bejiihiu.servercore.integration.luckperms.PermissionSource
import kz.bejiihiu.servercore.integration.luckperms.PermissionSourceFactory
import kz.bejiihiu.servercore.boot.GroupsDebugCommand
import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.packet.CachedViewStateStore
import kz.bejiihiu.servercore.packet.PacketConfig
import kz.bejiihiu.servercore.packet.PacketService
import kz.bejiihiu.servercore.packet.PacketThreads
import kz.bejiihiu.servercore.packet.WsSyncedViewStateStore
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
import kz.bejiihiu.servercore.spec.SpecAuditConfig
import kz.bejiihiu.servercore.spec.SpecAuditTrail
import kz.bejiihiu.servercore.spec.SpecEventSender
import kz.bejiihiu.servercore.spec.SpecSessionRegistry
import kz.bejiihiu.servercore.sync.EntityIdentityTracker
import kz.bejiihiu.servercore.sync.InMemoryViewStateRegistry
import kz.bejiihiu.servercore.sync.PacketResyncExecutor
import kz.bejiihiu.servercore.sync.PacketStateListener
import kz.bejiihiu.servercore.sync.PacketStateSync
import kz.bejiihiu.servercore.sync.SyncReconciler
import kz.bejiihiu.servercore.sync.VanillaOnlyResyncExecutor
import kz.bejiihiu.servercore.spectator.InventoryViewerService
import kz.bejiihiu.servercore.spectator.ObserverHider
import kz.bejiihiu.servercore.spectator.SpecObserverConfig
import kz.bejiihiu.servercore.spectator.SpecResyncListener
import kz.bejiihiu.servercore.spectator.SpecSessionRegistry as SpectatorSpecSessionRegistry
import kz.bejiihiu.servercore.spectator.SpecTraceListener
import kz.bejiihiu.servercore.spectator.SpecViewStateApplier
import kz.bejiihiu.servercore.spectator.TraceFilter
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
import kz.bejiihiu.servercore.spec.BukkitHiddenObserver
import kz.bejiihiu.servercore.spec.FoliaSpecEffects
import kz.bejiihiu.servercore.spec.GamemodeSanctionBridge
import kz.bejiihiu.servercore.spec.InventoryViewerBridge
import kz.bejiihiu.servercore.spec.InventoryViewerViews
import kz.bejiihiu.servercore.spec.LuckPermsAdapter
import kz.bejiihiu.servercore.spec.ObserverHidingBridge
import kz.bejiihiu.servercore.spec.SpecCommand
import kz.bejiihiu.servercore.spec.SpecConfig
import kz.bejiihiu.servercore.spec.SpecPermissionPolicy
import kz.bejiihiu.servercore.spec.SpecRestoreJournal
import kz.bejiihiu.servercore.spec.SpecService
import kz.bejiihiu.servercore.spec.SessionCoordinator
import kz.bejiihiu.servercore.spec.VanillaAdapter
import kz.bejiihiu.servercore.ui.UiKit
import kz.bejiihiu.servercore.util.I18n
import kz.bejiihiu.servercore.websocket.DelayScheduler
import kz.bejiihiu.servercore.websocket.DurableOutbox
import kz.bejiihiu.servercore.websocket.GatewayClient
import kz.bejiihiu.servercore.websocket.GatewayListener
import kz.bejiihiu.servercore.websocket.MessageType
import kz.bejiihiu.servercore.websocket.Payload
import kz.bejiihiu.servercore.websocket.ServerStatsProvider
import kz.bejiihiu.servercore.ws.BackendGateway
import kz.bejiihiu.servercore.ws.GatewayLink
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.newMessageId
import kotlinx.serialization.json.JsonObject
import com.mojang.brigadier.CommandDispatcher
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.text.Component
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
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
    private var esp: EspModule? = null
    private var papiExpansion: ServerCoreExpansion? = null
    private lateinit var opsBus: OpsEventBus
    private var packetService: PacketService? = null
    private lateinit var inventoryViewer: InventoryViewerService
    private var disguiseStack: DisguiseStack? = null
    private var gamemodeGuard: GamemodeGuardListener? = null
    private var commandBlockAuditor: CommandBlockAuditor? = null
    private var spec: SpecService? = null
    private var permissionSource: PermissionSource? = null
    private var specTrail: SpecAuditTrail? = null
    private var specRegistry: SpecSessionRegistry? = null
    private var specHider: ObserverHider? = null
    private var specTraceListener: com.github.retrooper.packetevents.event.PacketListenerCommon? = null
    private lateinit var flectonePulse: FlectonePulseBridge

    override fun onLoad() {
        // packetevents is a server-installed plugin we attach to, not one we
        // bundle — pre-flight just checks it is on the server, the shared
        // instance boots with its own plugin lifecycle
        saveDefaultConfig()
        val packetConfig = PacketConfig.load(config)
        if (packetConfig.enabled && server.pluginManager.getPlugin("PacketEvents") == null) {
            logger.warning("packetevents plugin not found on this server, packet layer will idle")
        }
    }

    override fun onEnable() {
        saveDefaultConfig()

        io = Executors.newVirtualThreadPerTaskExecutor()

        locale = LocaleService(dataFolder.toPath(), logger)
        ui = UiKit(this, locale)

        // flectonepulse bridge goes up before the ops bus: status frames carry
        // its telegram suppression flag, and the guest flow reads punishments
        // through it once connected
        val fpConfig = FlectonePulseConfig.load(config, logger)
        val fpBridge = FlectonePulseBridge(this, fpConfig, logger)
        flectonePulse = fpBridge

        // ops first: the bus is the seam the ws client hooks into (SERVER_STATUS
        // frames), and the updater needs the plugin meta version before anything else
        val ops = OpsConfig.load(config, logger)
        opsBus = OpsEventBus(
            logger,
            onlinePlayers = { Bukkit.getOnlinePlayers().size },
            maxPlayers = { Bukkit.getMaxPlayers() },
            version = { pluginMeta.version },
            telegramSuppressed = { fpBridge.telegramSuppressed() },
        )
        fpBridge.start()

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
        val pluginConfig = try {
            ConfigLoader.load(config, logger)
        } catch (e: IllegalArgumentException) {
            logger.severe("config error: ${e.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        val stats = object : ServerStatsProvider {
            override fun onlinePlayers(): Int = presence?.onlineCount() ?: 0
            override fun maxPlayers(): Int = server.maxPlayers
            override fun serverVersion(): String = server.getMinecraftVersion()
            override fun activeSpecSessions(): Int = specRegistry?.activeCount() ?: 0
        }

        // durable outbox: guest lifecycle frames survive reconnects AND plugin
        // restarts, parked on disk until the backend acknowledges them
        val durableOutbox = DurableOutbox(
            file = dataFolder.toPath().resolve("ws-outbox.json"),
            io = io,
            maxEntries = pluginConfig.gateway.outboxMaxEntries,
        )
        durableOutbox.load()

        // the link adapter is created after the client (it wraps it), so the
        // listener captures a mutable reference
        var gatewayLink: GatewayLink? = null
        val gatewayListener = object : GatewayListener {
            override fun onConnected() {
                logger.info("backend gateway connected as ${pluginConfig.gateway.serverId}")
                // the guest hub goes live with the transport, then both sides
                // re-sync what happened while the link was down
                gatewayLink?.let { backendGateway.attach(it) }
                sessions.reconcile()
            }

            override fun onDisconnected() {
                gatewayLink?.let { backendGateway.detach(it) }
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

        // folia async scheduler drives reconnect delays and the watchdog; the
        // client itself is bukkit-free, so its callbacks may run on any async thread
        val delayScheduler = DelayScheduler { task: Runnable, delayMs: Long ->
            server.asyncScheduler.runDelayed(this, { _ -> task.run() }, delayMs, TimeUnit.MILLISECONDS)
        }

        val client = GatewayClient(
            config = pluginConfig.gateway,
            listener = gatewayListener,
            scheduler = delayScheduler,
            stats = stats,
            presenceLookup = { uuid -> presence?.isOnline(uuid) ?: false },
            durablePark = durableOutbox,
        )
        wsGateway = client
        gatewayLink = GatewayLink(client)

        val presenceService = PlayerPresenceService(this, client, { specRegistry?.activeCount() ?: 0 })
        presence = presenceService
        server.pluginManager.registerEvents(presenceService, this)

        // spec reporting bridge: every settled session start/end goes to the
        // backend over the websocket (the backend writes the audit_logs rows);
        // the local audit trail is the same events as a daily-rotating jsonl
        val specAuditConfig = pluginConfig.specAudit
        val specTrail = if (specAuditConfig.enabled) {
            SpecAuditTrail(
                directory = dataFolder.toPath().resolve(specAuditConfig.directory),
                io = io,
                retentionDays = specAuditConfig.retentionDays.toLong(),
                maxEntriesPerSession = specAuditConfig.maxEntriesPerSession,
                queueCapacity = specAuditConfig.queueCapacity,
            )
        } else null
        val specSender = object : SpecEventSender {
            override fun sendSpecStarted(payload: Payload.SpecStarted) {
                client.send(MessageType.SPEC_STARTED, payload)
            }

            override fun sendSpecEnded(payload: Payload.SpecEnded) {
                client.send(MessageType.SPEC_ENDED, payload)
            }
        }
        this.specTrail = specTrail
        specRegistry = SpecSessionRegistry(specSender, specTrail)

        // guest mode stack (its hub is attached to the real transport below;
        // until the link is up every send fails closed to local behavior)
        val guestConfig = GuestConfig.load(config, logger)
        i18n = I18n(this, guestConfig.lang)

        val lpConfig = LuckPermsConfig.load(config, logger)
        val permissions = PermissionSourceFactory.create(lpConfig, logger)
        permissionSource = permissions
        GroupsDebugCommand.handler = GroupsDebugHandler(permissions, i18n, logger)

        snapshots = SnapshotStore(dataFolder.toPath().resolve(guestConfig.snapshotStoragePath), io)
        snapshots.load()
        // with restore-on-restart off the leftover snapshots of a previous run
        // are dropped here, otherwise they would still restore on the next
        // join through the store itself and the flag would do nothing
        if (!guestConfig.restoreOnRestart) snapshots.clearPending()

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
            punishmentQuery = { fpBridge.punishmentQuery() },
        )

        // backend confirmed a login -> ungate the player
        wsResolver.onAuthApproved { uuid -> sessions.onAuthApproved(uuid) }
        // personal deep link arrived after the generic prompt went out
        wsResolver.onLinkAvailable { uuid -> sessions.onLinkAvailable(uuid) }

        // packetevents layer: personal entity view foundation. attaches its
        // listener to the shared PE instance only when packetevents is
        // reachable, otherwise idles without breaking boot
        val packetConfig = PacketConfig.load(config)
        val packets = PacketService(
            plugin = this,
            config = packetConfig,
            store = WsSyncedViewStateStore(
                CachedViewStateStore(
                    maxViewers = 1024,
                    maxTargetsPerViewer = 512,
                ),
            ),
            threads = PacketThreads(this),
        )
        packets.start()
        packetService = packets

        // packet disguise layer: client-only entities for the anti-esp
        // visibility engine. it registers its own packetevents listener, so it
        // only comes up when the packet pipeline is actually alive; without
        // pe the engine's disguise verdicts degrade to plain hiding below
        disguiseStack = if (packets.online) DisguiseStack.create(this, config, logger) else null
        if (!packets.online) {
            logger.warning("packet layer idle, disguise layer staying off")
        }

        // packet state sync: every client-view state transition goes through
        // the double-load-guarded coordinator. with pe up, resyncs run through
        // the packet bridge — it keeps the policy rows, mirrors the applied
        // picture into the packet registry and drives the disguise service,
        // so an invalidation emits real packets. without pe the vanilla-only
        // fallback keeps registry state correct without emitting packets
        val viewStates = InMemoryViewStateRegistry()
        val entityIds = EntityIdentityTracker()
        val bridge = if (packets.online) {
            PacketResyncExecutor(packets.registry, viewStates, disguiseStack?.applier, logger)
        } else {
            null
        }
        packetSync = PacketStateSync.create(
            states = viewStates,
            resync = bridge ?: VanillaOnlyResyncExecutor(logger),
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

        // live read-only inventory view for spec sessions. the session machinery
        // lands on its own branch and calls open()/close() through this service;
        // everything else (packet rewrite, read-only guard, flush loop, cleanup)
        // is self-contained and inert while no viewer is open
        inventoryViewer = InventoryViewerService(this, pluginConfig.spec, i18n)
        inventoryViewer.start()

        // hidden observer layer for /spec: while an admin watches a player,
        // the admin's entity vanishes from that client (destroy + suppressed
        // spawns/data + tab removal), and comes back intact on session end.
        // the session coordinator drives it through the hiding bridge; this
        // registry stays the seam the esp engine and resync paths read
        val observerConfig = SpecObserverConfig.load(config, logger)
        val observerSessions = SpectatorSpecSessionRegistry()
        val traceFilter = TraceFilter(observerConfig.traceRadius, observerConfig.suppressActionBar)
        val hider = ObserverHider(this, packets, observerSessions, traceFilter, syncStates = viewStates)
        val traceListener = SpecTraceListener(observerSessions, packets.registry, hider::poseOf, traceFilter)
        if (packets.register(traceListener)) {
            specTraceListener = traceListener
        } else {
            logger.warning("packet layer idle, hidden observer suppression inactive")
        }
        server.pluginManager.registerEvents(SpecResyncListener(hider, observerSessions), this)
        specHider = hider

        // protection stack: command denial, gamemode guard, op lockdown.
        // guest sessions keep their players in spectator, so that specific
        // shape is sanctioned through the guard's seam
        val protectionConfig = ProtectionConfig.load(config, logger)
        val overrideRegistry = AllowedGamemodeOverrideRegistry()
        if (protectionConfig.enabled) {
            setupProtection(protectionConfig, i18n, sessions, overrideRegistry)
        } else {
            logger.warning("protection disabled in config: command and gamemode guards are off")
        }

        // spectator observation stack: brigadier command + session machine +
        // local audit. soft-dependency on luckperms, vanilla fallback otherwise
        val specConfig = SpecConfig.load(config, logger)
        if (specConfig.enabled) {
            val luckPerms: LuckPerms? = if (specConfig.permission.useLuckPerms) {
                // luckperms not on the server (or not initialized yet) is not fatal,
                // the vanilla-permission fallback covers access decisions
                runCatching { LuckPermsProvider.get() }.getOrNull()
            } else null
            if (specConfig.permission.useLuckPerms && luckPerms == null) {
                logger.warning("spec: luckperms requested but not available, falling back to vanilla permission checks")
            }

            val adapters = buildList {
                if (luckPerms != null) add(LuckPermsAdapter(luckPerms))
                add(VanillaAdapter { uuid -> server.getPlayer(uuid) })
            }
            val specPolicy = SpecPermissionPolicy(
                adapters = adapters,
                levels = specConfig.permission.levels,
                specPermission = specConfig.permission.specPermission,
                fallbackLevelIndex = specConfig.permission.fallbackLevelIndex,
            )
            val specJournal = SpecRestoreJournal(dataFolder.toPath().resolve("spec-restore-journal.json"), io)
            specJournal.load()
            val specEffects = FoliaSpecEffects(this, BukkitHiddenObserver(this), specJournal, logger, overrideRegistry)
            // the session machine is the single owner of the truth; the
            // bridges hang off its lifecycle callbacks:
            // - gamemode sanction: the guard sees the grant before the
            //   spectator switch fires its event
            // - packet hiding: the admin vanishes from the target the moment
            //   the session settles, comes back the moment it ends
            // - inventory viewer: the live view opens with the session
            val specBridges = listOf(
                GamemodeSanctionBridge(overrideRegistry),
                ObserverHidingBridge(hider),
                InventoryViewerBridge(InventoryViewerViews(this, inventoryViewer)),
            )
            val specCoordinator = SessionCoordinator(specEffects, specRegistry, specBridges)
            val specService = SpecService(
                plugin = this,
                config = specConfig,
                policy = specPolicy,
                luckPerms = luckPerms,
                coordinator = specCoordinator,
                effects = specEffects,
                journal = specJournal,
                reports = specRegistry,
                i18n = i18n,
            )
            spec = specService

            server.pluginManager.registerEvents(
                object : Listener {
                    @EventHandler(priority = EventPriority.LOWEST)
                    fun onJoin(event: PlayerJoinEvent) = specService.onJoin(event.player)

                    @EventHandler(priority = EventPriority.MONITOR)
                    fun onQuit(event: PlayerQuitEvent) = specService.onQuit(event.player.uniqueId)
                },
                this,
            )

            lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
                SpecCommand(specService).register(event.registrar())
            }
        }

        // anti-esp visibility engine; guests flag flows in so guest-viewing-guest
        // stays vanilla, the spec registry flags in so a watching admin renders
        // as nothing to the player they watch
        val espConfig = EspConfig.load(config, logger)
        if (espConfig.enabled) {
            esp = EspModule.create(
                this,
                espConfig,
                guests = sessions::isRestricted,
                spec = SpecObserverSource { observerSessions.isObserver(it) },
            ).also { it.start() }
            // the spec applier wraps the packet bridge (the no-op when pe is
            // absent and no writer exists at all): observer pairs are forced
            // hidden, everything else passes straight through
            esp?.let { module ->
                module.engine.applier = SpecViewStateApplier(
                    observerSessions,
                    hider,
                    bridge ?: NoopViewStateApplier,
                )
            }
        }

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

        // heartbeat keeps the backend's liveness picture fresh
        presenceService.startHeartbeat()
        client.start()

        registerPlaceholderExpansion()

        // boot sequence over the bus: starting now, running once the server
        // had a moment to settle
        opsBus.publish(ServerState.STARTING)
        server.globalRegionScheduler.runDelayed(this, { _ -> opsBus.publish(ServerState.RUNNING) }, 100L)

        logger.info(
            "servercore ${pluginMeta.version} enabled " +
                "(guest mode: ${if (guestConfig.enabled) "on" else "off"}, " +
                "flectonepulse: ${fpBridge.currentState().name.lowercase()})",
        )
    }

    /**
     * wires the whole protection stack: command denial + alias expansion,
     * gamemode guard with sweep, command block audit, op lockdown and the
     * /servercore admin command.
     */
    private fun setupProtection(
        protectionConfig: ProtectionConfig,
        i18n: I18n,
        sessions: GuestSessionService,
        overrideRegistry: AllowedGamemodeOverrideRegistry,
    ) {
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

        val registry = overrideRegistry
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
        // sessions end first, while the packetevents pipeline is still fully
        // alive: the lifecycle bridges restore client views and admin modes
        // through the normal session-end path
        spec?.shutdown()
        // hidden observer pairs restore next, in deterministic order, as a
        // belt-and-braces pass over anything the session sweep did not cover
        specHider?.shutdown()
        specTraceListener?.let { packetService?.unregister(it) }
        // detach our listener first, the shared packetevents instance must outlive us
        packetService?.stop()
        wsGateway?.stop()
        esp?.stop()
        updater?.stop()
        restartSchedule?.stop()
        if (::inventoryViewer.isInitialized) inventoryViewer.shutdown()
        disguiseStack?.shutdown()
        gamemodeGuard?.stop()
        commandBlockAuditor?.stop()
        permissionSource?.shutdown()
        // belt and braces: async snapshot writes may still be in flight.
        // lateinit-guarded: a config-error boot lands here with the stores
        // never constructed at all
        if (::snapshots.isInitialized) snapshots.shutdown()
        if (::counter.isInitialized) counter.shutdown()
        if (::accountsCache.isInitialized) accountsCache.shutdown()
        // the trail flushes synchronously before the io pool goes away
        specTrail?.close()
        if (::io.isInitialized) io.shutdown()
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
