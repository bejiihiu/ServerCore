package kz.bejiihiu.servercore.util

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
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
        format(active.getString(key) ?: base.getString(key) ?: key, placeholders)

    /** multiline entries, empty lines preserved, trailing blanks dropped */
    fun lines(key: String, vararg placeholders: Pair<String, String>): List<String> {
        val raw = active.getStringList(key).ifEmpty { base.getStringList(key) }
        if (raw.isNotEmpty()) {
            return raw.dropLastWhile { it.isBlank() }.map { format(it, placeholders) }
        }
        // single-string keys still work as one-liners
        return listOf(text(key, *placeholders)).dropLastWhile { it.isBlank() }
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
