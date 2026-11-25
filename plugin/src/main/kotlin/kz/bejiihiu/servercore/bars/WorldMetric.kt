package kz.bejiihiu.servercore.bars

import kz.bejiihiu.servercore.locale.LocaleService
import org.bukkit.entity.Player

/**
 * the player's world, its clock and its weather.
 *
 * every read happens on the player's entity scheduler (the bar task already
 * runs there), which is a region thread. folia's world clocks and weather are
 * world state, not per-region state, but reading them from the tick thread
 * that owns the player is the safe side of the line — nothing here touches a
 * world from the ws thread or a command thread.
 *
 * [showWeather] picks the template: weather and all, or just world and clock.
 * the weather word itself lives in the locale file, so a pushed locale
 * translates it too.
 */
class WorldMetric(
    private val locale: LocaleService,
    private val showWeather: Boolean,
) : BarMetric {

    override val bar: BarId = BarId.WORLD

    override fun read(player: Player): BarReading {
        val world = player.world
        return readingOf(
            worldName = world.name,
            time = world.time,
            storm = world.hasStorm(),
            thundering = world.isThundering(),
        )
    }

    /** pure half: clock, cycle and weather word without a live world */
    internal fun readingOf(
        worldName: String,
        time: Long,
        storm: Boolean,
        thundering: Boolean,
    ): BarReading {
        val cycle = Math.floorMod(time, DAY_TICKS)
        val weatherKey = when {
            thundering -> "bars.world.weather-thunder"
            storm -> "bars.world.weather-rain"
            else -> "bars.world.weather-clear"
        }
        return BarReading(
            // fill follows the day: sunrise empty, sunset full
            fraction = cycle.toDouble() / DAY_TICKS,
            // no health meaning in a clock; keep the mark neutral
            health = BarHealth.UNKNOWN,
            values = mapOf(
                "world" to worldName,
                "time" to BarFormats.clock(time),
                "cycle" to BarFormats.dayCyclePercent(time),
                "weather" to locale.text(weatherKey),
            ),
            titleKey = if (showWeather) null else "bars.world.title-plain",
        )
    }

    private companion object {
        const val DAY_TICKS = 24_000L
    }
}
