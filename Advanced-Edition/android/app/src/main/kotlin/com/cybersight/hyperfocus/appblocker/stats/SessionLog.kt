package com.cybersight.hyperfocus.appblocker.stats

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local session history (ARCHITECTURE.md §8).
 *
 * Needs no permission at all — these are FocusBro's own sessions — so the focus
 * trend works even when usage access is denied. Only the per-app breakdown
 * depends on [UsageBridge].
 *
 * ponytail: newline-delimited prefs, not SQLite. 180 days of sessions is a few
 * hundred short lines; move to a table if per-app history is ever kept here too.
 */
object SessionLog {

    /** §8 retention: local-only, and cleared by the user without any gate. */
    const val RETENTION_DAYS = 180L

    private const val PREFS = "focusbro.log"
    private const val KEY = "sessions"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(val startedAt: Long, val durationMs: Long) {
        fun date(zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
    }

    fun record(context: Context, startedAt: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val kept = entries(context).filter { it.startedAt != startedAt } + Entry(startedAt, durationMs)
        write(context, kept)
    }

    fun entries(context: Context): List<Entry> =
        prefs(context).getString(KEY, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val f = line.split("|")
                if (f.size != 2) null
                else runCatching { Entry(f[0].toLong(), f[1].toLong()) }.getOrNull()
            }

    private fun write(context: Context, entries: List<Entry>) {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        val pruned = entries.filter { it.startedAt >= cutoff }.sortedBy { it.startedAt }
        prefs(context).edit()
            .putString(KEY, pruned.joinToString("\n") { "${it.startedAt}|${it.durationMs}" })
            .apply()
    }

    /** Focus minutes per day for the last [days] days, oldest first. */
    fun dailyMinutes(context: Context, days: Int): List<Map<String, Any>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val totals = entries(context)
            .groupBy { it.date(zone) }
            .mapValues { (_, e) -> e.sumOf { it.durationMs } / 60_000L }

        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            mapOf("date" to date.toString(), "minutes" to (totals[date] ?: 0L))
        }
    }

    fun recent(context: Context, limit: Int): List<Map<String, Any>> =
        entries(context).sortedByDescending { it.startedAt }.take(limit)
            .map { mapOf("startedAt" to it.startedAt, "durationMs" to it.durationMs) }

    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
