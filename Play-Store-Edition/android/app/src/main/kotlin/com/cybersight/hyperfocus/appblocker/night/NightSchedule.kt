package com.cybersight.hyperfocus.appblocker.night

import com.cybersight.hyperfocus.appblocker.protection.DailyWindow
import com.cybersight.hyperfocus.appblocker.protection.ProtectionPolicy
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The one authoritative answer to "is it night?" (ARCHITECTURE.md §24.6).
 *
 *     night = localTime >= 21:00 OR localTime < 08:00
 *
 * Pure, and the only place the rule is written. Everything that enforces Night
 * Guard asks here; nothing re-derives the comparison inline. Follows the
 * device's current local zone deliberately: a trip across time zones moves
 * the window with the traveller, and TIMEZONE_CHANGED triggers a reconcile.
 */
object NightSchedule {

    val window = DailyWindow(
        ProtectionPolicy.NIGHT_START_HOUR * 60,
        ProtectionPolicy.NIGHT_END_HOUR * 60,
    )

    fun isNight(now: ZonedDateTime): Boolean = window.contains(now)

    fun isNightNow(): Boolean = isNight(ZonedDateTime.now(ZoneId.systemDefault()))

    val socialWindow = DailyWindow(22 * 60 + 30, ProtectionPolicy.NIGHT_END_HOUR * 60)

    fun nextBoundary(now: ZonedDateTime): ZonedDateTime =
        minOf(window.nextBoundary(now), socialWindow.nextBoundary(now))
}
