package com.cybersight.hyperfocus.appblocker.session

import java.time.ZonedDateTime

/**
 * Pure scheduling arithmetic for routines (ARCHITECTURE.md §6).
 *
 * Deliberately free of Android dependencies so it can be unit-tested on the
 * JVM — week-wrap and DST are exactly the kind of bugs that only show up on the
 * one morning you needed the routine to fire.
 */
object RoutineSchedule {

    /** Bit 0 = Monday … bit 6 = Sunday, matching [java.time.DayOfWeek.getValue]. */
    fun bitFor(dayOfWeek: Int): Int = 1 shl (dayOfWeek - 1)

    fun runsOn(daysMask: Int, dayOfWeek: Int): Boolean =
        daysMask and bitFor(dayOfWeek) != 0

    /**
     * How late a routine alarm may arrive and still count as the one that was
     * scheduled.
     *
     * Aggressive OEM skins freeze the app and hold its alarms, then deliver them
     * whenever it next wakes — at boot, or the first time the user opens it,
     * hours after the routine was meant to run. Acting on one of those locks the
     * phone at a moment nobody asked for, which reads as the app doing it at
     * random. Past this window the alarm is stale: drop it and wait for the next
     * real occurrence.
     */
    const val GRACE_MINUTES = 15

    /** Whether [now] really is the occurrence this routine was scheduled for. */
    fun isDue(now: ZonedDateTime, daysMask: Int, startMinute: Int): Boolean {
        val start = activeOccurrence(now, daysMask, startMinute, GRACE_MINUTES)
        return start != null
    }

    /** Resolve wall time consistently for both alarms and reconciliation.
     * Yesterday is included for periods crossing midnight. DST overlaps use
     * the first offset once; gaps move the nonexistent time forward.
     */
    fun activeOccurrence(
        now: ZonedDateTime, daysMask: Int, startMinute: Int, durationMin: Int,
    ): ZonedDateTime? {
        if (startMinute !in 0..1439 || durationMin !in 1..180) return null
        for (offset in 0L..1L) {
            val date = now.toLocalDate().minusDays(offset)
            if (!runsOn(daysMask, date.dayOfWeek.value)) continue
            val start = date.atTime(startMinute / 60, startMinute % 60).atZone(now.zone)
            if (!now.isBefore(start) && now.isBefore(start.plusMinutes(durationMin.toLong()))) return start
        }
        return null
    }

    /**
     * The next moment this routine should fire, strictly after [now], or null if
     * it runs on no days at all.
     *
     * Scans eight days rather than seven so a routine whose only day is today —
     * but whose time has already passed — correctly lands on the same weekday
     * next week.
     *
     * DST is handled by resolving the requested local wall time: on a spring-forward morning a 02:30 routine lands at 03:30 local
     * rather than silently not firing.
     */
    fun nextOccurrence(now: ZonedDateTime, daysMask: Int, startMinute: Int): ZonedDateTime? {
        if (daysMask == 0 || startMinute !in 0..1439) return null
        for (offset in 0..7) {
            val candidate = now.toLocalDate()
                .plusDays(offset.toLong())
                .atTime(startMinute / 60, startMinute % 60)
                .atZone(now.zone)
            if (runsOn(daysMask, candidate.dayOfWeek.value) && candidate.isAfter(now)) {
                return candidate
            }
        }
        return null
    }
}
