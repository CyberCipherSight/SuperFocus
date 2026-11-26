package com.cybersight.hyperfocus.appblocker.session

import android.content.Context

/**
 * The removal gate (ARCHITECTURE.md §7).
 *
 * Restrictions tighten instantly; loosening one costs a foreground dwell. The
 * dwell start is persisted natively so that killing the app neither grants free
 * progress nor silently resets a dwell the user has already served — it does
 * exactly one thing, which is reset on leaving the foreground.
 */
object RatchetStore {

    /** §7: weakening or deleting a routine. */
    const val DWELL_MS = 5L * 60_000L

    private const val PREFS = "focusbro.ratchet"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun begin(context: Context, key: String): Long {
        val existing = prefs(context).getLong(key, 0L)
        if (existing != 0L) return existing
        val now = System.currentTimeMillis()
        prefs(context).edit().putLong(key, now).apply()
        return now
    }

    fun remainingMs(context: Context, key: String): Long {
        val started = prefs(context).getLong(key, 0L)
        if (started == 0L) return DWELL_MS
        // A clock moved backwards cannot shorten a dwell (§7 clock trust).
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
        return (DWELL_MS - elapsed).coerceAtLeast(0L)
    }

    fun satisfied(context: Context, key: String): Boolean =
        prefs(context).getLong(key, 0L) != 0L && remainingMs(context, key) == 0L

    /** Called when the app leaves the foreground: dwell must be served awake. */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
