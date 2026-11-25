package kz.bejiihiu.servercore.diagnostics

import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.function.Consumer
import kz.bejiihiu.servercore.boot.PluginContext
import kz.bejiihiu.servercore.boot.ServerCoreCommand
import org.bukkit.entity.Player

/**
 * `/servercore diag`: collect the facts, assemble the report and print it —
 * card for a player, plain lines for console, one json document for `--json`.
 *
 * the read runs on the global region thread: counters and the plugin manager
 * are server-wide state, and folia has no main thread to lean on. a player's
 * card then hops to that player's entity scheduler through the ui kit, so no
 * frame is ever sent from the wrong thread.
 */
class DiagnosticsService(
    private val ctx: PluginContext,
    private val probe: DiagnosticsProbe,
    private val renderer: DiagnosticsRenderer,
) : ServerCoreCommand.Diagnostics {

    override fun run(source: CommandSourceStack, json: Boolean) {
        val plugin = ctx.plugin
        val sender = source.sender
        plugin.server.globalRegionScheduler.run(
            plugin,
            Consumer {
                val report = DiagnosticsAssembler.assemble(probe.facts())
                when {
                    json -> sender.sendPlainMessage(DiagnosticsJson.encode(report))
                    sender is Player -> ctx.ui.card(sender, renderer.card(report))
                    else -> renderer.console(report).forEach(sender::sendPlainMessage)
                }
            },
        )
    }
}
