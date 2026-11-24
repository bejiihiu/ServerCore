package kz.bejiihiu.servercore.guest

import java.util.UUID
import kz.bejiihiu.servercore.util.I18n
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player

/**
 * every player-facing guest state lives here: welcome titles, chat prompt with
 * the bot link, actionbar hints, periodic reminders, kick screens. text comes
 * from the i18n layer (guest.* keys, mini-message tags allowed), the personal
 * one-time link wins over the generic bot url whenever the backend sent one.
 */
class GuestMessageService(
    private val config: GuestConfig,
    private val linkSource: GuestLinkSource,
    private val i18n: I18n,
) {

    private val mini = MiniMessage.miniMessage()

    fun urlFor(uuid: UUID): String =
        linkSource.pendingLink(uuid)?.url ?: config.fallbackBotUrl

    fun chatLines(uuid: UUID, key: String = "guest.link-prompt"): List<Component> =
        i18n.lines(key, "link" to urlFor(uuid)).map { mini.deserialize(it) }

    fun title(uuid: UUID, titleKey: String, subtitleKey: String): Pair<Component, Component> =
        mini.deserialize(i18n.text(titleKey)) to mini.deserialize(i18n.text(subtitleKey))

    fun actionbar(uuid: UUID, key: String = "guest.reminder.actionbar"): Component =
        mini.deserialize(i18n.text(key, "link" to urlFor(uuid)))

    fun sendPrompt(player: Player, personalLink: String? = null) {
        val url = personalLink ?: urlFor(player.uniqueId)
        i18n.lines("guest.link-prompt", "link" to url).forEach { player.sendMessage(mini.deserialize(it)) }
    }

    /** one-shot welcome the moment the guest session starts, the chat prompt is sent separately */
    fun sendWelcome(player: Player, restored: Boolean) {
        val (t, s) = title(
            player.uniqueId,
            "guest.welcome.title",
            if (restored) "guest.welcome-back.subtitle" else "guest.welcome.subtitle",
        )
        player.showTitle(net.kyori.adventure.title.Title.title(t, s))
    }

    /** short and subtle, fired on a timer while the player waits for auth */
    fun sendReminder(player: Player) {
        player.sendActionBar(actionbar(player.uniqueId))
    }

    /** fired right after a restricted action was cancelled */
    fun sendRestrictionHint(player: Player) {
        player.sendActionBar(mini.deserialize(i18n.text("guest.restriction.actionbar")))
    }

    /** sessions exhausted but kick-after-limit is off: loud warning, guest continues */
    fun sendLimitWarning(player: Player) {
        val (t, s) = title(player.uniqueId, "guest.limit.warning.title", "guest.limit.warning.subtitle")
        player.showTitle(net.kyori.adventure.title.Title.title(t, s))
        i18n.lines("guest.limit.chat", "link" to urlFor(player.uniqueId), "limit" to config.sessionLimit.toString())
            .forEach { player.sendMessage(mini.deserialize(it)) }
    }

    fun kickMessage(code: String): List<Component> =
        i18n.lines("guest.kick.banned").map { mini.deserialize(it) }

    fun guestDisabledMessage(): List<Component> =
        i18n.lines("guest.kick.disabled", "link" to config.fallbackBotUrl).map { mini.deserialize(it) }

    fun limitKickMessage(): List<Component> =
        i18n.lines("guest.kick.limit", "link" to config.fallbackBotUrl, "limit" to config.sessionLimit.toString())
            .map { mini.deserialize(it) }
}
