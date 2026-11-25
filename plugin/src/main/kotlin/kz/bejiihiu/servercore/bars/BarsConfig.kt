package kz.bejiihiu.servercore.bars

import java.util.logging.Logger
import org.bukkit.configuration.file.FileConfiguration

/** one bar's on/off switch plus its coloring thresholds */
data class RamBarConfig(
    val enabled: Boolean,
    /** heap fill percent at or below which the bar is healthy */
    val thresholds: HealthThresholds,
)

data class TpsBarConfig(
    val enabled: Boolean,
    /** region tps at or above which the bar is healthy */
    val thresholds: HealthThresholds,
)

/**
 * the bars section. defaults are deliberately the shipped values: an admin
 * deleting a key gets the same behaviour the commented file describes.
 */
data class BarsConfig(
    val enabled: Boolean,
    val updateIntervalTicks: Long,
    val surface: BarSurface,
    val ram: RamBarConfig,
    val tps: TpsBarConfig,
) {
    /** true only when the master switch and the bar's own switch are both on */
    fun isEnabled(bar: BarId): Boolean = enabled && when (bar) {
        BarId.RAM -> ram.enabled
        BarId.TPS -> tps.enabled
    }

    fun enabledBars(): List<BarId> = BarId.entries.filter(::isEnabled)

    companion object {
        const val DEFAULT_INTERVAL_TICKS = 20L
        const val DEFAULT_SURFACE = "bossbar"
        const val DEFAULT_RAM_GOOD = 60.0
        const val DEFAULT_RAM_MEDIUM = 80.0
        const val DEFAULT_TPS_GOOD = 18.0
        const val DEFAULT_TPS_MEDIUM = 15.0

        fun load(file: FileConfiguration, logger: Logger): BarsConfig {
            val section = file.getConfigurationSection("bars")
            fun bool(path: String, default: Boolean) = section?.getBoolean(path, default) ?: default
            fun raw(path: String, default: Double) = section?.getDouble(path, default) ?: default

            val rawSurface = section?.getString("display", DEFAULT_SURFACE) ?: DEFAULT_SURFACE
            val surface = BarSurface.parse(rawSurface) ?: BarSurface.BOSSBAR.also {
                logger.warning("bars.display: unknown surface '$rawSurface', using bossbar")
            }

            val interval = (section?.getLong("update-interval-ticks", DEFAULT_INTERVAL_TICKS) ?: DEFAULT_INTERVAL_TICKS)
                .coerceIn(1L, 200L)

            // bad thresholds must not cost a boot: clamp them, and put a swapped
            // pair back in order so the middle band always exists
            val ramGood = raw("ram.good", DEFAULT_RAM_GOOD).coerceIn(1.0, 99.0)
            val ramMedium = raw("ram.medium", DEFAULT_RAM_MEDIUM).coerceIn(1.0, 99.0)
            val ramOrdered = if (ramGood <= ramMedium) ramGood to ramMedium else ramMedium to ramGood
            if (ramGood > ramMedium) logger.warning("bars.ram: good is above medium, swapping the thresholds")

            val tpsGood = raw("tps.good", DEFAULT_TPS_GOOD).coerceIn(0.0, 20.0)
            val tpsMedium = raw("tps.medium", DEFAULT_TPS_MEDIUM).coerceIn(0.0, 20.0)
            val tpsOrdered = if (tpsGood >= tpsMedium) tpsGood to tpsMedium else tpsMedium to tpsGood
            if (tpsGood < tpsMedium) logger.warning("bars.tps: good is below medium, swapping the thresholds")

            return BarsConfig(
                enabled = bool("enabled", true),
                updateIntervalTicks = interval,
                surface = surface,
                ram = RamBarConfig(
                    enabled = bool("ram.enabled", true),
                    thresholds = HealthThresholds(ramOrdered.first, ramOrdered.second, lowerIsBetter = true),
                ),
                tps = TpsBarConfig(
                    enabled = bool("tps.enabled", true),
                    thresholds = HealthThresholds(tpsOrdered.first, tpsOrdered.second, lowerIsBetter = false),
                ),
            )
        }
    }
}
