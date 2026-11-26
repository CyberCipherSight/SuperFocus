package com.cybersight.hyperfocus.appblocker.enforce

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cybersight.hyperfocus.appblocker.BuildConfig
import com.cybersight.hyperfocus.appblocker.MainActivity
import com.cybersight.hyperfocus.appblocker.PlayMode
import com.cybersight.hyperfocus.appblocker.R
import com.cybersight.hyperfocus.appblocker.applimits.AppLimitService
import com.cybersight.hyperfocus.appblocker.applimits.AppLimits
import com.cybersight.hyperfocus.appblocker.night.NightGuard
import com.cybersight.hyperfocus.appblocker.session.SessionStore
import com.cybersight.hyperfocus.appblocker.stats.UsageBridge

/** User-authorised reminder, never ejects apps or covers system controls. */
object PlayNudge {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var foreground: String? = null
    private var foregroundClass: String? = null
    private var cursor = 0L
    private val last = mutableMapOf<String, Long>()
    private var notice: View? = null
    private var noticePackage: String? = null
    private const val CHANNEL = "focusbro.reminders"
    private const val ID = 62
    fun start(c: Context) {
        if (!BuildConfig.PLAY_EDITION || running) return
        val app = c.applicationContext
        running = true
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                if (!com.cybersight.hyperfocus.appblocker.session.SessionService.running && !AppLimitService.running) {
                    stopIfIdle(app); return
                }
                runCatching { tick(app) }.onFailure { android.util.Log.w("FocusBroReminder", "Reminder check failed", it); hide(app); foreground = null; foregroundClass = null; cursor = 0L }
                if (running) handler.postDelayed(this, 2000)
            }
        })
    }
    fun reset(c: Context) {
        last.clear(); cursor = 0L; foreground = null; foregroundClass = null; hide(c)
        c.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    fun stopIfIdle(c: Context) {
        if (com.cybersight.hyperfocus.appblocker.session.SessionService.running || AppLimitService.running) return
        running = false; handler.removeCallbacksAndMessages(null); reset(c)
    }
    private fun tick(c: Context) {
        if (PlayMode.effective(c) != "reminder" || !UsageBridge.granted(c) ||
            !c.getSystemService(PowerManager::class.java).isInteractive ||
            c.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            hide(c); foreground = null; foregroundClass = null; cursor = 0L; return
        }
        val now = System.currentTimeMillis()
        val events = c.getSystemService(UsageStatsManager::class.java)
            .queryEvents(if (cursor == 0L || cursor > now) now - 60_000L else cursor, now) ?: return
        cursor = now
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    foreground = e.packageName
                    foregroundClass = e.className
                }
                // STOPPED may arrive for a previous Activity after a new Activity
                // in the same app resumed. Only the matching PAUSED clears it.
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    if (foreground == e.packageName && foregroundClass == e.className) {
                        foreground = null; foregroundClass = null
                    }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> {
                    foreground = null; foregroundClass = null
                }
            }
        }
        val pkg = foreground
        if (noticePackage != pkg || !PlayMode.overlay(c)) hide(c)
        if (pkg == null || pkg in NightGuard.safetyAllowlist(c) || Allowlist.permits(c, pkg)) return
        val limited = AppLimits.isBlockedNow(c, pkg)
        val focus = SessionStore.isLocked(c) && AppLimits.blocksInFocus(c, pkg)
        if (!limited && !focus) { hide(c); last.remove(pkg); return }
        val elapsed = SystemClock.elapsedRealtime()
        val previous = last[pkg]
        if (previous != null && elapsed - previous < PlayMode.interval(c) * 60_000L) return
        val label = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("This app")
        val message = if (limited) "$label has reached your chosen time limit or break." else "$label is on your focus list. Return to your intention when ready."
        val shown = PlayMode.overlay(c) && show(c, pkg, message)
        val notified = PlayMode.notifications(c) && runCatching {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Focus reminders", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(c, ID, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(ID, Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_focusbro_monochrome)
                .setContentTitle("FocusBro reminder").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(30_000L).build())
        }.isSuccess
        if (shown || notified) last[pkg] = elapsed
    }
    private fun show(c: Context, pkg: String, message: String): Boolean {
        hide(c)
        val density = c.resources.displayMetrics.density
        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*density).toInt(), (12*density).toInt(), (16*density).toInt(), (8*density).toInt())
            setBackgroundColor(android.graphics.Color.rgb(35, 29, 55))
            addView(TextView(c).apply { text = message; textSize = 16f; setTextColor(android.graphics.Color.WHITE) })
            addView(Button(c).apply { text = "Dismiss"; setOnClickListener { hide(c) } })
        }
        val params = WindowManager.LayoutParams((300*density).toInt(), -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        return runCatching {
            c.getSystemService(WindowManager::class.java).addView(panel, params)
            notice = panel; noticePackage = pkg
            handler.postDelayed({ if (notice === panel) hide(c) }, 12_000L)
        }.isSuccess
    }
    private fun hide(c: Context) {
        val current = notice ?: return
        runCatching { c.getSystemService(WindowManager::class.java).removeView(current) }
        notice = null; noticePackage = null
    }
}
