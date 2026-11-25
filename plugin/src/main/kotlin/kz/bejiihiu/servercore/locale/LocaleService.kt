package kz.bejiihiu.servercore.locale

import kz.bejiihiu.servercore.ui.Placeholders
import kz.bejiihiu.servercore.ui.Theme
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * runtime locale service and the one text store of the plugin.
 *
 * ships bundled seeds for every [LocaleProtocol.SUPPORTED] locale and lives off
 * them until the backend pushes a fresh full file over the ws channel. pushes
 * arrive on the ws thread: the new content is parsed off the hot path, then
 * swapped in atomically, so region threads rendering components never see a
 * half-built store.
 *
 * reads resolve against the active language, then fall back to
 * [LocaleProtocol.FALLBACK], then to the key itself — a typo never blanks the
 * chat. full-file semantics: a push replaces the whole store for its locale.
 */
class LocaleService(
    dataDir: Path,
    private val logger: Logger,
) {

    private val directory: Path = dataDir.resolve("locales")

    /** live stores per locale; the map is replaced, never mutated, on every push */
    @Volatile
    private var stores: Map<String, LocaleStore>

    /** content revision per locale, as last pushed by the backend */
    @Volatile
    private var revisions: Map<String, String>

    /** language the player-facing reads resolve against */
    @Volatile
    var language: String = LocaleProtocol.CANONICAL
        private set

    init {
        Files.createDirectories(directory)
        val loaded = loadInitial()
        stores = loaded.stores
        revisions = loaded.revisions
    }

    /** revision of the active language, "bundled" until a push lands */
    val revision: String
        get() = revisions[language] ?: "bundled"

    /**
     * binds the ui language (from config); unknown tags stay canonical so a
     * typo in config never blanks the server.
     */
    fun setLanguage(language: String?) {
        this.language = LocaleProtocol.normalize(language)
    }

    /** raw miniMessage template with placeholders substituted; missing key renders as the key itself */
    fun text(key: String, values: Map<String, String> = emptyMap()): String =
        Placeholders.substitute(template(key) ?: key, values)

    /**
     * raw template for a key, before any substitution; null when neither the
     * active language nor the base layer has it. this is the read a cached
     * renderer keys on.
     */
    fun template(key: String): String? = active().raw(key) ?: base().raw(key)

    /** template rendered into a themed adventure component (substitution happens exactly once) */
    fun component(key: String, values: Map<String, String> = emptyMap()) =
        Theme.render(text(key, values))

    /** multi-line template split into lines, trailing blanks dropped */
    fun lines(key: String, values: Map<String, String> = emptyMap()): List<String> =
        text(key, values).split('\n').dropLastWhile { it.isBlank() }

    /**
     * applies a full locale pushed by the backend. thread-safe: parses first,
     * swaps the volatile stores only on success, then persists to disk so the
     * file survives a restart. accepts every supported locale, so the backend
     * can push ru and en independently.
     */
    fun applyPush(locale: String, newRevision: String, content: String): LocalePushResult {
        if (!LocaleProtocol.isSupported(locale)) {
            return LocalePushResult.Rejected(
                locale, newRevision,
                "unsupported locale, expected one of ${LocaleProtocol.SUPPORTED.joinToString("/")}, got: $locale",
            )
        }
        val parsed = try {
            LocaleStore.parse(content)
        } catch (e: Exception) {
            return LocalePushResult.Rejected(locale, newRevision, "yaml parse failed: ${e.message}")
        }
        if (parsed.isEmpty) {
            return LocalePushResult.Rejected(locale, newRevision, "pushed locale has no entries")
        }
        stores = stores + (locale to parsed)
        revisions = revisions + (locale to newRevision)
        try {
            Files.writeString(fileFor(locale), content)
        } catch (e: Exception) {
            // memory already serves the pushed locale; a failed write only
            // means a restart falls back to the seed, so warn and keep going
            logger.warning("could not persist pushed locale $locale: ${e.message}")
        }
        return LocalePushResult.Accepted(locale, newRevision)
    }

    /** test helper: live entries of one locale (the active one by default), read-only view */
    fun snapshot(locale: String = language): Map<String, String> {
        val store = stores[locale] ?: stores.getValue(LocaleProtocol.CANONICAL)
        return store.keys().associateWith { store.raw(it)!! }
    }

    private fun active(): LocaleStore = stores[language] ?: stores.getValue(LocaleProtocol.CANONICAL)

    private fun base(): LocaleStore = stores[LocaleProtocol.FALLBACK] ?: stores.getValue(LocaleProtocol.CANONICAL)

    private fun fileFor(locale: String): Path = directory.resolve("$locale.yml")

    private class Loaded(val stores: Map<String, LocaleStore>, val revisions: Map<String, String>)

    private fun loadInitial(): Loaded {
        val loaded = LinkedHashMap<String, LocaleStore>()
        val revisions = LinkedHashMap<String, String>()
        for (locale in LocaleProtocol.SUPPORTED) {
            val persisted = loadPersisted(locale)
            if (persisted != null) {
                // revision of persisted pushes is unknown after a restart,
                // mark it so the next push can re-deliver the same file
                loaded[locale] = persisted
                revisions[locale] = "persisted"
            } else {
                loaded[locale] = loadBundled(locale)
            }
        }
        return Loaded(loaded, revisions)
    }

    private fun loadPersisted(locale: String): LocaleStore? {
        val file = fileFor(locale)
        if (!Files.exists(file)) return null
        return try {
            val parsed = LocaleStore.parse(Files.readString(file))
            if (parsed.isEmpty) {
                logger.warning("persisted locale file is empty, falling back to the bundled seed: $locale")
                null
            } else {
                parsed
            }
        } catch (e: Exception) {
            logger.warning("persisted locale file is broken (${e.message}), falling back to the bundled seed: $locale")
            null
        }
    }

    private fun loadBundled(locale: String): LocaleStore {
        val stream = javaClass.getResourceAsStream("/locales/$locale.yml")
            ?: error("bundled locale seed missing from the jar: $locale")
        val parsed = LocaleStore.parse(stream.readAllBytes().decodeToString())
        stream.close()
        return parsed
    }
}
