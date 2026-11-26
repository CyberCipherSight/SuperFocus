package com.cybersight.hyperfocus.appblocker.applimits

import java.time.ZonedDateTime

/** A usage day is 07:00 local through the next 07:00 (including DST days). */
object AppLimitMath {
    fun dayStart(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(7, 0).atZone(now.zone)
        return if (now.isBefore(today)) today.minusDays(1) else today
    }
    fun nextReset(now: ZonedDateTime): Long = dayStart(now).plusDays(1).toInstant().toEpochMilli()
    fun blocked(now: Long, timedUntil: Long, exhaustedUntil: Long) =
        timedUntil > now || exhaustedUntil > now
    fun usageForDay(storedDay: Long, day: Long, stored: Long, measured: Long): Long =
        maxOf(if (storedDay == day) stored else 0L, measured)

    fun effectiveLimit(current: Int, requested: Int, exhausted: Boolean): Int {
        require(requested in 0..1440)
        require(!exhausted || (requested in 1..current)) { "This limit is locked until 7 AM" }
        return requested
    }
}

/** Pure event reduction, not interval-bucket UsageStats totals. Counts only
 * resumed activities while screen is interactive and unlocked, once per app.
 * Events before the window establish the state at 07:00. */
class UsageCounter(private val start: Long, private val end: Long) {
    enum class Kind { RESUME, PAUSE, SCREEN_ON, SCREEN_OFF, UNLOCK, LOCK, REBOOT }
    data class Event(val at: Long, val kind: Kind, val pkg: String = "", val activity: String = "")
    private val active = mutableMapOf<String, MutableSet<String>>()
    private var interactive = true
    private var unlocked = true
    private var cursor = start
    private val totals = mutableMapOf<String, Long>()
    fun accept(event: Event) {
        accrue(event.at.coerceIn(start, end))
        when (event.kind) {
            Kind.RESUME -> active.getOrPut(event.pkg) { mutableSetOf() }.add(event.activity)
            Kind.PAUSE -> active[event.pkg]?.remove(event.activity)
            Kind.SCREEN_ON -> interactive = true
            Kind.SCREEN_OFF -> interactive = false
            Kind.UNLOCK -> unlocked = true
            Kind.LOCK -> unlocked = false
            Kind.REBOOT -> active.clear()
        }
    }
    private fun accrue(to: Long) {
        if (to <= cursor) return
        if (interactive && unlocked) active.filterValues { it.isNotEmpty() }.keys.forEach {
            totals[it] = (totals[it] ?: 0) + to - cursor
        }
        cursor = to
    }
    fun finish(): Map<String, Long> { accrue(end); return totals.toMap() }
    fun foreground(): Set<String> =
        if (interactive && unlocked) active.filterValues { it.isNotEmpty() }.keys else emptySet()
}
