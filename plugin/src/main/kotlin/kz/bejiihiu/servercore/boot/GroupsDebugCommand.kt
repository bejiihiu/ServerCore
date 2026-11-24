package kz.bejiihiu.servercore.boot

import io.papermc.paper.command.brigadier.CommandSourceStack

/**
 * the /servercore command surface as seen from bootstrap time. paper plugins
 * register commands during bootstrap, but the real handler (permission
 * source, i18n) only exists once the plugin enables — this holder bridges
 * the two phases. until then the command politely reports it is not ready.
 */
object GroupsDebugCommand {

    @Volatile
    var handler: Handler? = null

    interface Handler {
        fun execute(stack: CommandSourceStack, args: Array<out String>)

        fun suggest(stack: CommandSourceStack, args: Array<out String>): List<String>
    }
}
