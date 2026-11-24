package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.integration.flectonepulse.FlectonePulseBridge
import kz.bejiihiu.servercore.integration.flectonepulse.FlectonePulseConfig

/**
 * The flectonepulse bridge: read-only punishment lookups plus the telegram
 * suppression flag the status frames carry.
 *
 * Only the object is built here. [FlectonePulseBridge.start] runs from the ops
 * installer, right after the event bus exists, because a start before the bus
 * would fire status frames into nothing.
 */
class FlectonePulseInstaller(private val ctx: PluginContext) {

    lateinit var bridge: FlectonePulseBridge
        private set

    fun install() {
        bridge = FlectonePulseBridge(
            ctx.plugin,
            FlectonePulseConfig.load(ctx.plugin.config, ctx.logger),
            ctx.logger,
        )
    }
}
