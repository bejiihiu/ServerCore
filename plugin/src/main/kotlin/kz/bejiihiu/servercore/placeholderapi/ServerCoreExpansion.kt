package kz.bejiihiu.servercore.placeholderapi

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin

/**
 * the servercore placeholderapi expansion: %servercore_<param>% for the six
 * documented params (status, authenticated, application_status,
 * telegram_linked, last_seen, session_duration).
 *
 * class law, applies to every placeholder added here ever: values are served
 * from the in-memory [ServerStateSnapshotProvider] only. no synchronous
 * mongo/network/io call in onRequest, no exceptions — placeholderapi renders
 * on whatever thread the consuming plugin calls from, and a blocking call or
 * a throw here stalls somebody else's region thread. when a fact is missing
 * or old, the answer is the last known value, an "unknown" string or a stale
 * suffix, never a fetch.
 *
 * persists across placeholderapi reloads (persist = true), unregisters with
 * the plugin on disable.
 */
class ServerCoreExpansion(
    plugin: Plugin,
    private val resolver: PlaceholderResolver,
) : PlaceholderExpansion() {

    private val version: String = plugin.pluginMeta.version

    override fun getIdentifier(): String = IDENTIFIER

    override fun getAuthor(): String = "bejiihiu.xs"

    override fun getVersion(): String = version

    /** keep the registration through papi reloads, we manage it ourselves */
    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        resolver.resolve(params, player?.uniqueId)

    companion object {
        const val IDENTIFIER = "servercore"
    }
}
