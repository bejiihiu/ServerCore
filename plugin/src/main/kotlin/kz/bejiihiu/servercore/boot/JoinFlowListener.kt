package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.ui.UiKit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * join-time identity surface: the themed tab list for every arrival.
 *
 * the guest/auth flow used to be demo-rendered here before the real guest
 * stack landed; the fake welcome + invented auth code ran for every joiner,
 * authenticated staff included. the real flow (GuestSessionService +
 * GuestMessageService) owns all of that now — UiKit.guestWelcome/authPrompt
 * stay available for a future code-based confirmation flow, nothing fires
 * them on join anymore.
 */
class JoinFlowListener(
    private val kit: UiKit,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        kit.tabList(event.player)
    }
}
