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
}
