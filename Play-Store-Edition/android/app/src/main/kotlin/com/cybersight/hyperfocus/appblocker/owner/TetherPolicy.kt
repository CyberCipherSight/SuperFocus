package com.cybersight.hyperfocus.appblocker.owner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.cybersight.hyperfocus.appblocker.BuildConfig
import com.cybersight.hyperfocus.appblocker.protection.DailyWindow
import java.net.NetworkInterface
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The nightly tethering cutoff (§23.3).
 *
 * From [CUTOFF_MINUTE] to [RELEASE_MINUTE] every day, `DISALLOW_CONFIG_TETHERING`
 * is held. On every supported Android version that restriction both blocks the
 * tethering settings and shuts down tethering that is already running — USB
 * and hotspot alike. It touches nothing else: mobile data stays up, and the
 * apps on the allowlist keep their connectivity.
 *
 * Two mechanisms, deliberately redundant:
 *  - an exact alarm at each boundary, so the change lands at 22:30 sharp;
 *  - [reconcile], which derives the correct state from the wall clock and is
 *    called on boot, on app launch and on every alarm. A phone that was off at
 *    22:30 still ends up restricted the moment it comes back.
 *
 * The window math is pure and unit-tested; it crosses midnight, which is the
 * one place this kind of code reliably goes wrong.
 */
object TetherPolicy {

    /** 22:30 local. */
    const val CUTOFF_MINUTE = 22 * 60 + 30

    /** 06:00 local. Tethering is permitted again from here until the cutoff. */
    const val RELEASE_MINUTE = 6 * 60

    private const val REQUEST_CODE = 4

    // --- pure --------------------------------------------------------------

    // Distinct from Night Guard's 21:00 window on purpose (§24.6): only the
    // arithmetic is shared, never the constants.
    private val window = DailyWindow(CUTOFF_MINUTE, RELEASE_MINUTE)

    /** The window wraps midnight: restricted from 22:30 through 05:59. */
    fun restrictedAt(now: ZonedDateTime): Boolean = window.contains(now)

    /** The next instant at which the restriction flips, strictly after [now]. */
    fun nextBoundary(now: ZonedDateTime): ZonedDateTime = window.nextBoundary(now)

    // --- policy ------------------------------------------------------------

    /**
     * Applies whatever the clock says should be in force. Idempotent, and a
     * no-op without device owner — the restriction is a DPM primitive, and on
     * the other tiers there is nothing honest to offer in its place.
     */
    fun reconcile(context: Context) {
        if (!OwnerSetup.isOwner(context)) return
        val dpm = OwnerSetup.dpm(context)
        val admin = OwnerSetup.admin(context)
        val restricted = ConnectivityPolicy.tetherRestricted(
            restrictedAt(ZonedDateTime.now(ZoneId.systemDefault())), BuildConfig.TESTING_CONNECTIVITY)
        runCatching {
            if (restricted) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
            }
        }
    }

    fun isRestricted(context: Context): Boolean =
        runCatching {
            (context.getSystemService(Context.USER_SERVICE) as UserManager)
                .hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)
        }.getOrDefault(false)

    /**
     * Sets the alarm for the next boundary. Cancels any previous one first.
     *
     * `setAlarmClock`, not `setExactAndAllowWhileIdle`. The first night in
     * production, the 22:30 cutoff fired and the 06:00 release did not — MIUI
     * drops allow-while-idle alarms in deep doze, and the user woke to a phone
     * that could not tether and had no way to say why. An alarm-clock alarm is
     * the one kind OEMs treat as user-visible and honour; routines already use
     * it and their 22:05 firing that same night proved it. The cost is an alarm
     * icon in the status bar, which is honest: FocusBro does have an alarm set.
     */
    fun schedule(context: Context) {
        if (!OwnerSetup.isOwner(context)) return
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context)
        alarms.cancel(pending)
        // Also retires an already scheduled cutoff when updating an existing
        // owner install to the connectivity-testing build.
        if (BuildConfig.TESTING_CONNECTIVITY) return
        val at = nextBoundary(ZonedDateTime.now(ZoneId.systemDefault()))
            .toInstant().toEpochMilli()
        val show = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, com.cybersight.hyperfocus.appblocker.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending)
        }.onFailure {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /** reconcile + schedule, which is all any caller ever needs. */
    fun sync(context: Context) {
        reconcile(context)
        schedule(context)
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, TetherReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // --- observation -------------------------------------------------------

    /**
     * Whether a USB tethering interface is up. Read from the kernel's interface
     * list rather than TetheringManager, whose state APIs are system-only.
     * A heuristic, and labelled as one in the UI.
     */
    fun usbTetheringActive(): Boolean = interfaceUp("rndis0", "usb0", "ncm0")

    fun hotspotActive(): Boolean = interfaceUp("ap0", "swlan0", "wlan1", "softap0")

    private fun interfaceUp(vararg names: String): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.any { it.name in names && it.isUp } ?: false
    }.getOrDefault(false)

    fun status(context: Context): Map<String, Any> {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        return mapOf(
            "enforceable" to OwnerSetup.isOwner(context),
            "restricted" to isRestricted(context),
            "usbActive" to usbTetheringActive(),
            "hotspotActive" to hotspotActive(),
            "testingConnectivity" to BuildConfig.TESTING_CONNECTIVITY,
            "nextChangeUtc" to if (BuildConfig.TESTING_CONNECTIVITY) 0L else nextBoundary(now).toInstant().toEpochMilli(),
            "cutoffMinute" to CUTOFF_MINUTE,
            "releaseMinute" to RELEASE_MINUTE,
        )
    }
}

/**
 * Fires at each boundary, and on any clock or timezone change — a moved clock
 * is exactly when the window has to be re-evaluated, and those broadcasts are
 * exempt from the manifest-receiver ban.
 */
class TetherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = TetherPolicy.sync(context)
}
