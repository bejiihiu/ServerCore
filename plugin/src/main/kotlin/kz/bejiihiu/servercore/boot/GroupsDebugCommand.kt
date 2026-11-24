package kz.bejiihiu.servercore.boot

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import kz.bejiihiu.servercore.ui.Theme
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender

/**
 * the /servercore command surface. registration happens at enable time, but the
 * real handler (permission source, i18n) is wired a few lines later — this
 * holder bridges the gap. until then the command politely reports it is not
 * ready.
 */
object GroupsDebugCommand {

    @Volatile
    var handler: Handler? = null

    interface Handler {
        fun execute(stack: CommandSourceStack, args: Array<out String>)

        fun suggest(stack: CommandSourceStack, args: Array<out String>): List<String>
    }
}

/** brigadier-facing shell; all behavior lives behind the runtime handler */
internal class GroupsDebugBasicCommand : BasicCommand {

    override fun execute(stack: CommandSourceStack, args: Array<out String>) {
        val handler = GroupsDebugCommand.handler
        if (handler == null) {
            // the locale store is not up yet, so this one line is hardcoded on
            // purpose; it only shows inside the first seconds of boot
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
