package kz.bejiihiu.servercore.boot

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kz.bejiihiu.servercore.protection.OpLockdown
import kz.bejiihiu.servercore.protection.Permissions
import kz.bejiihiu.servercore.ui.Theme
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.plugin.java.JavaPlugin

/**
 * /servercore — the plugin's single administrative command tree.
 *
 * one root literal with both subcommands, so nothing overwrites anybody's
 * registration:
 * - `groups <player>`: debug output of a player's permission-source groups
 * - `deop-all`: strips operator status from every account holding it
 *
 * the root and every subcommand require servercore.admin, which luckperms
 * resolves; console is always allowed. handlers resolve lazily through the
 * suppliers, because the tree registers from the commands lifecycle event
 * while the permission source and the op lockdown came up during the boot.
 */
class ServerCoreCommand(
    private val groups: () -> Groups?,
    private val deopAll: () -> OpLockdown.DeopReport?,
    /** scheduler hop for the op-list mutation, never the calling thread */
    private val runOnGlobalThread: (Runnable) -> Unit,
) {

    /** the debug output handler behind `groups <player>` */
    interface Groups {
        fun execute(stack: CommandSourceStack, args: Array<out String>)

        fun suggest(stack: CommandSourceStack, args: Array<out String>): List<String>
    }

    /** hooks the commands lifecycle event; fires once the whole boot has run */
    fun attach(plugin: JavaPlugin) {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(build(), DESCRIPTION)
        }
    }

    /** the whole tree, exposed so its shape is testable without a server */
    fun build(): LiteralCommandNode<CommandSourceStack> = Commands.literal(ROOT)
        .requires { source ->
            source.sender is ConsoleCommandSender || source.sender.hasPermission(Permissions.ADMIN)
        }
        .then(groupsNode())
        .then(deopAllNode())
        .build()

    private fun groupsNode(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("groups")
        .executes { context -> executeGroups(context.source, emptyArray()) }
        .then(
            Commands.argument("player", StringArgumentType.word())
                .suggests { context, builder ->
                    groups()?.suggest(context.source, arrayOf(builder.remaining))?.forEach(builder::suggest)
                    builder.buildFuture()
                }
                .executes { context ->
                    executeGroups(context.source, arrayOf(context.getArgument("player", String::class.java)))
                },
        )

    private fun executeGroups(source: CommandSourceStack, args: Array<out String>): Int {
        val handler = groups()
        if (handler == null) {
            // the locale store is not up yet, so this one line is hardcoded on
            // purpose; it only shows inside the first seconds of boot
            source.sender.sendMessage(
                Theme.render("<muted>ServerCore is still starting up, try again in a moment</muted>"),
            )
        } else {
            handler.execute(source, args)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun deopAllNode(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("deop-all")
        .executes { context ->
            val sender = context.source.sender
            // ops list bookkeeping belongs on the global thread
            runOnGlobalThread {
                val report = deopAll() ?: return@runOnGlobalThread
                sender.sendPlainMessage(
                    "deop-all: removed operator status from ${report.removed} account(s)" +
                        if (report.names.isEmpty()) "" else ": ${report.names.joinToString()}",
                )
            }
            Command.SINGLE_SUCCESS
        }

    private companion object {
        const val ROOT = "servercore"
        const val DESCRIPTION = "servercore admin tools"
    }
}
