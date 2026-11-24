package kz.bejiihiu.servercore.ops

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestartPlannerTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(moscowDateTime: String): ZonedDateTime =
        LocalDateTime.parse(moscowDateTime).atZone(moscow)

    @Test
    fun nextRestartIsLaterToday() {
        val next = RestartPlanner.nextAfter(listOf(RestartTime(5, 0)), at("2026-11-24T02:00"), moscow)
        assertEquals(at("2026-11-24T05:00"), next)
    }

    @Test
    fun nextRestartFallsToTomorrowWhenTodayIsGone() {
        val next = RestartPlanner.nextAfter(listOf(RestartTime(5, 0)), at("2026-11-24T05:00"), moscow)
        // exactly at the restart instant is not "after" - the same-day slot is spent
        assertEquals(at("2026-11-25T05:00"), next)
    }

    @Test
    fun picksTheClosestOfSeveralTimes() {
        val times = listOf(RestartTime(5, 0), RestartTime(17, 30))
        assertEquals(at("2026-11-24T17:30"), RestartPlanner.nextAfter(times, at("2026-11-24T10:00"), moscow))
        assertEquals(at("2026-11-25T05:00"), RestartPlanner.nextAfter(times, at("2026-11-24T18:00"), moscow))
    }

    @Test
    fun emptyScheduleHasNoNext() {
        assertNull(RestartPlanner.nextAfter(emptyList(), at("2026-11-24T10:00"), moscow))
    }

    @Test
    fun wallClockTimeIsStableAcrossADstTransition() {
        // berlin moves cet -> cest on 2026-03-29 at 02:00 local (spring forward)
        val before = ZonedDateTime.of(2026, 3, 28, 10, 0, 0, 0, berlin)
        val next = RestartPlanner.nextAfter(listOf(RestartTime(4, 0)), before, berlin)

        // next day 04:00 wall clock; utc offset changed from +01:00 to +02:00
        assertEquals(LocalDateTime.parse("2026-03-29T04:00"), next!!.toLocalDateTime())
        assertEquals("+02:00", next.offset.id)

        // autumn back: 2026-10-25, 03:00 -> 02:00; wall clock 04:00 still means 04:00
        val beforeAutumn = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, berlin)
        val autumnNext = RestartPlanner.nextAfter(listOf(RestartTime(4, 0)), beforeAutumn, berlin)
        assertEquals(LocalDateTime.parse("2026-10-25T04:00"), autumnNext!!.toLocalDateTime())
        assertEquals("+01:00", autumnNext.offset.id)
    }

    @Test
    fun nonexistentWallClockInSpringGapIsShiftedByZoneRules() {
        // berlin 2026-03-29: 02:30 does not exist (clocks jump 02:00 -> 03:00).
        // a restart scheduled at 02:30 must not silently disappear or fire
        // twice - zone rules resolve the gap by shifting forward.
        val before = ZonedDateTime.of(2026, 3, 28, 23, 0, 0, 0, berlin)
        val next = RestartPlanner.nextAfter(listOf(RestartTime(2, 30)), before, berlin)

        assertEquals(LocalDateTime.parse("2026-03-29T03:30"), next!!.toLocalDateTime())
        assertTrue(next.toInstant().isAfter(before.toInstant()))
    }

    @Test
    fun moscowIsFixedOffsetAllYear() {
        val january = RestartPlanner.nextAfter(listOf(RestartTime(5, 0)), at("2026-01-01T01:00"), moscow)!!
        val july = RestartPlanner.nextAfter(listOf(RestartTime(5, 0)), at("2026-07-01T01:00"), moscow)!!
        assertEquals("+03:00", january.offset.id)
        assertEquals("+03:00", july.offset.id)
        // same wall clock in january and july = exactly the same utc hour
        assertEquals(january.toInstant().toEpochMilli() % 86_400_000, july.toInstant().toEpochMilli() % 86_400_000)
    }

    @Test
    fun isDueRespectsTheInstant() {
        val planned = at("2026-11-24T05:00")
        assertFalse(RestartPlanner.isDue(at("2026-11-24T04:59"), planned))
        assertTrue(RestartPlanner.isDue(at("2026-11-24T05:00"), planned))
        assertFalse(RestartPlanner.isDue(at("2026-11-24T05:00"), null))
    }

    @Test
    fun parseTimesAcceptsAndRejects() {
        assertEquals(RestartTime(5, 0), RestartTime.parse("05:00"))
        assertEquals(RestartTime(23, 59), RestartTime.parse("23:59"))
        assertEquals(RestartTime(0, 0), RestartTime.parse("0:00"))
        assertNull(RestartTime.parse("24:00"))
        assertNull(RestartTime.parse("05:60"))
        assertNull(RestartTime.parse("5"))
        assertNull(RestartTime.parse("abc"))
        assertNull(RestartTime.parse("05:00:00"))
    }
}
