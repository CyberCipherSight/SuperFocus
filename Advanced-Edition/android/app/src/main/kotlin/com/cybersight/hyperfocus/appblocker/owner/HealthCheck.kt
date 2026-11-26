package com.cybersight.hyperfocus.appblocker.owner

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.cybersight.hyperfocus.appblocker.stats.UsageBridge
import kotlin.math.abs

/**
 * Permission status, the "fix this" intents, and a background-survival probe
 * (ARCHITECTURE.md §15 phase 5, edge cases 18-23, 31-33).
 *
 * Framing that matters: none of this decides whether the lock *holds* — the OS
 * holds it. These affect whether the countdown stays accurate and whether
 * routines fire on time. The UI says so, rather than implying the lock is at
 * risk.
 */
object HealthCheck {

    private const val PREFS = "focusbro.health"
    private const val KEY_DUE = "probeDueAt"
    private const val KEY_FIRED = "probeFiredAt"

    /** A probe is "late" past this, which in practice means an OEM killed us. */
    private const val LATE_TOLERANCE_MS = 60_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(context: Context): Map<String, Any> = mapOf(
        "deviceOwner" to OwnerSetup.isOwner(context),
        "notifications" to notificationsEnabled(context),
        "exactAlarms" to exactAlarmsAllowed(context),
        "batteryExempt" to batteryExempt(context),
        "usageStats" to usageStatsGranted(context),
        // Only meaningful without device owner: the pinning fallback needs
        // both to start a routine session by itself.
        "overlay" to Settings.canDrawOverlays(context),
        "screenPinning" to LockController.pinningEnabled(context),
        "accessibility" to LockController.accessibilityRunning(),
        // Edge case 32: some OEM skins silently ignore setStatusBarDisabled.
        "statusBarControl" to LockController.statusBarControlWorks,
        "root" to com.cybersight.hyperfocus.appblocker.root.RootEnforcer.available(),
    )

    private fun notificationsEnabled(context: Context) =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun exactAlarmsAllowed(context: Context): Boolean {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    }

    private fun batteryExempt(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    private fun usageStatsGranted(context: Context) = UsageBridge.granted(context)

    /** Opens the right Settings screen for [id], or app details as a fallback. */
    fun fix(context: Context, id: String): Boolean {
        if (!com.cybersight.hyperfocus.appblocker.PlayPrivacy.permitted(context, id)) return false
        val intent = when (id) {
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

            "exactAlarms" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri(context))
                } else null

            "batteryExempt" ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri(context))

            "usageStats" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri(context))

            // No direct intent exists for the pinning toggle itself.
            "screenPinning" -> Intent(Settings.ACTION_SECURITY_SETTINGS)

            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            else -> null
        } ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri(context))

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun uri(context: Context) =
        android.net.Uri.parse("package:${context.packageName}")

    // --- Background survival probe -------------------------------------------
    //
    // Schedules one exact alarm N minutes out. The user backgrounds the phone
    // and comes back; if the alarm fired late or never, the OEM's battery
    // manager is killing us and routines will be unreliable. This reuses
    // AlarmManager rather than standing up a watchdog service — the thing we
    // want to measure is exactly what AlarmManager already tells us.

    fun startProbe(context: Context, minutes: Int): Long {
        val dueAt = System.currentTimeMillis() + minutes * 60_000L
        prefs(context).edit().putLong(KEY_DUE, dueAt).remove(KEY_FIRED).apply()
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, probeIntent(context))
        return dueAt
    }

    fun probeResult(context: Context): Map<String, Any> {
        val p = prefs(context)
        val due = p.getLong(KEY_DUE, 0L)
        val fired = p.getLong(KEY_FIRED, 0L)
        val now = System.currentTimeMillis()

        val state = when {
            due == 0L -> "none"
            fired == 0L && now < due -> "pending"
            fired == 0L -> "missed"
            abs(fired - due) <= LATE_TOLERANCE_MS -> "onTime"
            else -> "late"
        }
        return mapOf(
            "state" to state,
            "dueAt" to due,
            "firedAt" to fired,
            "latenessMs" to if (fired > 0L) fired - due else 0L,
        )
    }

    fun recordProbeFired(context: Context) {
        prefs(context).edit().putLong(KEY_FIRED, System.currentTimeMillis()).apply()
    }

    private fun probeIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, ProbeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        HealthCheck.recordProbeFired(context)
}
