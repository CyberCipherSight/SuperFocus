package com.cybersight.hyperfocus.appblocker.enforce

import com.cybersight.hyperfocus.appblocker.BuildConfig

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cybersight.hyperfocus.appblocker.night.NightGuard
import com.cybersight.hyperfocus.appblocker.night.NightGuardService
import com.cybersight.hyperfocus.appblocker.root.RootEnforcer
import com.cybersight.hyperfocus.appblocker.session.SessionStore

/**
 * The backstop that makes enforcement survive a reboot (§19.8).
 *
 * The accessibility service only acts on events it receives. It misses windows
 * opened before it binds — which is exactly the gap after a reboot, since the
 * system rebinds accessibility services on its own schedule while the session is
 * already live. It also misses the occasional event outright.
 *
 * This polls the current foreground package from UsageStats instead, driven by
 * the foreground service, which the boot receiver restarts deterministically.
 * Belt and braces: accessibility ejects fast when it sees an event, this catches
 * whatever it did not.
 */
object ForegroundWatcher {

    private const val INTERVAL_MS = 800L
    private const val FIRST_LOOKBACK_MS = 10_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var ticks = 0
    @Volatile private var queriedUpTo = 0L
    @Volatile private var lastForeground: String? = null

    fun start(context: Context) {
        if (BuildConfig.PLAY_EDITION) { PlayNudge.start(context); return }
        if (running) return
        // Resolved once: holding the Service in the Runnable leaked it past
        // onDestroy.
        val app = context.applicationContext
        running = true
        ticks = 0
        queriedUpTo = 0L
        lastForeground = null
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                tick(app)
                handler.postDelayed(this, INTERVAL_MS)
            }
        })
    }

    /**
     * Two hosts share this watcher — the session service and Night Guard's
     * overnight service — so a stop from one must not end the other's
     * enforcement. It only really stops when neither still needs it.
     */
    fun stop(context: Context) {
        if (BuildConfig.PLAY_EDITION) { PlayNudge.stopIfIdle(context); return }
        val app = context.applicationContext
        if (com.cybersight.hyperfocus.appblocker.applimits.AppLimitService.running || SessionStore.isLocked(app) || (NightGuardService.running && NightGuard.isActiveNow())) return
        running = false
        handler.removeCallbacksAndMessages(null)
        BlockOverlay.hide(app)
    }

    private fun tick(context: Context) {
        val session = SessionStore.isLocked(context)
        // Night Guard keeps the watcher alive on the non-owner tiers through
        // the night (§24.6); either reason is enough to keep polling.
        if (!session && !NightGuard.isActiveNow() && !com.cybersight.hyperfocus.appblocker.applimits.AppLimits.needsWatcher()) {
            stop(context)
            return
        }
        // Roughly every 6s: put the accessibility service back if it was switched
        // off. Without root this is a no-op and the escape simply works.
        if (++ticks % 8 == 0) RootEnforcer.healAccessibility(context)

        val current = foregroundPackage(context) ?: return
        when {
            com.cybersight.hyperfocus.appblocker.applimits.AppLimits.isBlocked(current) -> {
                BlockOverlay.show(context, current, BlockOverlay.Reason.APP_LIMIT)
                RootEnforcer.stopApp(current)
            }
            session && !Allowlist.permits(context, current) -> {
                BlockOverlay.show(context, current)
                // Root closes the app rather than covering it, so there is no
                // process left waiting behind the overlay.
                RootEnforcer.stopApp(current)
                SessionStore.recordBlock(context, current)
            }
            NightGuard.isBlocked(context, current) -> {
                BlockOverlay.show(context, current, BlockOverlay.Reason.NIGHT)
                RootEnforcer.stopApp(current)
            }
            else -> BlockOverlay.onAllowedForeground(context, current)
        }
    }

    /**
     * The current foreground package, or null when usage access is not granted.
     *
     * Queries only what has happened since the last call rather than a fixed
     * ten-second window, and remembers the last answer — an empty window means
     * "nothing changed", not "nothing is in the foreground", and re-parsing ten
     * seconds of events eight times a second was pure waste.
     */
    fun foregroundPackage(context: Context): String? {
        val manager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val since = if (queriedUpTo == 0L) now - FIRST_LOOKBACK_MS else queriedUpTo
        queriedUpTo = now

        val events = manager.queryEvents(since, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // ACTIVITY_RESUMED is the API 29 rename of MOVE_TO_FOREGROUND and
            // carries the same value, so one check covers both.
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForeground = event.packageName
            }
        }
        return lastForeground
    }
}
