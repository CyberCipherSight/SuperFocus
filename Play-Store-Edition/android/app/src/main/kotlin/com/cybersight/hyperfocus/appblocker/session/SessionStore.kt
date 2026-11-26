package com.cybersight.hyperfocus.appblocker.session

import android.content.Context

/**
 * Native-owned session state (ARCHITECTURE.md §11).
 *
 * This is the source of truth, NOT the Dart layer. The boot receiver and the
 * routine alarm both run with no Flutter engine alive, so anything that decides
 * whether the phone is locked has to be readable from Kotlin alone.
 */
object SessionStore {

    /**
     * §2.1 — the recoverability guarantee. Enforced here, at the bottom, so no
     * caller can produce a longer session however it is reached.
     */
    const val MAX_SESSION_MS = 180L * 60_000L

    /** §2.2 — two sessions cannot be chained into a continuous block. */
    const val MIN_GAP_MS = 60_000L

    private const val PREFS = "focusbro.session"
    private const val KEY_ENDS_AT = "endsAtUtc"
    private const val KEY_STARTED_AT = "startedAtUtc"
    private const val KEY_LAST_ENDED = "lastEndedAtUtc"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(context: Context, requestedMs: Long): Long {
        val duration = requestedMs.coerceIn(1L, MAX_SESSION_MS)
        val now = System.currentTimeMillis()
        val endsAt = now + duration
        prefs(context).edit()
            .putLong(KEY_ENDS_AT, endsAt)
            .putLong(KEY_STARTED_AT, now)
            .apply()
        return endsAt
    }

    /** Atomically persist occurrence consumption and its original end time.
     * Duplicate alarms, activity launches and reboot reconciliation cannot
     * restart a completed occurrence or extend a running session.
     */
    @Synchronized
    fun startRoutine(context: Context, routine: Routine, start: java.time.ZonedDateTime): Boolean {
        val key = "routine:${routine.id}:${start.toLocalDate()}"
        val p = prefs(context)
        if (p.getBoolean(key, false)) return false
        val now = System.currentTimeMillis()
        val end = start.toInstant().toEpochMilli() + routine.durationMin * 60_000L
        val editor = p.edit().putBoolean(key, true)
        val accepted = canStart(context) && end > now
        if (accepted) editor.putLong(KEY_ENDS_AT, end).putLong(KEY_STARTED_AT, now)
        check(editor.commit()) { "Could not persist routine occurrence" }
        return accepted
    }

    fun endsAt(context: Context): Long = prefs(context).getLong(KEY_ENDS_AT, 0L)

    fun startedAt(context: Context): Long = prefs(context).getLong(KEY_STARTED_AT, 0L)

    fun remainingMs(context: Context): Long {
        val endsAt = endsAt(context)
        if (endsAt == 0L) return 0L
        // Capped as well as floored: a forward clock jump cannot extend a
        // session past the guarantee (edge cases 10-11).
        return (endsAt - System.currentTimeMillis()).coerceIn(0L, MAX_SESSION_MS)
    }

    fun isLocked(context: Context): Boolean = remainingMs(context) > 0L

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_ENDS_AT)
            .remove(KEY_STARTED_AT)
            .putLong(KEY_LAST_ENDED, System.currentTimeMillis())
            .apply()
    }

    private const val KEY_BLOCKS = "blockCount"
    private const val KEY_TAMPER = "tamperCount"

    /** An app was ejected during a session. Shown in analytics, never as blame. */
    fun recordBlock(context: Context, @Suppress("UNUSED_PARAMETER") packageName: String) {
        val p = prefs(context)
        p.edit().putInt(KEY_BLOCKS, p.getInt(KEY_BLOCKS, 0) + 1).apply()
    }

    /** The accessibility service was switched off mid-session (§2.1). */
    fun recordTamper(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_TAMPER, p.getInt(KEY_TAMPER, 0) + 1).apply()
    }

    fun blockCount(context: Context): Int = prefs(context).getInt(KEY_BLOCKS, 0)
    fun tamperCount(context: Context): Int = prefs(context).getInt(KEY_TAMPER, 0)

    fun canStart(context: Context): Boolean {
        if (isLocked(context)) return false
        val last = prefs(context).getLong(KEY_LAST_ENDED, 0L)
        if (last == 0L) return true
        return System.currentTimeMillis() - last >= MIN_GAP_MS
    }
}
