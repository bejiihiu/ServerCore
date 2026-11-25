package kz.bejiihiu.servercore.bars

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import java.util.concurrent.CompletableFuture
import kz.bejiihiu.servercore.locale.LocaleService
import net.kyori.adventure.text.Component
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * /rambar and /tpsbar, registered from the commands lifecycle event.
 *
 * purpur-compatible shape: bare form toggles yourself, an argument toggles
 * another player, and the literal "all" flips the whole online group. the
 * base node carries the bar's own permission, targeting others needs the
 * extra ".other" node, and the denial is a locale line instead of brigadier's
 * "unknown command" so a moderator knows why it did nothing.
 */
class BarCommands(
    private val plugin: Plugin,
    private val config: BarsConfig,
    private val service: InfoBarService,
    private val locale: LocaleService,
) {

    fun attach() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            for (bar in config.enabledBars()) {
                event.registrar().register(build(bar), "toggle the ${bar.key} server info bar")
            }
        }
    }

    /** the tree for one bar, exposed so its shape is testable without a server */
    fun build(bar: BarId): LiteralCommandNode<CommandSourceStack> = Commands.literal(bar.commandName)
        .requires { source -> source.sender.hasPermission(bar.permission) }
        .executes { context -> toggleSelf(context.source, bar) }
        .then(targetNode(bar))
        .build()

    private fun targetNode(bar: BarId): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("target", StringArgumentType.word())
            .suggests { context, builder -> suggest(context.source, builder, bar) }
            .executes { context ->
                toggleTarget(context.source, bar, StringArgumentType.getString(context, "target"))
            }

    private fun suggest(
        source: CommandSourceStack,
        builder: SuggestionsBuilder,
        bar: BarId,
    ): CompletableFuture<Suggestions> {
        val sender = source.sender
        if (sender is ConsoleCommandSender || sender.hasPermission(bar.otherPermission)) {
            builder.suggest(BarTarget.ALL)
            plugin.server.onlinePlayers
                .map { it.name }
                .filter { it.lowercase().startsWith(builder.remainingLowerCase) }
                .forEach(builder::suggest)
        }
        return builder.buildFuture()
    }

    private fun toggleSelf(source: CommandSourceStack, bar: BarId): Int {
        val player = source.sender as? Player
        if (player == null) {
            source.sender.sendMessage(locale.component("bars.players-only"))
            return Command.SINGLE_SUCCESS
        }
        val enabled = service.toggle(player, bar)
        player.sendMessage(feedback(bar, enabled, player.name))
        return Command.SINGLE_SUCCESS
    }

    private fun toggleTarget(source: CommandSourceStack, bar: BarId, raw: String): Int {
        val sender = source.sender
        if (!sender.hasPermission(bar.otherPermission)) {
            sender.sendMessage(locale.component("bars.no-permission-other"))
            return Command.SINGLE_SUCCESS
        }

        when (val target = BarTarget.parse(raw)) {
            BarTarget.All -> {
                val online = plugin.server.onlinePlayers.toList()
                if (online.isEmpty()) {
                    sender.sendMessage(locale.component("bars.all-empty"))
                    return Command.SINGLE_SUCCESS
                }
                val enabled = service.toggleAll(online, bar)
                sender.sendMessage(feedback(bar, enabled, locale.text("bars.all-target")))
            }

            is BarTarget.Named -> {
                val player = plugin.server.getPlayerExact(target.name)
                if (player == null) {
                    sender.sendMessage(locale.component("bars.player-not-found", mapOf("player" to target.name)))
                    return Command.SINGLE_SUCCESS
                }
                val enabled = service.toggle(player, bar)
                sender.sendMessage(feedback(bar, enabled, player.name))
            }

            // an argument can never arrive without a value, but the parser
            // has the case and the handler stays total
            BarTarget.Self -> return toggleSelf(source, bar)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun feedback(bar: BarId, enabled: Boolean, target: String): Component =
        locale.component(
            if (enabled) "bars.toggled-on" else "bars.toggled-off",
            mapOf("bar" to bar.label, "player" to target),
        )
}

/** what a /bar argument names; "all" is a literal, not a player called All */
sealed interface BarTarget {
    data object Self : BarTarget
    data object All : BarTarget
    data class Named(val name: String) : BarTarget

    companion object {
        const val ALL = "all"

        fun parse(raw: String?): BarTarget = when {
            raw == null -> Self
            raw.equals(ALL, ignoreCase = true) -> All
            else -> Named(raw.trim())
        }
    }
}
