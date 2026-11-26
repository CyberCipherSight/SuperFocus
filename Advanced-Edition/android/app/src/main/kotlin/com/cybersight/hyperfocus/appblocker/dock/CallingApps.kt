package com.cybersight.hyperfocus.appblocker.dock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.InCallService
import android.telecom.TelecomManager

/** A keypad launcher and the incoming/ongoing call screen may be separate
 * packages. Both must be permitted before lock task starts, on every OEM. */
object CallingApps {
    data class Snapshot(val defaultDialer: String?, val systemDialer: String?,
                        val dialers: Set<String>, val packages: Set<String>)
    @Volatile private var cached: Snapshot? = null
    @Volatile private var cachedAt = 0L

    fun invalidate() { cachedAt = 0 }

    private fun isSystem(info: ApplicationInfo) =
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun resolve(c: Context): Snapshot {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.takeIf { cachedAt > 0 && now - cachedAt < 60_000 }?.let { return it }
        val pm = c.packageManager
        val telecom = c.getSystemService(TelecomManager::class.java)
        val default = runCatching { telecom?.defaultDialerPackage }.getOrNull()
        val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            runCatching { telecom?.systemDialerPackage }.getOrNull() else null
        val dialers = listOf(Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
            .flatMap { intent -> runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList()) }
            .mapNotNull { it.activityInfo?.takeIf { a -> a.enabled && a.exported }?.packageName }.toSet()
        val services = runCatching {
            pm.queryIntentServices(Intent(InCallService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
                .mapNotNull { it.serviceInfo }.filter { it.enabled }.map {
                    CallingPackagePolicy.CallUi(it.packageName, isSystem(it.applicationInfo),
                        it.permission == Manifest.permission.BIND_INCALL_SERVICE,
                        it.metaData?.getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI, false) == true)
                }
        }.getOrDefault(emptyList())
        // OEMs sometimes hide their in-call service from queries. These core
        // fallbacks count only when installed as genuine system packages.
        val fallbacks = setOf("com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.android.incallui", "com.android.dialer", "com.google.android.dialer",
            "com.samsung.android.dialer", "com.samsung.android.incallui", "com.samsung.android.app.telephonyui")
            .filter { runCatching { isSystem(pm.getApplicationInfo(it, 0)) }.getOrDefault(false) }.toSet()
        return Snapshot(default, system, dialers,
            CallingPackagePolicy.packages(default, system, dialers, services, fallbacks)).also {
            cached = it
            cachedAt = now
        }
    }

    fun phoneIntent(c: Context, emergency: Boolean = false): Intent? {
        val pm = c.packageManager
        val s = resolve(c)
        val preferred = if (emergency) listOfNotNull(s.systemDialer, s.defaultDialer)
            else listOfNotNull(s.defaultDialer, s.systemDialer)
        for (pkg in (preferred + s.dialers.sorted()).distinct()) {
            for (intent in listOf(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).setPackage(pkg),
                Intent(Intent.ACTION_DIAL).setPackage(pkg))) {
                if (intent.resolveActivity(pm) != null) return intent
            }
            pm.getLaunchIntentForPackage(pkg)?.let { return it }
        }
        // Covers devices whose default role is unset. This opens a dial pad or
        // chooser without placing a call or guessing an emergency number.
        return Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).takeIf { it.resolveActivity(pm) != null }
    }

    fun launchable(c: Context): List<Map<String, Any>> {
        val pm = c.packageManager
        val main = phoneIntent(c)?.resolveActivity(pm)?.packageName
        return resolve(c).dialers.filter { it != main && pm.getLaunchIntentForPackage(it) != null }.map { pkg ->
            mapOf("id" to pkg, "label" to runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg))
        }
    }

    fun status(c: Context): Map<String, Any> {
        val s = resolve(c)
        return mapOf("defaultDialer" to s.defaultDialer.orEmpty(), "systemDialer" to s.systemDialer.orEmpty(),
            "phoneAvailable" to (phoneIntent(c) != null), "packages" to s.packages.sorted())
    }
}
