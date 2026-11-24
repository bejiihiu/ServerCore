package kz.bejiihiu.servercore.boot

import java.util.function.Consumer

/**
 * The /servercore tree: registered last, after the permission source and the
 * protection stack are up, so both handlers are already in place when the
 * commands lifecycle event fires.
 *
 * Registration deliberately lives here instead of a bootstrapper: a command
 * registered during bootstrap outlives a failed plugin load, and paper's help
 * map then trips on the missing plugin instance and npes the whole server.
 * An admin command does not need to be visible to datapack functions.
 */
class AdminCommandInstaller(
    private val ctx: PluginContext,
    private val integrations: IntegrationInstaller,
    private val protection: ProtectionInstaller,
) {

    fun install() {
        val plugin = ctx.plugin
        ServerCoreCommand(
            groups = { integrations.groupsHandler },
            deopAll = { protection.opLockdown?.deopAll() },
            runOnGlobalThread = { task ->
                plugin.server.globalRegionScheduler.run(plugin, Consumer { task.run() })
            },
        ).attach(plugin)
    }
}
