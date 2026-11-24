package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.integration.luckperms.GroupsDebugHandler
import kz.bejiihiu.servercore.integration.luckperms.LuckPermsConfig
import kz.bejiihiu.servercore.integration.luckperms.PermissionSource
import kz.bejiihiu.servercore.integration.luckperms.PermissionSourceFactory

/**
 * LuckPerms permission source plus the /servercore groups handler.
 *
 * The permission source is optional by design: without luckperms the factory
 * hands back the vanilla-backed source and nothing else changes.
 */
class IntegrationInstaller(private val ctx: PluginContext) {

    /** read lazily by the command tree; set here so the debug command works */
    var groupsHandler: GroupsDebugHandler? = null
        private set

    private var permissionSource: PermissionSource? = null

    fun install() {
        val permissions = PermissionSourceFactory.create(
            LuckPermsConfig.load(ctx.plugin.config, ctx.logger),
            ctx.logger,
        )
        permissionSource = permissions
        groupsHandler = GroupsDebugHandler(ctx.plugin, permissions, ctx.i18n, ctx.logger)
    }

    fun stop() {
        permissionSource?.shutdown()
    }
}
