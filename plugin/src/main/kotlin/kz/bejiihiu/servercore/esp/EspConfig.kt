package kz.bejiihiu.servercore.esp

import java.util.logging.Logger
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.EntityType

data class EspConfig(
    val enabled: Boolean,
    /** managed radius in blocks; pairs beyond it fall back to vanilla visibility */
    val maxDistance: Double,
    /** engine cycle cadence, game ticks */
    val checkIntervalTicks: Long,
    /** sneaking targets are hidden from every viewer */
    val sneakHides: Boolean,
    val disguiseEnabled: Boolean,
    /** validated entity type names (bukkit enum style) for the disguise roll */
    val mobs: List<String>,
    /** pair verdict lifetime in game ticks */
    val cacheTtlTicks: Long,
) {
    fun rules(): EspRules = EspRules(
        maxDistanceSquared = maxDistance * maxDistance,
        sneakHides = sneakHides,
        disguiseEnabled = disguiseEnabled,
        mobs = mobs,
    )

    companion object {
        val defaultMobs = listOf("ZOMBIE", "SKELETON", "SPIDER", "COW", "PIG", "SHEEP")

        fun load(file: FileConfiguration, logger: Logger): EspConfig {
            val esp = file.getConfigurationSection("anti-esp")
            fun bool(name: String, default: Boolean) = esp?.getBoolean(name, default) ?: default

            val rawMobs = esp?.getString("disguise.mobs", "") ?: ""
            val parsed = rawMobs.split(',')
                .map { it.trim().uppercase().replace(' ', '_').replace('-', '_') }
                .filter { it.isNotEmpty() }
                .filter { name ->
                    val known = runCatching { EntityType.valueOf(name) }.isSuccess
                    if (!known) logger.warning("anti-esp.disguise.mobs: unknown entity type '$name', dropped")
                    known
                }

            return EspConfig(
                enabled = bool("enabled", true),
                maxDistance = esp?.getDouble("max-distance", 48.0)?.coerceIn(8.0, 128.0) ?: 48.0,
                checkIntervalTicks = esp?.getLong("check-interval-ticks", 10L)?.coerceIn(1L, 200L) ?: 10L,
                sneakHides = bool("sneak-hides", true),
                disguiseEnabled = bool("disguise.enabled", true),
                mobs = parsed.ifEmpty { defaultMobs },
                cacheTtlTicks = esp?.getLong("cache.ttl-ticks", 40L)?.coerceIn(1L, 2000L) ?: 40L,
            )
        }
    }
}
