package kz.bejiihiu.servercore.packet

import java.util.logging.Logger
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.EntityType

/**
 * config for the packet layer. keys live in the existing anti-esp section and
 * are kept exactly as they are — no renames, the section predates this module.
 */
data class PacketConfig(
    val enabled: Boolean,
    /** view-distance-scale bound for visibility checks, blocks */
    val maxDistance: Int,
    /** cadence of the periodic visibility sweep, ticks */
    val checkIntervalTicks: Int,
    /** sneaking players drop to hidden for observers */
    val sneakHides: Boolean,
    val disguiseEnabled: Boolean,
    /** validated disguise mob registry; unknown names are dropped with a warning */
    val mobs: List<EntityType>,
    /** raw configured mob names, kept for config round-trips */
    val mobNames: List<String>,
    /** view-state cache lifetime, ticks */
    val cacheTtlTicks: Long,
) {
    /** ms view of the ttl, ticks are the config unit but eviction works in millis */
    val cacheTtlMillis: Long get() = cacheTtlTicks * 50L

    companion object {
        fun load(file: FileConfiguration, logger: Logger): PacketConfig {
            val section = file.getConfigurationSection("anti-esp")
            fun bool(name: String, default: Boolean) = section?.getBoolean(name, default) ?: default

            val disguise = section?.getConfigurationSection("disguise")
            val cache = section?.getConfigurationSection("cache")

            val rawNames = disguise?.getString("mobs", "")
                ?.split(',')?.mapNotNull { it.trim().ifEmpty { null } }.orEmpty()
            val known = EntityType.entries.associateBy { it.name }
            val mobs = rawNames.mapNotNull { raw ->
                val name = raw.trim().uppercase().replace(' ', '_')
                val type = known[name]
                if (type == null) {
                    logger.warning("anti-esp.disguise.mobs: '$raw' is not an entity type, entry ignored")
                }
                type
            }

            return PacketConfig(
                enabled = bool("enabled", true),
                maxDistance = section?.getInt("max-distance", 48)?.coerceIn(8, 128) ?: 48,
                checkIntervalTicks = section?.getInt("check-interval-ticks", 10)?.coerceIn(1, 200) ?: 10,
                sneakHides = bool("sneak-hides", true),
                disguiseEnabled = disguise?.getBoolean("enabled", true) ?: true,
                mobs = mobs,
                mobNames = rawNames,
                cacheTtlTicks = cache?.getLong("ttl-ticks", 40L)?.coerceIn(20L, 1200L) ?: 40L,
            )
        }
    }
}
