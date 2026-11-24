package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.config.ConfigLoader

/**
 * Loads the plugin settings every later installer reads. A bad backend config
 * is a boot blocker: better dead than half-connected.
 */
class ConfigInstaller(private val ctx: PluginContext) {

    /** @return false when the config is unusable and the boot must abort */
    fun install(): Boolean {
        ctx.pluginConfig = try {
            ConfigLoader.load(ctx.plugin.config, ctx.logger)
        } catch (e: IllegalArgumentException) {
            ctx.logger.severe("config error: ${e.message}")
            return false
        }
        return true
    }
}
