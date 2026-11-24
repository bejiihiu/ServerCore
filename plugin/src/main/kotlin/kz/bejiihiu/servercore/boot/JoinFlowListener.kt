package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.ui.AuthPrompt
import kz.bejiihiu.servercore.ui.UiKit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * demo wiring for the ui toolkit: renders the guest arrival flow on every
 * real join event. this listener is the seam other agents will replace —
 * the guest-mode and account agents know who is actually a guest and when
 * an auth request is real; they call UiKit.guestWelcome / authPrompt with
 * real state instead of re-implementing any rendering.
 */
class JoinFlowListener(
    private val kit: UiKit,
) : Listener {

    private val prompts = ConcurrentHashMap<UUID, AuthPrompt>()

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        kit.tabList(player)
        // demo assumption until the guest agent lands: every joiner is a
        // first-session guest pending auth, three sessions to a window
        val prompt = kit.guestWelcome(player, session = 1, maxSessions = 3)
        prompts[event.player.uniqueId] = prompt
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        prompts.remove(event.player.uniqueId)?.stop()
    }
}
