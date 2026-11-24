package kz.bejiihiu.servercore.boot

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kz.bejiihiu.servercore.ui.Theme
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender

/**
 * paper plugins register commands in the bootstrap phase, so /servercore is
 * wired up here and plugs into the real handler at enable time.
 */
class ServerCoreBootstrapper : PluginBootstrap {

    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register("servercore", "servercore admin tools", GroupsDebugBasicCommand())
        }
    }
}

/** brigadier-facing shell; all behavior lives behind the runtime handler */
internal class GroupsDebugBasicCommand : BasicCommand {

    override fun execute(stack: CommandSourceStack, args: Array<out String>) {
        val handler = GroupsDebugCommand.handler
        if (handler == null) {
            // bootstrap runs before the locale store exists, so this one line is
            // hardcoded on purpose; it only shows inside the first seconds of boot
            stack.sender.sendMessage(
                Theme.render("<muted>ServerCore is still starting up, try again in a moment</muted>"),
            )
        } else {
            handler.execute(stack, args)
        }
    }

    override fun suggest(stack: CommandSourceStack, args: Array<out String>): List<String> =
        GroupsDebugCommand.handler?.suggest(stack, args).orEmpty()

    // fast tab-completion pre-filter: luckperms-backed when installed, vanilla
    // ops otherwise. the authoritative gate runs in the handler itself, this
    // only keeps the command out of regular players' tab lists
    override fun canUse(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender || sender.hasPermission("servercore.admin")
}
