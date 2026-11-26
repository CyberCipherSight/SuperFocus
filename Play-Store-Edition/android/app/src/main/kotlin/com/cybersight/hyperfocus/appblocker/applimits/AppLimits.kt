package com.cybersight.hyperfocus.appblocker.applimits

import com.cybersight.hyperfocus.appblocker.BuildConfig

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cybersight.hyperfocus.appblocker.MainActivity
import com.cybersight.hyperfocus.appblocker.night.NightGuard
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup
import com.cybersight.hyperfocus.appblocker.protection.OwnerSuspensions
import com.cybersight.hyperfocus.appblocker.stats.UsageBridge
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/** Native authority for daily budgets, repeating breaks and timed app locks. */
object AppLimits {
    val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "focusbro-app-limits") }
    private fun prefs(c: Context) = c.getSharedPreferences("focusbro.appLimits", Context.MODE_PRIVATE)
    private fun packages(c: Context) = prefs(c).getStringSet("packages", emptySet()).orEmpty().toSet()
    @Volatile private var blocked = emptySet<String>()
    @Volatile private var configured = false
    @Volatile private var lastError = ""
    fun isBlocked(pkg: String) = pkg in blocked

    fun isBlockedNow(c: Context, pkg: String): Boolean {
        if (!BuildConfig.PLAY_EDITION) return isBlocked(pkg)
        if (!eligible(c,pkg)) return false
        val p = prefs(c)
        val now = System.currentTimeMillis()
        return p.getLong("until:$pkg",0L) > now ||
            (UsageBridge.granted(c) && p.getLong("exhausted:$pkg",0L) > now && isBlocked(pkg))
    }
    fun blocksInFocus(c: Context, pkg: String): Boolean =
        prefs(c).getBoolean("focus:$pkg", false)

    fun setFocusBlocked(c: Context, pkg: String, enabled: Boolean) {
        require(BuildConfig.PLAY_EDITION && eligible(c, pkg)) { "This app cannot be selected" }
        check(prefs(c).edit().putBoolean("focus:$pkg", enabled).commit())
    }

    fun focusSelectionCount(c: Context): Int = prefs(c).all.count { (k, v) ->
        k.startsWith("focus:") && v == true && eligible(c, k.removePrefix("focus:"))
    }

    fun hasRules(c: Context) = packages(c).isNotEmpty() || BreakUsageMonitor.installedRules(c, prefs(c)).isNotEmpty()
    fun needsWatcher() = configured

    private fun eligible(c: Context, pkg: String) = pkg !in NightGuard.safetyAllowlist(c) &&
        runCatching { c.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    private var lastWindowSync = 0L
    fun onWindowChanged(c: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWindowSync < 1000 || !hasRules(c)) return
        lastWindowSync = now
        requestSync(c)
    }

    fun requestSync(c: Context) {
        val app = c.applicationContext
        AppLimitService.sync(app)
        executor.execute { runCatching { reconcile(app) }.onFailure {
            lastError = it.message ?: "App limits could not be checked"
            Log.e("FocusBroAppLimits", "reconcile failed", it)
        } }
    }

    @Synchronized
    fun clearAppRestrictions(c: Context, pkg: String) {
        require(BuildConfig.PLAY_EDITION && eligible(c, pkg)) { "This app cannot be edited" }
        val p = prefs(c)
        check(p.edit().remove("limit:$pkg").remove("until:$pkg").remove("exhausted:$pkg")
            .putStringSet("packages", packages(c) - pkg).commit())
        reconcile(c)
    }

    @Synchronized
    fun setDaily(c: Context, pkg: String, minutes: Int) {
        require(eligible(c, pkg)) { "Essential apps cannot be restricted" }
        require(minutes in 0..1440) { "Choose 1–1440 minutes, or remove the limit" }
        if (minutes > 0) require(UsageBridge.granted(c)) { "Enable Usage access before setting a daily limit" }
        reconcile(c)
        val p = prefs(c)
        AppLimitMath.effectiveLimit(p.getInt("limit:$pkg", 0), minutes,
            p.getLong("exhausted:$pkg", 0) > System.currentTimeMillis())
        val names = packages(c).toMutableSet()
        if (minutes > 0 || p.getLong("until:$pkg", 0) > System.currentTimeMillis()) names += pkg else names -= pkg
        check(p.edit().putInt("limit:$pkg", minutes).putStringSet("packages", names).commit())
        reconcile(c)
    }

    @Synchronized
    fun lockFor(c: Context, pkg: String, minutes: Int) {
        require(eligible(c, pkg)) { "Essential apps cannot be restricted" }
        require(minutes in 1..1440) { "Choose 1–1440 minutes" }
        val p = prefs(c)
        val now = System.currentTimeMillis()
        check(p.getLong("until:$pkg", 0) <= now) { "This app is already locked; its end time is unchanged" }
        check(p.edit().putLong("until:$pkg", now + minutes * 60_000L)
            .putStringSet("packages", packages(c) + pkg).commit())
        reconcile(c)
    }

    @Synchronized
    fun reconcile(c: Context) {
        val p = prefs(c)
        val now = ZonedDateTime.now()
        val ms = now.toInstant().toEpochMilli()
        val reset = AppLimitMath.nextReset(now)
        val start = AppLimitMath.dayStart(now).toInstant().toEpochMilli()
        val names = packages(c) - NightGuard.safetyAllowlist(c)
        val breakRules = BreakUsageMonitor.installedRules(c, p) - NightGuard.safetyAllowlist(c)
        configured = names.isNotEmpty() || breakRules.isNotEmpty()
        val usageGranted = UsageBridge.granted(c) && (!BuildConfig.PLAY_EDITION || com.cybersight.hyperfocus.appblocker.PlayMode.selected(c) != "basic")
        var breaksReadable = usageGranted
        val breaks = if (usageGranted && breakRules.isNotEmpty()) runCatching {
            BreakUsageMonitor.read(c, p, breakRules, ms)
        }.onFailure { breaksReadable = false; lastError = it.message.orEmpty() }.getOrNull() else null
        var foreground = emptySet<String>()
        var measured: Map<String, Long> = emptyMap()
        var usageReadable = usageGranted
        if (names.any { p.getInt("limit:$it", 0) > 0 } && usageGranted) {
            runCatching {
                val counter = UsageCounter(start, ms)
                val events = c.getSystemService(UsageStatsManager::class.java)
                    .queryEvents(start - 24 * 60 * 60_000L, ms)
                    ?: error("Usage history is unavailable until the phone is unlocked")
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val kind = when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> UsageCounter.Kind.RESUME
                        UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> UsageCounter.Kind.PAUSE
                        UsageEvents.Event.SCREEN_INTERACTIVE -> UsageCounter.Kind.SCREEN_ON
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageCounter.Kind.SCREEN_OFF
                        UsageEvents.Event.KEYGUARD_HIDDEN -> UsageCounter.Kind.UNLOCK
                        UsageEvents.Event.KEYGUARD_SHOWN -> UsageCounter.Kind.LOCK
                        UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> UsageCounter.Kind.REBOOT
                        else -> null
                    } ?: continue
                    val pkg = event.packageName.orEmpty()
                    // Keep screen/lifecycle state, and only selected apps' activity events.
                    if (kind in listOf(UsageCounter.Kind.RESUME, UsageCounter.Kind.PAUSE) && pkg !in names) continue
                    val activity = event.className.orEmpty()
                    counter.accept(UsageCounter.Event(event.timeStamp, kind, pkg, activity))
                }
                measured = counter.finish()
                foreground = counter.foreground()
            }.onFailure { usageReadable = false; lastError = it.message.orEmpty() }
        }
        var releaseAt = if (names.any { p.getInt("limit:$it", 0) > 0 }) reset else Long.MAX_VALUE
        var wakeAt = reset
        val wanted = mutableSetOf<String>()
        val keep = mutableSetOf<String>()
        val edit = p.edit()
        if (breaks != null) BreakUsageMonitor.save(edit, breaks, ms)
        for (pkg in breakRules.keys) {
            val until = breaks?.lockedUntil?.get(pkg) ?: p.getLong("breakUntil:$pkg", 0)
            if (until > ms || !breaksReadable) wanted += pkg
            if (until > ms) releaseAt = minOf(releaseAt, until)
        }
        breaks?.nextThreshold()?.let { wakeAt = minOf(wakeAt, it.coerceAtLeast(ms + 1000)) }
        for (pkg in names) {
            val limit = p.getInt("limit:$pkg", 0)
            val used = AppLimitMath.usageForDay(
                p.getLong("usedDay:$pkg", 0), start, p.getLong("used:$pkg", 0), measured[pkg] ?: 0)
            val until = p.getLong("until:$pkg", 0)
            var exhausted = p.getLong("exhausted:$pkg", 0)
            if (limit > 0 && used >= limit * 60_000L && exhausted <= ms) exhausted = reset
            edit.putLong("used:$pkg", used).putLong("usedDay:$pkg", start).putLong("exhausted:$pkg", exhausted)
            val dailyBlocked = if (BuildConfig.PLAY_EDITION) usageReadable && exhausted > ms
                else exhausted > ms || (limit > 0 && !usageReadable)
            if (until > ms || dailyBlocked) {
                // Retain configuration across uninstall/reinstall without
                // repeatedly asking DPM to suspend a nonexistent package.
                if (runCatching { c.packageManager.getApplicationInfo(pkg, 0) }.isSuccess) wanted += pkg
            }
            if (limit > 0 || until > ms || exhausted > ms) keep += pkg
            if (until > ms) releaseAt = minOf(releaseAt, until)
            if (exhausted > ms) releaseAt = minOf(releaseAt, exhausted)
            if (pkg in foreground && limit > 0 && pkg !in wanted) {
                wakeAt = minOf(wakeAt, ms + (limit * 60_000L - used).coerceAtLeast(1000))
            }
        }
        check(edit.putStringSet("packages", keep).commit())
        if (!BuildConfig.PLAY_EDITION && blocked != wanted) Log.i("FocusBroAppLimits", "blocked=${wanted.joinToString()} reset=$reset")
        blocked = wanted
        if (BuildConfig.PLAY_EDITION && com.cybersight.hyperfocus.appblocker.PlayMode.selected(c) != "basic") {
            com.cybersight.hyperfocus.appblocker.enforce.BlockerAccessibilityService.checkCurrentAppLimit()
        }
        configured = keep.isNotEmpty() || breakRules.isNotEmpty()
        val failed = if (OwnerSetup.isOwner(c)) OwnerSuspensions.apply(c, "limits", wanted) else emptySet()
        lastError = when {
            failed.isNotEmpty() -> "Android could not suspend: ${failed.joinToString()}"
            !breaksReadable && breakRules.isNotEmpty() -> "Usage access unavailable; apps with automatic breaks stay paused until it is restored."
            !usageReadable && names.any { p.getInt("limit:$it", 0) > 0 } -> if (BuildConfig.PLAY_EDITION) "Usage access unavailable; daily limits cannot be measured." else "Usage access unavailable; limited apps stay paused until it is restored."
            else -> ""
        }
        // Persist failed releases through the registry and retry even with no rules.
        schedule(c,
            if (configured || failed.isNotEmpty()) minOf(wakeAt, ms + 15 * 60_000L) else null,
            releaseAt.takeIf { configured && it != Long.MAX_VALUE })
    }

    private fun schedule(c: Context, monitorAt: Long?, releaseAt: Long?) {
        val manager = c.getSystemService(AlarmManager::class.java)
        fun pending(code: Int) = PendingIntent.getBroadcast(c, code,
            Intent(c, AppLimitReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val monitor = pending(41)
        if (monitorAt == null) manager.cancel(monitor) else {
            try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, monitorAt, monitor) }
            catch (e: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, monitorAt, monitor)
                Log.w("FocusBroAppLimits", "Exact usage check unavailable", e)
            }
        }
        // Release alarms have a separate identity and alarm-clock priority:
        // the idle quota consumed by monitoring must not delay a 07:00 release.
        val release = pending(42)
        if (releaseAt == null) manager.cancel(release) else {
            val show = PendingIntent.getActivity(c, 42, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try { manager.setAlarmClock(AlarmManager.AlarmClockInfo(releaseAt, show), release) }
            catch (e: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, releaseAt, release)
                Log.w("FocusBroAppLimits", "Exact release unavailable; release may be delayed", e)
            }
        }
    }

    @Synchronized
    fun status(c: Context): Map<String, Any> {
        val p = prefs(c)
        val pm = c.packageManager
        val safety = NightGuard.safetyAllowlist(c)
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        val breakRules = BreakUsageMonitor.installedRules(c, p)
        // Read-only UI status; enforcement continues to be owned by NightGuard.
        val nightBlocked = if (NightGuard.isActiveNow()) NightGuard.targets(c) else emptySet()
        val rows = (launchable + packages(c) + breakRules.keys).filter { it !in safety }.map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            mapOf("package" to pkg, "label" to label, "dailyMinutes" to p.getInt("limit:$pkg", 0),
                "usedMs" to AppLimitMath.usageForDay(p.getLong("usedDay:$pkg", 0),
                    AppLimitMath.dayStart(ZonedDateTime.now()).toInstant().toEpochMilli(), p.getLong("used:$pkg", 0), 0), "lockedUntil" to p.getLong("until:$pkg", 0),
                "exhaustedUntil" to p.getLong("exhausted:$pkg", 0), "blocked" to (pkg in blocked),
                "nightBlocked" to (pkg in nightBlocked),
                "focusBlocked" to blocksInFocus(c, pkg),
                "breakUseMinutes" to (breakRules[pkg]?.useMinutes ?: 0),
                "breakMinutes" to (breakRules[pkg]?.breakMinutes ?: 0),
                "breakUntil" to p.getLong("breakUntil:$pkg", 0),
                "continuousUsedMs" to if (p.getString("breakForeground", "") == pkg && p.getLong("breakStartedAt", 0) > 0)
                    (System.currentTimeMillis() - p.getLong("breakStartedAt", 0)).coerceAtLeast(0) else 0L)
        }.sortedBy { it["label"].toString().lowercase() }
        return mapOf("apps" to rows, "usageGranted" to UsageBridge.granted(c),
            "deviceOwner" to OwnerSetup.isOwner(c), "error" to lastError, "playMode" to com.cybersight.hyperfocus.appblocker.PlayMode.effective(c),
            "exactAlarms" to (com.cybersight.hyperfocus.appblocker.owner.HealthCheck.status(c)["exactAlarms"] == true),
            "resetAt" to AppLimitMath.nextReset(ZonedDateTime.now()))
    }

    @Synchronized
    fun tearDown(c: Context) {
        check(prefs(c).edit().clear().putBoolean("automaticBreaksDisabled", true).commit())
        blocked = emptySet(); configured = false
        if (OwnerSetup.isOwner(c)) OwnerSuspensions.apply(c, "limits", emptySet())
        schedule(c, null, null)
        c.stopService(Intent(c, AppLimitService::class.java))
    }
}
