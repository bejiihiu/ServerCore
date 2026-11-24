package kz.bejiihiu.servercore.util

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kz.bejiihiu.servercore.ui.Theme
import net.kyori.adventure.text.Component
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin

/**
 * tiny key-based i18n for player-facing text, reusable by every later phase
 * (guest.*, auth.*, admission.* ...). two layers: the bundled english file is
 * the base, the language file (bundled or user-edited in the data folder)
 * overrides it. missing keys fall back to english, missing everywhere fall
 * back to the key itself so a typo never blanks the chat.
 *
 * files live flat in the plugin data folder: messages_en.yml, messages_ru.yml.
 * on first run the bundled ones are copied out so admins can edit them.
 */
class I18n(private val plugin: JavaPlugin, lang: String) {

    private var base: YamlConfiguration
    private var active: YamlConfiguration

    init {
        base = loadEmbedded("messages_en.yml")
        exportBundled("messages_en.yml")
        val langFile = "messages_${lang.lowercase(Locale.ROOT)}.yml"
        val exported = exportBundled(langFile)
        active = when {
            exported -> loadFromData(langFile)
            Files.exists(plugin.dataFolder.toPath().resolve(langFile)) -> loadFromData(langFile)
            else -> base
        }
    }

    fun text(key: String, vararg placeholders: Pair<String, String>): String =
        format(template(key), placeholders)

    /** multiline entries, empty lines preserved, trailing blanks dropped */
    fun lines(key: String, vararg placeholders: Pair<String, String>): List<String> =
        rawLines(key).map { format(it, placeholders) }

    /**
     * one key rendered for a player: theme tags applied, placeholder values
     * escaped. feature code sends this, never a raw miniMessage string — that
     * is how a <red> from an old locale file reaches a client.
     */
    fun component(key: String, vararg placeholders: Pair<String, String>): Component =
        Theme.render(template(key), placeholders.toMap())

    /** same, for callers that already hold a value map */
    fun component(key: String, values: Map<String, String>): Component =
        Theme.render(template(key), values)

    /** every line of a block key, each rendered and escaped the same way */
    fun components(key: String, vararg placeholders: Pair<String, String>): List<Component> =
        rawLines(key).map { Theme.render(it, placeholders.toMap()) }

    /** same, for callers that already hold a value map */
    fun components(key: String, values: Map<String, String>): List<Component> =
        rawLines(key).map { Theme.render(it, values) }

    private fun template(key: String): String =
        active.getString(key) ?: base.getString(key) ?: key

    private fun rawLines(key: String): List<String> {
        val raw = active.getStringList(key).ifEmpty { base.getStringList(key) }
        // list entries and block scalars both end up one line per element
        val lines = if (raw.isNotEmpty()) raw else template(key).split('\n')
        return lines.dropLastWhile { it.isBlank() }
    }

    private fun format(template: String, placeholders: Array<out Pair<String, String>>): String =
        placeholders.fold(template) { acc, (name, value) -> acc.replace("{$name}", value) }

    private fun loadEmbedded(resource: String): YamlConfiguration {
        plugin.getResource(resource)?.use { stream ->
            return YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
        }
        // broken jar, better loud than silent
        throw IllegalStateException("bundled resource $resource is missing from the plugin jar")
    }

    private fun loadFromData(fileName: String): YamlConfiguration =
        YamlConfiguration.loadConfiguration(plugin.dataFolder.toPath().resolve(fileName).toFile())

    /** copies the bundled file into the data folder unless the admin already edited one */
    private fun exportBundled(resource: String): Boolean {
        if (Files.exists(plugin.dataFolder.toPath().resolve(resource))) return false
        val stream = plugin.getResource(resource) ?: return false
        Files.createDirectories(plugin.dataFolder.toPath())
        stream.use { Files.copy(it, plugin.dataFolder.toPath().resolve(resource)) }
        return true
    }
}
