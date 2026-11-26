package com.cybersight.hyperfocus.appblocker.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Week-wrap and DST are the two ways routine scheduling silently fails on the
 * one morning it mattered. Both are covered here.
 */
class RoutineScheduleTest {

    private val london = ZoneId.of("Europe/London")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId = london) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)

    private val mondays = RoutineSchedule.bitFor(1)
    private val weekdays = (1..5).fold(0) { acc, d -> acc or RoutineSchedule.bitFor(d) }

    @Test
    fun `fires later the same day when the time has not passed`() {
        // Monday 2026-01-05, routine at 06:00.
        val next = RoutineSchedule.nextOccurrence(at(2026, 1, 5, 5, 0), mondays, 6 * 60)
        assertEquals(at(2026, 1, 5, 6, 0), next)
    }

    @Test
    fun `wraps to next week when today's slot has already passed`() {
        val next = RoutineSchedule.nextOccurrence(at(2026, 1, 5, 7, 0), mondays, 6 * 60)
        assertEquals(at(2026, 1, 12, 6, 0), next)
    }

    @Test
    fun `picks the nearest enabled weekday`() {
        // Saturday 2026-01-03 -> next weekday run is Monday.
        val next = RoutineSchedule.nextOccurrence(at(2026, 1, 3, 9, 0), weekdays, 8 * 60)
        assertEquals(at(2026, 1, 5, 8, 0), next)
    }

    @Test
    fun `survives the spring-forward gap instead of skipping`() {
        // UK clocks jump 01:00 -> 02:00 on 2026-03-29. A 01:30 routine has no
        // literal local time that day; it must still produce a real instant.
        val next = RoutineSchedule.nextOccurrence(
            at(2026, 3, 28, 12, 0),
            RoutineSchedule.bitFor(7), // Sunday
            90,
        )
        requireNotNull(next)
        assertEquals(2026, next.year)
        assertEquals(3, next.monthValue)
        assertEquals(29, next.dayOfMonth)
    }

    @Test
    fun `a routine with no days never fires`() {
        assertNull(RoutineSchedule.nextOccurrence(at(2026, 1, 5, 5, 0), 0, 360))
    }

    // isDue: the guard against an OEM holding an alarm and delivering it hours
    // late, which locked the phone at a moment the user never scheduled.

    @Test
    fun `due when the alarm arrives on time`() {
        assertTrue(RoutineSchedule.isDue(at(2026, 1, 5, 6, 0), mondays, 6 * 60))
    }

    @Test
    fun `due when a little late but inside the grace window`() {
        assertTrue(RoutineSchedule.isDue(at(2026, 1, 5, 6, 14), mondays, 6 * 60))
    }

    @Test
    fun `not due when released hours after the scheduled time`() {
        // The reported symptom: a 06:00 routine surfacing at 09:30 and locking.
        assertFalse(RoutineSchedule.isDue(at(2026, 1, 5, 9, 30), mondays, 6 * 60))
    }

    @Test
    fun `not due on a day the routine does not run`() {
        // Saturday, weekday-only routine, right on the minute.
        assertFalse(RoutineSchedule.isDue(at(2026, 1, 3, 8, 0), weekdays, 8 * 60))
    }

    @Test
    fun `not due when the alarm arrives meaningfully early`() {
        assertFalse(RoutineSchedule.isDue(at(2026, 1, 5, 5, 45), mondays, 6 * 60))
    }

    @Test
    fun `spring forward does not shift afternoon wall time`() {
        assertEquals(at(2026, 3, 29, 15, 0), RoutineSchedule.nextOccurrence(
            at(2026, 3, 29, 0, 0), 127, 15 * 60))
    }

    @Test
    fun `fall back does not shift afternoon wall time`() {
        assertEquals(at(2026, 10, 25, 15, 0), RoutineSchedule.nextOccurrence(
            at(2026, 10, 25, 0, 0), 127, 15 * 60))
    }

    @Test
    fun `reconcile finds previous days period across midnight`() {
        assertEquals(at(2026, 1, 5, 23, 30), RoutineSchedule.activeOccurrence(
            at(2026, 1, 6, 0, 20), mondays, 23 * 60 + 30, 60))
        assertNull(RoutineSchedule.activeOccurrence(at(2026, 1, 6, 0, 30), mondays, 1410, 60))
    }

    @Test
    fun `late wake resumes only remaining scheduled period`() {
        val start = RoutineSchedule.activeOccurrence(at(2026, 1, 5, 6, 45), mondays, 360, 60)!!
        assertEquals(at(2026, 1, 5, 7, 0), start.plusMinutes(60))
        assertNull(RoutineSchedule.activeOccurrence(at(2026, 1, 5, 7, 0), mondays, 360, 60))
        assertNull(RoutineSchedule.activeOccurrence(at(2026, 1, 5, 5, 59), mondays, 360, 60))
    }

    @Test
    fun `DST gap alarm and reconcile identify same occurrence`() {
        val start = RoutineSchedule.nextOccurrence(at(2026, 3, 29, 0, 0), 127, 90)!!
        assertEquals(start, RoutineSchedule.activeOccurrence(start.plusMinutes(5), 127, 90, 30))
    }

    @Test
    fun `DST repeated hour never creates a second occurrence`() {
        val first = at(2026, 10, 25, 1, 30).withEarlierOffsetAtOverlap()
        val second = first.withLaterOffsetAtOverlap()
        assertEquals(first, RoutineSchedule.activeOccurrence(second, 127, 90, 120))
        assertNull(RoutineSchedule.activeOccurrence(second, 127, 90, 30))
    }

    @Test
    fun `invalid schedule cannot reach wall time construction`() {
        assertNull(RoutineSchedule.nextOccurrence(at(2026, 1, 5, 6, 0), 127, 1440))
        assertNull(RoutineSchedule.activeOccurrence(at(2026, 1, 5, 6, 0), 127, -1, 30))
    }
}
