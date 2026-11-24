package kz.bejiihiu.servercore.protection

import com.mojang.brigadier.Command
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.function.Consumer
import org.bukkit.plugin.java.JavaPlugin

/**
 * /servercore — the plugin's own administrative surface, brigadier-native.
 * the root node and every subcommand require servercore.admin, which luckperms
 * resolves; console is always allowed. the actual work is pushed onto the
 * global scheduler so ops-list mutation never runs on a region thread.
 */
class ServerCoreCommands(
    private val plugin: JavaPlugin,
    private val opLockdown: OpLockdown,
) {

    /** called from the commands lifecycle event, re-runs on server reload */
    fun register(registrar: Commands) {
        val root = Commands.literal("servercore")
            .requires { source -> source.sender.hasPermission(Permissions.ADMIN) }
            .then(
                Commands.literal("deop-all")
                    .executes { context ->
                        val sender = context.source.sender
                        // ops list bookkeeping belongs on the global thread
                        plugin.server.globalRegionScheduler.run(
                            plugin,
                            Consumer { _ ->
                                val report = opLockdown.deopAll()
                                sender.sendPlainMessage(
                                    "deop-all: removed operator status from ${report.removed} account(s)" +
                                        if (report.names.isEmpty()) "" else ": ${report.names.joinToString()}",
                                )
                            },
                        )
                        Command.SINGLE_SUCCESS
                    },
            )
            .build()
        registrar.register(root, "servercore administration utilities")
    }
}
