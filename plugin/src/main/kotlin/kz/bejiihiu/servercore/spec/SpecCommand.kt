package kz.bejiihiu.servercore.spec

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player

/**
 * the /spec command, brigadier via the paper lifecycle api (the modern command
 * surface folia 26.3 ships). /spec leaves the current session, /spec <player>
 * opens one. permission is our own policy rather than a bukkit permission
 * node, because the admin group grants access without holding the node.
 */
class SpecCommand(private val service: SpecService) {

    fun register(registrar: Commands) {
        val node = Commands.literal("spec")
            .requires { stack -> stack.sender is Player && service.visibleFor((stack.sender as Player).uniqueId) }
            .executes { context -> executeExit(context) }
            .then(
                Commands.argument("target", StringArgumentType.word())
                    .suggests { context, builder ->
                        val sender = context.source.sender
                        if (sender is Player) {
                            sender.server.onlinePlayers
                                .map { it.name }
                                .filter { it.startsWith(builder.remainingLowerCase, ignoreCase = true) }
                                .forEach { builder.suggest(it) }
                        }
                        builder.buildFuture()
                    }
                    .executes { context -> executeStart(context) },
            )
        registrar.register(node.build(), "hidden administrative observation")
    }

    private fun executeExit(context: CommandContext<CommandSourceStack>): Int {
        val admin = context.source.playerOrThrow
        service.exit(admin) { component -> admin.sendMessage(component) }
        return 1
    }

    private fun executeStart(context: CommandContext<CommandSourceStack>): Int {
        val admin = context.source.playerOrThrow
        val targetName = StringArgumentType.getString(context, "target")
        service.start(admin, targetName, admin::sendMessage)
        return 1
    }
}
