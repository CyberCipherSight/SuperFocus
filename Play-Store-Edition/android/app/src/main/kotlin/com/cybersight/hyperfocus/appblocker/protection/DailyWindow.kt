package com.cybersight.hyperfocus.appblocker.protection

import java.time.ZonedDateTime

/**
 * A daily window of local wall-clock minutes, [startMinute, endMinute), that
 * may cross midnight. Shared by the tethering cutoff (22:30–06:00) and Night
 * Guard (21:00–06:00): the two policies keep separate constants and separate
 * enforcement, and share only this arithmetic, which is the one part that
 * reliably goes wrong when written twice.
 */
data class DailyWindow(val startMinute: Int, val endMinute: Int) {

    init {
        require(startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY)
        require(startMinute != endMinute) { "an empty or all-day window is not a schedule" }
    }

    private val crossesMidnight get() = startMinute > endMinute

    /** Whether the wall-clock minute falls inside the window. */
    fun contains(minuteOfDay: Int): Boolean =
        if (crossesMidnight) minuteOfDay >= startMinute || minuteOfDay < endMinute
        else minuteOfDay in startMinute until endMinute

    fun contains(now: ZonedDateTime): Boolean = contains(now.hour * 60 + now.minute)

    /** The next instant at which [contains] flips, strictly after [now]. */
    fun nextBoundary(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atStartOfDay(now.zone)
        val start = today.plusMinutes(startMinute.toLong())
        val end = today.plusMinutes(endMinute.toLong())
        val candidates = listOf(start, end, start.plusDays(1), end.plusDays(1))
        return candidates.filter { it.isAfter(now) }.minOrNull()!!
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
