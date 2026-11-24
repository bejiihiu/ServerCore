package kz.bejiihiu.servercore.integration.luckperms

import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.UUID
import java.util.logging.Logger
import kz.bejiihiu.servercore.boot.GroupsDebugCommand
import kz.bejiihiu.servercore.ui.Placeholders
import kz.bejiihiu.servercore.util.I18n
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.ConsoleCommandSender

/**
 * /servercore groups <player> — admin-gated debug output listing a player's
 * luckperms groups (real data from the active permission source).
 *
 * threading: the target lookup happens on the command thread (the region
 * thread that owns the player registry), everything after that is async —
 * the sender's permission check and the lp group query only touch adventure
 * sends from their callback threads.
 */
class GroupsDebugHandler(
    private val source: PermissionSource,
    private val i18n: I18n,
    private val logger: Logger,
) : GroupsDebugCommand.Handler {

    private val mini = MiniMessage.miniMessage()

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
                    text("luckperms.groups.primary-mark")
                } else {
                    ""
                }
                reply(stack, text("luckperms.groups.entry", "group" to group, "primary" to mark))
            }
        }
    }

    /** template from i18n, dynamic values escaped so names stay literal */
    private fun text(key: String, vararg values: Pair<String, String>): String =
        Placeholders.substitute(i18n.text(key), values.toMap())

    private fun reply(stack: CommandSourceStack, raw: String) {
        stack.sender.sendMessage(mini.deserialize(raw))
    }
}
