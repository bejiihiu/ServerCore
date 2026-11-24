package kz.bejiihiu.servercore.placeholderapi

import org.bukkit.configuration.file.FileConfiguration

/**
 * knobs for the placeholder layer, all under the `placeholders:` config
 * section. every value here has a working default so an empty section is fine.
 */
data class PlaceholderConfig(
    /** string served for a player this server knows nothing about */
    val unknownValue: String,
    /** backend-derived facts older than this are flagged while the backend is offline */
    val staleAfterMs: Long,
    /** suffix appended to stale backend-derived values */
    val staleSuffix: String,
    /** how long a rendered placeholder value is memoized before recomputing */
    val cacheTtlMs: Long,
) {
    companion object {
        fun load(config: FileConfiguration): PlaceholderConfig = PlaceholderConfig(
            unknownValue = config.getString("placeholders.unknown-value", "unknown")!!
                .trim()
                .ifEmpty { "unknown" },
            staleAfterMs = config.getLong("placeholders.stale-after-minutes", 10L) * 60_000L,
            staleSuffix = config.getString("placeholders.stale-suffix", " (stale)")!!,
            cacheTtlMs = config.getLong("placeholders.cache-ttl-milliseconds", 2_000L),
        )
    }
}
