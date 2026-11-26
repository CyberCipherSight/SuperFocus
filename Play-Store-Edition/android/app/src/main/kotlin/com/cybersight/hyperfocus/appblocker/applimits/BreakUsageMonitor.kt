package com.cybersight.hyperfocus.appblocker.applimits

import com.cybersight.hyperfocus.appblocker.BuildConfig

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences

/** Persist the reducer's cursor and run so a process restart cannot reset a
 * pending break. Usage events fill the gap while the process was absent. */
object BreakUsageMonitor {
    fun installedRules(c: Context, p: SharedPreferences): Map<String, ContinuousBreaks.Rule> {
        if (BuildConfig.PLAY_EDITION || p.getBoolean("automaticBreaksDisabled", false)) return emptyMap()
        return ContinuousBreaks.RULES.filterKeys {
            runCatching { c.packageManager.getApplicationInfo(it, 0) }.isSuccess
        }
    }

    fun read(c: Context, p: SharedPreferences, rules: Map<String, ContinuousBreaks.Rule>, now: Long): ContinuousBreaks {
        val cursor = p.getLong("breakCursor", 0)
        val continuing = cursor in (now - 24 * 60 * 60_000L)..now && cursor > 0
        val state = if (!continuing) ContinuousBreaks.State() else ContinuousBreaks.State(
            p.getString("breakForeground", "").orEmpty(), p.getString("breakActivity", "").orEmpty(),
            p.getLong("breakStartedAt", 0), p.getLong("breakPausedAt", 0),
            p.getBoolean("breakInteractive", true), p.getBoolean("breakUnlocked", true),
        )
        val tracker = ContinuousBreaks(rules, state,
            rules.keys.associateWith { p.getLong("breakUntil:$it", 0) },
            activeSince = if (continuing) 0 else now)
        // First activation reconstructs the foreground app, but starts its new
        // use allowance now. Subsequent reads replay only unprocessed events.
        val events = c.getSystemService(UsageStatsManager::class.java)
            .queryEvents(if (continuing) cursor else now - 24 * 60 * 60_000L, now)
            ?: error("Usage history is unavailable until the phone is unlocked")
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageCounter.Kind.RESUME
                // STOPPED can belong to an old instance of the same activity
                // after a new instance already resumed (e.g. rotation).
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageCounter.Kind.PAUSE
                UsageEvents.Event.SCREEN_INTERACTIVE -> UsageCounter.Kind.SCREEN_ON
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageCounter.Kind.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_HIDDEN -> UsageCounter.Kind.UNLOCK
                UsageEvents.Event.KEYGUARD_SHOWN -> UsageCounter.Kind.LOCK
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> UsageCounter.Kind.REBOOT
                else -> null
            } ?: continue
            // All app transitions matter: leaving Instagram for any other app
            // must end its consecutive period even if that app has no rule.
            tracker.accept(UsageCounter.Event(event.timeStamp, kind, event.packageName.orEmpty(), event.className.orEmpty()))
        }
        tracker.finish(now)
        return tracker
    }

    fun save(edit: SharedPreferences.Editor, tracker: ContinuousBreaks, now: Long) {
        val s = tracker.state
        edit.putLong("breakCursor", now).putString("breakForeground", s.pkg)
            .putString("breakActivity", s.activity).putLong("breakStartedAt", s.startedAt)
            .putLong("breakPausedAt", s.pausedAt).putBoolean("breakInteractive", s.interactive)
            .putBoolean("breakUnlocked", s.unlocked)
        tracker.lockedUntil.forEach { (pkg, until) -> edit.putLong("breakUntil:$pkg", until) }
    }
}
