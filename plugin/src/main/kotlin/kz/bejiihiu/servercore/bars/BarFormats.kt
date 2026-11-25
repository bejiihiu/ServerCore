package kz.bejiihiu.servercore.bars

import java.util.Locale

/**
 * number formatting for bar values. locale root on purpose: the jvm default
 * would hand a russian client "18,4" while the template says "18.4", and unit
 * words live in the locale file, not here.
 */
object BarFormats {

    /** marker for a value the metric could not read; a dash reads the same everywhere */
    const val UNKNOWN = "—"

    private const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0

    fun gigabytes(bytes: Long): String =
        String.format(Locale.ROOT, "%.1f", bytes.coerceAtLeast(0) / BYTES_PER_GIB)

    fun percent(fraction: Double): String =
        String.format(Locale.ROOT, "%.0f", fraction.coerceIn(0.0, 1.0) * 100)

    fun tps(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    /** milliseconds with one decimal, for mspt and its p95 */
    fun millis(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private const val TICKS_PER_DAY = 24_000L
    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * minecraft day time to a 24h clock. tick 0 is 06:00 in game, and a full
     * day is 24000 ticks; a negative or rolled-over time wraps instead of
     * printing nonsense.
     */
    fun clock(ticks: Long): String {
        val dayTicks = Math.floorMod(ticks, TICKS_PER_DAY)
        val minutes = ((dayTicks * MINUTES_PER_DAY / TICKS_PER_DAY) + 6 * 60) % MINUTES_PER_DAY
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    }

    /** how far the world is through its day, 0 at sunrise-ish ticks */
    fun dayCyclePercent(ticks: Long): String =
        percent(Math.floorMod(ticks, TICKS_PER_DAY).toDouble() / TICKS_PER_DAY)
}
