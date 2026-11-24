package kz.bejiihiu.servercore.boot

/**
 * The ui toolkit's own listeners. The leaf kit itself is built with the
 * context; this installer only hooks the surfaces that want it.
 */
class UiInstaller(private val ctx: PluginContext) {

    fun install() {
        // demo surface for the ui toolkit until real flows consume it everywhere
        ctx.plugin.server.pluginManager.registerEvents(JoinFlowListener(ctx.ui), ctx.plugin)
    }
}
