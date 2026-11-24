package kz.bejiihiu.servercore.protection

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kz.bejiihiu.servercore.util.I18n
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerCommandSendEvent

/**
 * the enforcement surface every player-typed command passes through.
 *
 * hard layer: PlayerCommandPreprocessEvent at LOWEST cancels the command
 * before it is parsed or executed. sign-clicked commands arrive as
 * PlayerSignCommandPreprocessEvent, a subclass, so one handler covers both.
 *
 * cosmetic layer: PlayerCommandSendEvent strips denied names from the client
 * command list. display only, the handler above is the actual wall.
 *
 * deliberately absent here: console and rcon (trusted channels, see the
 * server policy), command block execution (no public event exists, the
 * auditor scans blocks instead) and other plugins invoking the command api
 * directly (not observable without server internals).
 */
class CommandProtectionListener(
    private val config: ProtectionConfig,
    private val service: CommandProtectionService,
    private val i18n: I18n,
    private val logger: Logger,
) : Listener {

    private val messageCooldown = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.LOWEST)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!config.enabled) return
        val match = service.match(event.message) ?: return
        event.isCancelled = true
        logger.warning(
            "denied command for ${event.player.name}: '${event.message}' (matched: ${match.matched}, ${match.kind})",
        )
        val player = event.player
        val now = System.currentTimeMillis()
        if (now - (messageCooldown[player.uniqueId] ?: 0) < MESSAGE_COOLDOWN_MS) return
        messageCooldown[player.uniqueId] = now
        player.sendMessage(i18n.text("protection.denied.chat", "command" to match.matched))
    }

    @EventHandler
    fun onCommandSend(event: PlayerCommandSendEvent) {
        if (!config.enabled || !config.hideFromTabComplete) return
        event.commands.removeAll { service.isDeniedForTabComplete(it) }
    }

    private companion object {
        const val MESSAGE_COOLDOWN_MS = 3000L
    }
}
