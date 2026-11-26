package com.cybersight.hyperfocus.appblocker

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.cybersight.hyperfocus.appblocker.applimits.AppLimits
import com.cybersight.hyperfocus.appblocker.enforce.BlockerAccessibilityService
import com.cybersight.hyperfocus.appblocker.enforce.BlockOverlay
import com.cybersight.hyperfocus.appblocker.enforce.PlayNudge
import com.cybersight.hyperfocus.appblocker.stats.UsageBridge

/** Preference and effective capability are separate. User can always choose basic. */
object PlayMode {
    private fun prefs(c: Context) = c.getSharedPreferences("focusbro.playMode", Context.MODE_PRIVATE)
    fun selected(c: Context) = prefs(c).getString("mode", "basic") ?: "basic"
    fun interval(c: Context) = prefs(c).getInt("interval", 5).coerceIn(1, 15)
    fun setInterval(c: Context, minutes: Int) { prefs(c).edit().putInt("interval", minutes).apply() }
    fun overlay(c: Context) = PlayPrivacy.permitted(c, "overlay") && runCatching { Settings.canDrawOverlays(c) }.getOrDefault(false)
    fun notifications(c: Context) = PlayPermissions.notificationGranted(c) && PlayPermissions.reminderChannelEnabled(c)
    fun effective(c: Context): String = PlayModePolicy.effective(
        selected(c),
        PlayPermissions.accessibilityReady(c),
        UsageBridge.granted(c),
        notifications(c) || overlay(c)
    )
    fun select(c: Context, mode: String) {
        check(prefs(c).edit().putString("mode", mode).commit())
        BlockOverlay.hide(c)
        PlayNudge.reset(c)
    }
    fun status(c: Context): Map<String, Any> = mapOf(
        "configured" to prefs(c).contains("mode"), "selected" to selected(c),
        "effective" to effective(c), "interval" to interval(c),
        "focusCount" to AppLimits.focusSelectionCount(c),
        "usageStats" to UsageBridge.granted(c), "overlay" to overlay(c),
        "notifications" to notifications(c),
        "exactAlarms" to PlayPermissions.exact(c),
        "sdk" to android.os.Build.VERSION.SDK_INT,
        "manufacturer" to android.os.Build.MANUFACTURER,
        "permissionStates" to PlayPermissions.snapshot(c),
        "batteryExempt" to PlayPermissions.battery(c),
        "backgroundRestricted" to PlayPermissions.backgroundRestricted(c),
        "sessionRunning" to com.cybersight.hyperfocus.appblocker.session.SessionService.running,
        "limitsRunning" to com.cybersight.hyperfocus.appblocker.applimits.AppLimitService.running,
        "monitorError" to com.cybersight.hyperfocus.appblocker.session.SessionService.lastError,
        "limitsError" to com.cybersight.hyperfocus.appblocker.applimits.AppLimitService.lastError,
        "sessionActive" to com.cybersight.hyperfocus.appblocker.session.SessionStore.isLocked(c),
        "accessibility" to (PlayPermissions.accessibilityReady(c))
    )
}
