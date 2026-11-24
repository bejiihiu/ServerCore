package kz.bejiihiu.servercore.locale

import kz.bejiihiu.servercore.ui.Placeholders
import kz.bejiihiu.servercore.ui.Theme
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * runtime locale service.
 *
 * ships a bundled canonical RU seed and lives off it until the backend pushes
 * a fresh full file over the ws channel. pushes arrive on the ws thread: the
 * new content is parsed off the hot path, then swapped in atomically, so
 * region threads rendering components never see a half-built store.
 */
class LocaleService(
    dataDir: Path,
    private val logger: Logger,
) {

    private val file: Path = dataDir.resolve("locales/${LocaleProtocol.CANONICAL}.yml")

    @Volatile
    private var store: LocaleStore

    @Volatile
    var revision: String = "bundled"
        private set

    init {
        Files.createDirectories(file.parent)
        store = loadInitial()
    }

    private fun loadInitial(): LocaleStore {
        if (Files.exists(file)) {
            try {
                val persisted = Files.readString(file)
                val parsed = LocaleStore.parse(persisted)
                if (!parsed.isEmpty) {
                    // revision of persisted pushes is unknown after a restart,
                    // mark it so the next push can re-deliver the same file
                    revision = "persisted"
                    return parsed
                }
                logger.warning("persisted locale file is empty, falling back to the bundled seed")
            } catch (e: Exception) {
                logger.warning("persisted locale file is broken (${e.message}), falling back to the bundled seed")
            }
        }
        return loadBundled()
    }

    private fun loadBundled(): LocaleStore {
        val stream = javaClass.getResourceAsStream("/locales/${LocaleProtocol.CANONICAL}.yml")
            ?: error("bundled locale seed missing from the jar")
        val parsed = LocaleStore.parse(stream.readAllBytes().decodeToString())
        stream.close()
        return parsed
    }

    /** raw miniMessage template with placeholders substituted; missing key renders as the key itself */
    fun text(key: String, values: Map<String, String> = emptyMap()): String =
        Placeholders.substitute(store.raw(key) ?: key, values)

    /** template rendered into a themed adventure component (substitution happens exactly once) */
    fun component(key: String, values: Map<String, String> = emptyMap()) =
        Theme.render(text(key, values))

    /** multi-line template split into lines */
    fun lines(key: String, values: Map<String, String> = emptyMap()): List<String> =
        text(key, values).split('\n')

    /**
     * applies a full locale pushed by the backend. thread-safe: parses first,
     * swaps the volatile store only on success, then persists to disk so the
     * file survives a restart.
     */
    fun applyPush(locale: String, newRevision: String, content: String): LocalePushResult {
        if (locale != LocaleProtocol.CANONICAL) {
            return LocalePushResult.Rejected(
                locale, newRevision,
                "only the canonical locale is accepted, got: $locale",
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
        store = parsed
        revision = newRevision
        try {
            Files.writeString(file, content)
        } catch (e: Exception) {
            // memory already serves the pushed locale; a failed write only
            // means a restart falls back to the seed, so warn and keep going
            logger.warning("could not persist pushed locale: ${e.message}")
        }
        return LocalePushResult.Accepted(locale, newRevision)
    }

    /** test helper: current live entries, read-only view */
    fun snapshot(): Map<String, String> = store.keys().associateWith { store.raw(it)!! }
}
