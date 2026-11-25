package kz.bejiihiu.servercore.bars

import java.util.UUID
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** how healthy the sampled value is. the renderer maps it to colors. */
enum class BarHealth {
    GOOD,
    MEDIUM,
    LOW,
    UNKNOWN,
}

/**
 * one sampled bar value plus everything its template needs.
 *
 * [fraction] drives the bossbar fill (0..1), null when the metric has no
 * usable reading and the bar should render empty. [values] are plain strings
 * substituted into the locale template, so the template owns the copy and the
 * unit while the metric owns the number.
 *
 * [titleKey] lets a metric pick between two templates of the same bar (the
 * world bar has a weather and a plain flavour); null means the bar's own title.
 */
data class BarReading(
    val fraction: Double?,
    val health: BarHealth,
    val values: Map<String, String>,
    val titleKey: String? = null,
)

/**
 * thresholds turning a raw number into a [BarHealth].
 *
 * [lowerIsBetter] flips the comparison: heap fill is healthy while small,
 * tps is healthy while high.
 */
data class HealthThresholds(
    val good: Double,
    val medium: Double,
    val lowerIsBetter: Boolean,
) {
    fun health(value: Double): BarHealth {
        if (!value.isFinite()) return BarHealth.UNKNOWN
        return if (lowerIsBetter) {
            when {
                value <= good -> BarHealth.GOOD
                value <= medium -> BarHealth.MEDIUM
                else -> BarHealth.LOW
            }
        } else {
            when {
                value >= good -> BarHealth.GOOD
                value >= medium -> BarHealth.MEDIUM
                else -> BarHealth.LOW
            }
        }
    }
}

/**
 * one source of a bar value.
 *
 * the tps metric reads the player's location, so [read] has to run on that
 * player's entity scheduler; the ram metric only reads a volatile snapshot and
 * is safe from any thread. bars refresh through the player's scheduler either
 * way, so the contract is one and the same for both.
 *
 * [start] exists for metrics that need background sampling (jmx lives on the
 * async scheduler); pull-only metrics keep the default no-op.
 */
interface BarMetric {
    val bar: BarId

    fun read(player: Player): BarReading

    fun start(plugin: Plugin, periodTicks: Long) {}

    fun stop() {}

    /**
     * drops per-player state a metric keeps. called when a player quits, on the
     * quit event's thread; metrics with a sampler cache (mspt) override it so a
     * uuid never outlives the session.
     */
    fun forget(uuid: UUID) {}
}
