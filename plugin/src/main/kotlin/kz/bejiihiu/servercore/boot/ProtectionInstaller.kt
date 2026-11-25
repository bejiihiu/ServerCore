package kz.bejiihiu.servercore.boot

import java.util.concurrent.atomic.AtomicReference
import com.mojang.brigadier.CommandDispatcher
import io.papermc.paper.command.brigadier.CommandSourceStack
import kz.bejiihiu.servercore.protection.AllowedGamemodeOverrideRegistry
import kz.bejiihiu.servercore.protection.CommandBlockAuditor
import kz.bejiihiu.servercore.protection.CommandProtectionListener
import kz.bejiihiu.servercore.protection.CommandProtectionService
import kz.bejiihiu.servercore.protection.CommandTreeSources
import kz.bejiihiu.servercore.protection.GamemodeGuardListener
import kz.bejiihiu.servercore.protection.OpLockdown
import kz.bejiihiu.servercore.protection.ProtectionConfig
import kz.bejiihiu.servercore.protection.ProtectionLifecycle
import org.bukkit.Bukkit

/**
 * Protection stack: command denial + alias expansion, gamemode guard with its
 * sweep, command block audit and the op lockdown behind /servercore deop-all.
 *
 * The gamemode override registry and the op lockdown are created even when
 * protection is disabled: the spec stack sanctions its own mode grants through
 * the registry either way, and the admin command tree is always present.
 */
class ProtectionInstaller(
    private val ctx: PluginContext,
    private val guest: GuestInstaller,
) {

    lateinit var overrideRegistry: AllowedGamemodeOverrideRegistry
        private set

    /** backs /servercore deop-all; exists even with the guards switched off */
    var opLockdown: OpLockdown? = null
        private set

    private var gamemodeGuard: GamemodeGuardListener? = null
    private var commandBlockAuditor: CommandBlockAuditor? = null

    /** config flag, kept so the self-check can tell "off" from "failed" */
    var enabledByConfig: Boolean = false
        private set

    /** true when the command guards and the gamemode guard are registered */
    val active: Boolean get() = gamemodeGuard != null

    fun install() {
        val config = ProtectionConfig.load(ctx.plugin.config, ctx.logger)
        enabledByConfig = config.enabled
        val registry = AllowedGamemodeOverrideRegistry()
        overrideRegistry = registry
        val lockdown = OpLockdown(ctx.plugin, ctx.logger)
        opLockdown = lockdown
        if (config.enabled) {
            setup(config, registry, lockdown)
        } else {
            ctx.logger.warning("protection disabled in config: command and gamemode guards are off")
        }
    }

    private fun setup(
        protectionConfig: ProtectionConfig,
        registry: AllowedGamemodeOverrideRegistry,
        opLockdown: OpLockdown,
    ) {
        val plugin = ctx.plugin
        val i18n = ctx.i18n
        val dispatcherRef = AtomicReference<CommandDispatcher<CommandSourceStack>?>(null)
        val aliasTree = CommandTreeSources.combined(
            commandMap = { runCatching { Bukkit.getCommandMap() }.getOrNull() },
            dispatcherRef = dispatcherRef,
        )

        val service = CommandProtectionService(protectionConfig, { aliasTree }, ctx.logger)
        service.refreshAliases()
        plugin.server.pluginManager.registerEvents(
            CommandProtectionListener(plugin, protectionConfig, service, i18n, ctx.logger),
            plugin,
        )

        val guard = GamemodeGuardListener(
            plugin = plugin,
            config = protectionConfig,
            registry = registry,
            logger = ctx.logger,
            // guests live in spectator for their whole session; that state is
            // sanctioned by the session service, not by op status
            extraSanction = { uuid, mode ->
                mode == org.bukkit.GameMode.SPECTATOR && guest.sessions.isRestricted(uuid)
            },
        )
        plugin.server.pluginManager.registerEvents(guard, plugin)
        guard.start()
        gamemodeGuard = guard

        val auditor = CommandBlockAuditor(plugin, protectionConfig, service, ctx.logger)
        plugin.server.pluginManager.registerEvents(auditor, plugin)
        auditor.start()
        commandBlockAuditor = auditor

        opLockdown.verifyOps()
        ProtectionLifecycle(
            plugin = plugin,
            dispatcherRef = dispatcherRef,
            onAliasesChanged = { service.refreshAliases() },
        ).attach()
    }

    fun stop() {
        gamemodeGuard?.stop()
        commandBlockAuditor?.stop()
    }
}
