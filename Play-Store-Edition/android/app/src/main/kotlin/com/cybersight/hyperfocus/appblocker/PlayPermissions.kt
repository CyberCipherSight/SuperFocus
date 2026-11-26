package com.cybersight.hyperfocus.appblocker

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.cybersight.hyperfocus.appblocker.enforce.BlockerAccessibilityService
import com.cybersight.hyperfocus.appblocker.stats.UsageBridge

/** Public Android APIs only. A grant, disclosure consent and a live service are separate. */
object PlayPermissions {
    private fun check(block: () -> Boolean) = runCatching(block).getOrDefault(false)
    fun accessibilityEnabled(c: Context): Boolean = check {
        val component = ComponentName(c, BlockerAccessibilityService::class.java)
        c.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName.unflattenFromString(it.id) == component }
    }
    fun accessibilityReady(c: Context) = accessibilityEnabled(c) &&
        PlayPrivacy.permitted(c, "accessibility") && BlockerAccessibilityService.connected
    fun notificationGranted(c: Context) = check {
        (Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
    fun reminderChannelEnabled(c: Context) = check {
        c.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("focusbro.reminders")?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun exact(c: Context) = Build.VERSION.SDK_INT < 31 || check {
        c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
    fun battery(c: Context) = check {
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    }
    fun backgroundRestricted(c: Context) = check {
        c.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    }
    fun snapshot(c: Context): Map<String, String> = mapOf(
        "accessibility" to PlayPermissionPolicy.status(accessibilityEnabled(c), PlayPrivacy.permitted(c,"accessibility"), BlockerAccessibilityService.connected),
        "usageStats" to PlayPermissionPolicy.status(UsageBridge.systemGranted(c), PlayPrivacy.permitted(c,"usageStats")),
        "overlay" to PlayPermissionPolicy.status(check { Settings.canDrawOverlays(c) }, PlayPrivacy.permitted(c,"overlay")),
        "notifications" to PlayPermissionPolicy.status(notificationGranted(c), operational = reminderChannelEnabled(c)),
        "exactAlarms" to if (Build.VERSION.SDK_INT < 31) "automatic" else PlayPermissionPolicy.status(exact(c)),
        "batteryExempt" to PlayPermissionPolicy.status(battery(c)),
        "background" to if (backgroundRestricted(c)) "restricted" else "not_restricted",
        "foregroundService" to PlayPermissionPolicy.status(c.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED),
        "boot" to PlayPermissionPolicy.status(c.checkSelfPermission(Manifest.permission.RECEIVE_BOOT_COMPLETED) == PackageManager.PERMISSION_GRANTED)
    )
    private fun open(a: Activity, vararg intents: Intent): Boolean {
        for (i in intents) if (runCatching { a.startActivity(i) }.isSuccess) return true
        return false
    }
    fun request(a: Activity, id: String): Boolean {
        val uri = Uri.parse("package:" + a.packageName)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        if (id == "notifications") {
            val p = a.getSharedPreferences("focusbro.playConsent", Context.MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= 33 && PlayPermissionPolicy.notificationAction(
                    Build.VERSION.SDK_INT, a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    p.getBoolean("askedNotifications", false), a.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                ) == "runtime") {
                return runCatching {
                    p.edit().putBoolean("askedNotifications", true).apply()
                    a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 601)
                    true
                }.getOrElse { open(a, details) }
            }
            val notification = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,a.packageName)
            val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,a.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID,"focusbro.reminders")
            return if (notificationGranted(a) && !reminderChannelEnabled(a)) open(a,channel,notification,details)
                else open(a,notification,details)
        }
        val settings = when (id) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "usageStats" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
            "exactAlarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,uri) else return true
            "batteryExempt" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "background" -> details
            else -> return false
        }
        return PlayPrivacy.disclose(a,id) {
            if (!open(a,settings,details)) android.widget.Toast.makeText(a,"Open Android Settings manually to grant access.",android.widget.Toast.LENGTH_LONG).show()
        } || open(a,settings,details)
    }
}
