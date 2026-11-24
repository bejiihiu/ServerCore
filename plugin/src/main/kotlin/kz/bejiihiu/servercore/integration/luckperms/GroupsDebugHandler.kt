package kz.bejiihiu.servercore.integration.luckperms

import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.UUID
import java.util.logging.Logger
import kz.bejiihiu.servercore.boot.ServerCoreCommand
import kz.bejiihiu.servercore.ui.UiScheduler.uiNow
import kz.bejiihiu.servercore.util.I18n
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * /servercore groups <player> — admin-gated debug output listing a player's
 * luckperms groups (real data from the active permission source).
 *
 * threading: the target lookup happens on the command thread (the region
 * thread that owns the player registry), the permission check and the lp
 * group query complete on their own callback threads. console may be written
 * to from anywhere, a player needs their entity scheduler — replies hop.
 */
class GroupsDebugHandler(
    private val plugin: Plugin,
    private val source: PermissionSource,
    private val i18n: I18n,
    private val logger: Logger,
) : ServerCoreCommand.Groups {

    override fun execute(stack: CommandSourceStack, args: Array<out String>) {
        if (args.size != 1) {
            reply(stack, text("luckperms.groups.usage", "arg" to "<player>"))
            return
        }

        // resolve the target before going async, the player registry belongs
        // to the thread this command runs on
        val targetName = args[0]
        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            reply(stack, text("luckperms.groups.player-not-found", "player" to targetName))
            return
        }
        val targetId = target.uniqueId

        val senderId = (stack.sender as? OfflinePlayer)?.uniqueId
        if (senderId == null) {
            // console has no uuid to check, it is inherently trusted
            sendGroups(stack, targetId, target.name)
            return
        }

        source.hasPermission(senderId, ServerCorePermissions.ADMIN).whenComplete { allowed, error ->
            if (error != null) {
                logger.warning("admin gate check failed for $senderId: $error")
            }
            if (allowed != true) {
                reply(stack, text("luckperms.groups.no-permission"))
            } else {
                sendGroups(stack, targetId, target.name)
            }
        }
    }

    override fun suggest(stack: CommandSourceStack, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase()
        return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(prefix) }
    }

    private fun sendGroups(stack: CommandSourceStack, uuid: UUID, name: String) {
        source.groups(uuid).whenComplete { groups, error ->
            if (error != null) {
                logger.warning("group lookup failed for $uuid: $error")
                reply(stack, text("luckperms.groups.lookup-failed"))
                return@whenComplete
            }
            if (groups.groups.isEmpty()) {
                reply(stack, text("luckperms.groups.none"))
                return@whenComplete
            }
            reply(stack, text("luckperms.groups.header", "player" to name))
            val primary = groups.primary
            for (group in groups.groups.sorted()) {
                val mark = if (primary != null && group.equals(primary, ignoreCase = true)) {
                    i18n.text("luckperms.groups.primary-mark")
                } else {
                    ""
                }
                reply(stack, text("luckperms.groups.entry", "group" to group, "primary" to mark))
            }
        }
    }

    /** template from i18n, rendered with theme tags and escaped values */
    private fun text(key: String, vararg values: Pair<String, String>): Component =
        i18n.component(key, *values)

    private fun reply(stack: CommandSourceStack, line: Component) {
        val sender = stack.sender
        if (sender is Player) {
            sender.uiNow(plugin) { sender.sendMessage(line) }
        } else {
            sender.sendMessage(line)
        }
    }
}
