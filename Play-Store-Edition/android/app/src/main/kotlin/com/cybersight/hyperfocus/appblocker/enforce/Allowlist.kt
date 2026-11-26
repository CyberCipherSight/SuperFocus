package com.cybersight.hyperfocus.appblocker.enforce

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.cybersight.hyperfocus.appblocker.dock.DockResolver

/**
 * What stays reachable during a session on the accessibility tier (§19.5).
 *
 * Resolved, never hardcoded. Recomputed at most once every 30 seconds because
 * this is consulted on every window change, and resolving intents on each event
 * would be a real battery cost for no benefit.
 */
object Allowlist {

    // Two minutes, not thirty seconds: each rebuild is eight PackageManager
    // queries for a set that only changes when apps are installed, and
    // invalidate() already covers the moments that matter.
    private const val CACHE_MS = 120_000L

    @Volatile private var cache: Set<String> = emptySet()
    @Volatile private var cachedAt = 0L

    fun packages(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS && cache.isNotEmpty()) return cache
        cache = build(context)
        cachedAt = now
        return cache
    }

    fun invalidate() {
        cachedAt = 0L
    }

    fun permits(context: Context, packageName: String): Boolean =
        packageName in packages(context)

    /**
     * Whether this window is the accessibility settings page.
     *
     * Matched on the activity class name rather than the package, so the rest of
     * Settings stays usable. Class names differ across OEM skins, hence the
     * substring match on both the class and its subsettings wrapper.
     */
    fun isAccessibilitySettings(packageName: String, className: String): Boolean {
        if (!packageName.contains("settings", ignoreCase = true)) return false
        return className.contains("accessibility", ignoreCase = true)
    }

    private fun build(context: Context): Set<String> {
        val pm = context.packageManager
        val set = mutableSetOf(context.packageName)

        // The launcher must stay reachable or GLOBAL_ACTION_HOME ejects into a
        // blocked app and loops forever.
        pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName?.let(set::add)

        // Keyboard: a blocked IME makes the phone unusable (edge case 40).
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?.let(set::add)

        // §2.1: Settings is never blocked. Disabling the service from here is
        // detected and priced, not prevented — the alternative is an app that
        // can trap you, which is the line this project does not cross.
        pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
            ?.activityInfo?.packageName?.let(set::add)

        set += "com.android.systemui"
        set += DockResolver.packages(context)   // emergency six + AllowedApps
        return set
    }
}
