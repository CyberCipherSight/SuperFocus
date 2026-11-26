package com.cybersight.hyperfocus.appblocker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context

object PlayPrivacy {
    private val disclosures = mapOf(
        "accessibility" to (
            "FocusBro uses Android Accessibility to detect which app opens, including while FocusBro is closed. " +
                "It uses the app identifier to apply the limits and focus rules you choose. It does not read screen content, " +
                "messages, or keystrokes. Nothing is sent off your device. You can disable FocusBro in Android Accessibility settings."
        ),
        "usageStats" to (
            "FocusBro uses Android Usage Access to read app identifiers and foreground usage times, including in the background. " +
                "It uses these to calculate your daily app limits and usage charts. This information stays on your device. " +
                "You can revoke Usage Access in Android Settings."
        ),
        "overlay" to (
            "FocusBro can display its own notice when a selected app is blocked. " +
                "Display over other apps does not give FocusBro access to the content underneath. You can revoke it in Android Settings."
        ),
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences("focusbro.playConsent", Context.MODE_PRIVATE)

    fun permitted(context: Context, feature: String): Boolean =
        !BuildConfig.PLAY_EDITION || prefs(context).getBoolean(feature, false)

    fun disclose(activity: Activity, feature: String, openSettings: () -> Unit): Boolean {
        if (!BuildConfig.PLAY_EDITION) return false
        val message = disclosures[feature] ?: return false
        if (permitted(activity, feature)) return false
        AlertDialog.Builder(activity)
            .setTitle(when (feature) {
                "accessibility" -> "Allow app blocking?"
                "usageStats" -> "Allow usage measurement?"
                else -> "Show blocking notices?"
            })
            .setMessage(message)
            .setNegativeButton("Not now", null)
            .setPositiveButton("Agree and open settings") { _, _ ->
                if (prefs(activity).edit().putBoolean(feature, true).commit()) openSettings()
            }
            .show()
        return true
    }
}
