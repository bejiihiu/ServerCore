package kz.bejiihiu.servercore.ops

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** one daily restart time, wall clock in the configured zone (hh:mm) */
data class RestartTime(val hour: Int, val minute: Int) {
    companion object {
        private val PATTERN = Regex("""^(\d{1,2}):(\d{2})$""")

        /** parses "05:00"; returns null for malformed entries (caller logs and skips) */
        fun parse(raw: String): RestartTime? {
            val match = PATTERN.find(raw.trim()) ?: return null
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            if (hour !in 0..23 || minute !in 0..59) return null
            return RestartTime(hour, minute)
        }
    }
}

/**
 * computes the next scheduled restart instant. pure logic, timezoned via
 * java.time zone rules so daylight-saving weirdness (any zone, not only the
 * fixed-offset moscow one) is handled by the platform: a wall-clock time is
 * resolved against the zone on the concrete date, and a nonexistent time
 * inside a spring-forward gap is shifted forward by the zone rules.
 */
object RestartPlanner {

    fun nextAfter(times: List<RestartTime>, after: ZonedDateTime, zone: ZoneId): ZonedDateTime? {
        if (times.isEmpty()) return null

        return times
            .map { time -> candidateFor(time, after, zone) }
            .min()
    }

    /** earliest occurrence of [time] strictly after [after] */
    private fun candidateFor(time: RestartTime, after: ZonedDateTime, zone: ZoneId): ZonedDateTime {
        val local = LocalDateTime.of(after.year, after.month, after.dayOfMonth, time.hour, time.minute)
        val today = local.atZone(zone)
        return if (today.isAfter(after)) today else local.plusDays(1).atZone(zone)
    }

    /** true when the planned instant has been reached (or passed) */
    fun isDue(now: ZonedDateTime, next: ZonedDateTime?): Boolean =
        next != null && !now.isBefore(next)

    /** human readable countdown for logs */
    fun until(now: ZonedDateTime, next: ZonedDateTime?): Duration? = next?.let { Duration.between(now, it) }
}
