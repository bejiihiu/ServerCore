package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.spectator.InventoryViewerService

/**
 * Live read-only inventory view for spec sessions.
 *
 * The session machinery calls open()/close() through this service; everything
 * else (packet rewrite, read-only guard, flush loop, cleanup) is self-contained
 * and inert while no viewer is open.
 */
class ViewerInstaller(private val ctx: PluginContext) {

    lateinit var viewer: InventoryViewerService
        private set

    fun install() {
        viewer = InventoryViewerService(ctx.plugin, ctx.pluginConfig.spec, ctx.i18n)
        viewer.start()
    }

    fun stop() {
        if (this::viewer.isInitialized) viewer.shutdown()
    }
}
