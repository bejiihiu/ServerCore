package kz.bejiihiu.servercore.boot

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kz.bejiihiu.servercore.spec.BukkitHiddenObserver
import kz.bejiihiu.servercore.spec.FoliaSpecEffects
import kz.bejiihiu.servercore.spec.GamemodeSanctionBridge
import kz.bejiihiu.servercore.spec.InventoryViewerBridge
import kz.bejiihiu.servercore.spec.InventoryViewerViews
import kz.bejiihiu.servercore.spec.LuckPermsAdapter
import kz.bejiihiu.servercore.spec.ObserverHidingBridge
import kz.bejiihiu.servercore.spec.SessionCoordinator
import kz.bejiihiu.servercore.spec.SpecCommand
import kz.bejiihiu.servercore.spec.SpecConfig
import kz.bejiihiu.servercore.spec.SpecPermissionPolicy
import kz.bejiihiu.servercore.spec.SpecRestoreJournal
import kz.bejiihiu.servercore.spec.SpecService
import kz.bejiihiu.servercore.spec.VanillaAdapter
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Spectator observation stack: the brigadier command, the session machine and
 * the local audit journal.
 *
 * The session machine is the single owner of the truth; the bridges hang off
 * its lifecycle callbacks — gamemode sanction (through the protection
 * registry), packet hiding (the admin vanishes the moment a session settles)
 * and the live inventory view. LuckPerms is a soft dependency: without it the
 * vanilla-permission adapters cover access decisions.
 */
class SpecInstaller(
    private val ctx: PluginContext,
    private val protection: ProtectionInstaller,
    private val observer: SpecObserverInstaller,
    private val viewer: ViewerInstaller,
    private val reporting: SpecReportingInstaller,
) {

    private var service: SpecService? = null

    fun install() {
        val plugin = ctx.plugin
        val specConfig = SpecConfig.load(plugin.config, ctx.logger)
        if (!specConfig.enabled) return

        val luckPerms: LuckPerms? = if (specConfig.permission.useLuckPerms) {
            // luckperms not on the server (or not initialized yet) is not fatal,
            // the vanilla-permission fallback covers access decisions
            runCatching { LuckPermsProvider.get() }.getOrNull()
        } else null
        if (specConfig.permission.useLuckPerms && luckPerms == null) {
            ctx.logger.warning("spec: luckperms requested but not available, falling back to vanilla permission checks")
        }

        val adapters = buildList {
            if (luckPerms != null) add(LuckPermsAdapter(luckPerms))
            add(VanillaAdapter { uuid -> plugin.server.getPlayer(uuid) })
        }
        val policy = SpecPermissionPolicy(
            adapters = adapters,
            levels = specConfig.permission.levels,
            specPermission = specConfig.permission.specPermission,
            fallbackLevelIndex = specConfig.permission.fallbackLevelIndex,
        )
        val journal = SpecRestoreJournal(ctx.dataFolder.toPath().resolve("spec-restore-journal.json"), ctx.io)
        journal.load()
        val effects = FoliaSpecEffects(
            plugin,
            BukkitHiddenObserver(plugin),
            journal,
            ctx.logger,
            protection.overrideRegistry,
        )
        // the bridges hang off the session lifecycle:
        // - gamemode sanction: the guard sees the grant before the spectator
        //   switch fires its event
        // - packet hiding: the admin vanishes from the target the moment the
        //   session settles, comes back the moment it ends
        // - inventory viewer: the live view opens with the session
        val bridges = listOf(
            GamemodeSanctionBridge(protection.overrideRegistry),
            ObserverHidingBridge(observer.hider),
            InventoryViewerBridge(InventoryViewerViews(plugin, viewer.viewer)),
        )
        val coordinator = SessionCoordinator(effects, reporting.registry, bridges)
        // the heartbeat/stats counter forwards the coordinator's own count;
        // the reporting bridge keeps no second counter
        reporting.registry.bindActiveCount(coordinator::activeCount)
        val specService = SpecService(
            plugin = plugin,
            config = specConfig,
            policy = policy,
            luckPerms = luckPerms,
            coordinator = coordinator,
            effects = effects,
            journal = journal,
            reports = reporting.registry,
            i18n = ctx.i18n,
        )
        service = specService

        plugin.server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler(priority = EventPriority.LOWEST)
                fun onJoin(event: PlayerJoinEvent) = specService.onJoin(event.player)

                @EventHandler(priority = EventPriority.MONITOR)
                fun onQuit(event: PlayerQuitEvent) = specService.onQuit(event.player.uniqueId)
            },
            plugin,
        )

        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            SpecCommand(specService).register(event.registrar())
        }
    }

    fun stop() {
        // sessions end while the packetevents pipeline is still fully alive:
        // the lifecycle bridges restore client views and admin modes through
        // the normal session-end path
        service?.shutdown()
    }
}
