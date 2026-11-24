package kz.bejiihiu.servercore.guest

import java.util.UUID
import kz.bejiihiu.servercore.ui.UiFx
import kz.bejiihiu.servercore.ui.UiScheduler.uiNow
import kz.bejiihiu.servercore.util.I18n
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * every player-facing guest state lives here: welcome titles, chat prompt with
 * the bot link, actionbar hints, periodic reminders, kick screens. text comes
 * from the i18n layer (guest.* keys, theme tags allowed), the personal
 * one-time link wins over the generic bot url whenever the backend sent one.
 *
 * surfaces are picked by the design rules, not by convenience: the welcome is
 * a title (state change), the prompt is a chat block (scroll back to it), the
 * waiting reminder is an actionbar (continuous transient), the limit wall is a
 * title plus a card. every player touch hops onto the player's entity
 * scheduler, because callers include the async chat event and ws callbacks.
 */
class GuestMessageService(
    private val config: GuestConfig,
    private val linkSource: GuestLinkSource,
    private val i18n: I18n,
    private val plugin: Plugin,
) {

    private fun urlFor(uuid: UUID): String =
        linkSource.pendingLink(uuid)?.url ?: config.fallbackBotUrl

    private fun title(titleKey: String, subtitleKey: String): Pair<Component, Component> =
        i18n.component(titleKey) to i18n.component(subtitleKey)

    private fun actionbar(uuid: UUID, key: String = "guest.reminder.actionbar"): Component =
        i18n.component(key, "link" to urlFor(uuid))

    /** the register-now block: one frame so the link cannot interleave with chat */
    fun sendPrompt(player: Player, personalLink: String? = null) {
        val url = personalLink ?: urlFor(player.uniqueId)
        val lines = i18n.components("guest.link-prompt", "link" to url)
        send(player) { UiFx.sendFrame(player, lines) }
    }

    /** one-shot welcome the moment the guest session starts, the chat prompt is sent separately */
    fun sendWelcome(player: Player, restored: Boolean, session: Int, maxSessions: Int) {
        val subtitleKey = if (restored) "guest.welcome-back.subtitle" else "guest.welcome.subtitle"
        val values = mapOf("n" to session.toString(), "max" to maxSessions.toString())
        val t = i18n.component("guest.welcome.title", values)
        val s = i18n.component(subtitleKey, values)
        send(player) { UiFx.title(player, t, s, stayMs = WELCOME_STAY_MS) }
    }

    /** short and subtle, fired on a timer while the player waits for auth */
    fun sendReminder(player: Player) {
        val line = actionbar(player.uniqueId)
        send(player) { UiFx.actionBar(player, line) }
    }

    /** fired right after a restricted action was cancelled */
    fun sendRestrictionHint(player: Player) {
        // this one is called from the async chat event as well, hence the hop
        val line = i18n.component("guest.restriction.actionbar")
        send(player) { UiFx.actionBar(player, line) }
    }

    /** sessions exhausted but kick-after-limit is off: loud warning, guest continues */
    fun sendLimitWarning(player: Player) {
        val (t, s) = title("guest.limit.warning.title", "guest.limit.warning.subtitle")
        val lines = i18n.components(
            "guest.limit.chat",
            "link" to urlFor(player.uniqueId),
            "limit" to config.sessionLimit.toString(),
        )
        send(player) {
            UiFx.title(player, t, s, stayMs = WELCOME_STAY_MS)
            UiFx.sendFrame(player, lines)
        }
    }

    /** flectonepulse reports an active mute: one line so the chat silence is explained */
    fun sendMuteNotice(player: Player) {
        val line = UiFx.railLine(i18n.component("guest.mute-notice"))
        send(player) { player.sendMessage(line) }
    }

    /**
     * one session left: said before the wall, not at it. the actionbar already
     * nags about the link on a timer, so this is a single scroll-back line.
     */
    fun sendLastSessionNotice(player: Player) {
        val line = UiFx.railLine(i18n.component("guest.limit.last-session"))
        send(player) { player.sendMessage(line) }
    }

    /**
     * the payoff of the gate flow, after the snapshot is back and the player
     * is in normal play: one title, no card. a guest who never was one (an
     * authorized straight join) never sees this.
     */
    fun sendApproved(player: Player) {
        val (t, s) = title("auth.approved.title", "auth.approved.subtitle")
        send(player) { UiFx.title(player, t, s) }
    }

    /**
     * kick screens: the whole message goes to the disconnect screen, nothing
     * to chat. sending the lines first made the last one show up twice (chat
     * then disconnect) and the rest scrolled past unread.
     */
    fun kickMessage(): Component =
        UiFx.joinLines(i18n.components("guest.kick.banned"))

    fun guestDisabledMessage(): Component =
        UiFx.joinLines(i18n.components("guest.kick.disabled", "link" to config.fallbackBotUrl))

    fun limitKickMessage(): Component =
        UiFx.joinLines(
            i18n.components(
                "guest.kick.limit",
                "link" to config.fallbackBotUrl,
                "limit" to config.sessionLimit.toString(),
            ),
        )

    private fun send(player: Player, block: () -> Unit) {
        player.uiNow(plugin, block)
    }

    private companion object {
        // the welcome is the one headline a guest gets; give it room to land
        const val WELCOME_STAY_MS = 3200L
    }
}
