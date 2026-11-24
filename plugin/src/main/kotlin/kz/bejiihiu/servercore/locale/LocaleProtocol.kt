package kz.bejiihiu.servercore.locale

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * locale channel of the ws protocol. the backend owns the canonical bundle
 * (shipped in its image), renders the canonical content and pushes the full
 * file to every connected server — the plugin never fetches anything itself
 * (no http).
 */
object LocaleProtocol {

    /** backend → plugin: full content of one locale file */
    const val TYPE_LOCALE_PUSH = "LOCALE_PUSH"

    /** plugin → backend: outcome of an applied push, correlates with the push */
    const val TYPE_LOCALE_ACK = "LOCALE_ACK"

    /** the primary locale: the backend serves this one, the plugin boots on it */
    const val CANONICAL = "ru"

    /** bundled locales; a full-file push is accepted for any of them */
    val SUPPORTED = listOf("ru", "en")

    /**
     * base layer for reads: a key missing from the active language resolves
     * here before falling back to the key itself.
     */
    const val FALLBACK = "en"

    /** config language tag → a bundled locale; anything unknown stays canonical */
    fun normalize(language: String?): String {
        val tag = language?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (tag in SUPPORTED) tag else CANONICAL
    }

    fun isSupported(locale: String): Boolean = locale in SUPPORTED
}

/** body of a LOCALE_PUSH message; content is the full yaml file, not a diff */
@Serializable
data class LocalePushPayload(
    val locale: String,
    val revision: String,
    val content: String,
)

/** body of the LOCALE_ACK the plugin answers with */
@Serializable
data class LocaleAckPayload(
    val locale: String,
    val revision: String,
    val accepted: Boolean,
    val reason: String? = null,
)

/** what applyPush decided, in a form the ws layer can map straight to an ack */
sealed interface LocalePushResult {
    data class Accepted(val locale: String, val revision: String) : LocalePushResult
    data class Rejected(val locale: String, val revision: String, val reason: String) : LocalePushResult
}
