package com.cybersight.hyperfocus.appblocker.dock

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import com.cybersight.hyperfocus.appblocker.enforce.AllowedApps
import com.cybersight.hyperfocus.appblocker.enforce.MusicApps

/**
 * The six immovable emergency slots (ARCHITECTURE.md §3).
 *
 * Every slot resolves at runtime from an intent or a system default — no
 * hardcoded package names, because clock/dialer/SMS packages differ on every
 * OEM and change between releases.
 */
object DockResolver {

    /** Slot ids match the Dart side's [kMandatoryDock]. */
    const val PHONE = "phone"
    const val EMERGENCY = "emergency"
    const val CONTACTS = "contacts"
    const val MESSAGES = "messages"
    const val CAMERA = "camera"
    const val CLOCK = "clock"

    /**
     * The launch intent for a slot, or null when the device has no app for it
     * (a tablet with no dialer, for instance). Callers must handle null rather
     * than assume six working slots.
     */
    fun intentFor(context: Context, id: String): Intent? {
        val pm = context.packageManager
        val intent = when (id) {
            PHONE -> CallingApps.phoneIntent(context)

            // Deliberately the bare keypad, with no number prefilled: emergency
            // numbers are region- and SIM-specific, and guessing 112 vs 911
            // would be worse than letting the dialer do its job.
            EMERGENCY -> CallingApps.phoneIntent(context, emergency = true)

            CONTACTS -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)

            MESSAGES -> Telephony.Sms.getDefaultSmsPackage(context)
                ?.let { pm.getLaunchIntentForPackage(it) }

            CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)

            // SHOW_ALARMS, not SET_ALARM: viewing and setting both stay possible
            // during a session, which is why the clock app needs no protection.
            CLOCK -> Intent(AlarmClock.ACTION_SHOW_ALARMS)

            else -> null
        } ?: return null

        return if (intent.resolveActivity(pm) != null) intent else null
    }

    /** The user-chosen allowlist, their own dev builds, and any music player. */
    fun allowedApps(context: Context): List<Map<String, Any>> =
        (AllowedApps.labelled(context) + MusicApps.labelled(context) + CallingApps.launchable(context))
            .distinctBy { it["id"] }
            .sortedBy { it["label"] as String }

    fun launchPackage(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Packages that must be added to the lock-task allowlist. */
    fun packages(context: Context): List<String> {
        val pm = context.packageManager
        return (
            listOf(PHONE, EMERGENCY, CONTACTS, MESSAGES, CAMERA, CLOCK)
                .mapNotNull { intentFor(context, it)?.resolveActivity(pm)?.packageName } +
                AllowedApps.installed(context) +
                MusicApps.installed(context) +
                CallingApps.resolve(context).packages
            ).distinct()
    }

    /** What the UI renders: which slots actually exist on this device. */
    fun availability(context: Context): List<Map<String, Any>> =
        listOf(PHONE, EMERGENCY, CONTACTS, MESSAGES, CAMERA, CLOCK).map {
            mapOf("id" to it, "available" to (intentFor(context, it) != null))
        }

    fun launch(context: Context, id: String): Boolean {
        val intent = intentFor(context, id) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
