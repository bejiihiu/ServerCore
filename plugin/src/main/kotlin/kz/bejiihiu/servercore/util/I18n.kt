package kz.bejiihiu.servercore.util

import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.ui.Theme
import net.kyori.adventure.text.Component

/**
 * compatibility facade over [LocaleService] for the feature surfaces: guest
 * lifecycle, auth payoff, observation replies, protection denials, permission
 * debug and the placeholder values. the flat `messages_*.yml` layer is gone —
 * this class only keeps the simple get/format/component surface so feature
 * code does not have to learn the locale service.
 *
 * language selection lives on the service (the same knob the toolkit reads),
 * so constructing the facade binds the shared service to the configured ui
 * language.
 */
class I18n(private val locale: LocaleService, lang: String) {

    init {
        locale.setLanguage(lang)
    }

    fun text(key: String, vararg placeholders: Pair<String, String>): String =
        locale.text(key, placeholders.toMap())

    /** multiline entries, one line per element, trailing blanks dropped by the store */
    fun lines(key: String, vararg placeholders: Pair<String, String>): List<String> =
        locale.lines(key, placeholders.toMap())

    /**
     * one key rendered for a player: theme tags applied, placeholder values
     * escaped. feature code sends this, never a raw miniMessage string — that
     * is how a <red> from an old locale file reaches a client.
     */
    fun component(key: String, vararg placeholders: Pair<String, String>): Component =
        locale.component(key, placeholders.toMap())

    /** same, for callers that already hold a value map */
    fun component(key: String, values: Map<String, String>): Component =
        locale.component(key, values)

    /** every line of a block key, each rendered and escaped the same way */
    fun components(key: String, vararg placeholders: Pair<String, String>): List<Component> =
        locale.lines(key, placeholders.toMap()).map { Theme.render(it) }

    /** same, for callers that already hold a value map */
    fun components(key: String, values: Map<String, String>): List<Component> =
        locale.lines(key, values).map { Theme.render(it) }
}
