package com.cybersight.hyperfocus.appblocker.stats

import com.cybersight.hyperfocus.appblocker.PlayPrivacy

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.os.Process
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-app foreground durations (ARCHITECTURE.md §8, §16).
 *
 * Durations only. FocusBro never reads what was on screen, what was typed, or
 * what was sent — the accessibility service that would allow that does not
 * exist in this app.
 *
 * Package labels resolve without QUERY_ALL_PACKAGES: a device owner is exempt
 * from API 30 package-visibility filtering, and FocusBro requires device owner
 * anyway. Where a label cannot be resolved the package name is shown rather
 * than the row being dropped.
 */
object UsageBridge {

    fun todayByApp(context: Context, limit: Int = 8): List<Map<String, Any>> {
        if (!granted(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        val manager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // queryAndAggregateUsageStats already merges the multiple buckets a
        // package can produce in one interval; queryUsageStats would need that
        // summing done by hand.
        val byPackage = manager.queryAndAggregateUsageStats(start, now)
            .filterValues { it.totalTimeInForeground > 0 }
            .filterKeys { it != context.packageName }
            .mapValues { it.value.totalTimeInForeground }
            .entries
            .sortedByDescending { it.value }

        val top = byPackage.take(limit).map {
            mapOf(
                "package" to it.key,
                "label" to label(context, it.key),
                "ms" to it.value,
            )
        }
        val rest = byPackage.drop(limit).sumOf { it.value }
        return if (rest > 0) {
            top + mapOf("package" to "", "label" to "Everything else", "ms" to rest)
        } else {
            top
        }
    }

    private fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * The single usage-access check in the app — HealthCheck delegates here
     * rather than repeating it.
     *
     * `unsafeCheckOpNoThrow` is deprecated but has no replacement that answers
     * this question without a permission we do not hold; the alternative is
     * inferring the grant from an empty query result, which is a guess.
     */
    @Suppress("DEPRECATION")
    fun granted(context: Context): Boolean {
        return PlayPrivacy.permitted(context, "usageStats") && systemGranted(context)
    }

    @Suppress("DEPRECATION")
    fun systemGranted(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val op = AppOpsManager.OPSTR_GET_USAGE_STATS
        val uid = Process.myUid()
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(op, uid, context.packageName)
        else ops.checkOpNoThrow(op, uid, context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
