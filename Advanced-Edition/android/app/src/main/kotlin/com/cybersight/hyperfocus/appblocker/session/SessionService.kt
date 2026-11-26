package com.cybersight.hyperfocus.appblocker.session

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.cybersight.hyperfocus.appblocker.MainActivity
import com.cybersight.hyperfocus.appblocker.R
import com.cybersight.hyperfocus.appblocker.enforce.BlockOverlay
import com.cybersight.hyperfocus.appblocker.enforce.ForegroundWatcher
import com.cybersight.hyperfocus.appblocker.night.NightGuardService
import com.cybersight.hyperfocus.appblocker.owner.LockController
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup
import com.cybersight.hyperfocus.appblocker.stats.SessionLog

/**
 * Keeps the countdown honest while the screen is off, and ends the session on
 * time without needing an Activity (ARCHITECTURE.md §15 phase 4).
 *
 * The OS holds the lock, not this service — killing it does not unlock the
 * phone. What it protects is the *timer*: without it, a dozing device would
 * only notice the session had expired the next time the user looked at it.
 */
class SessionService : Service() {

    companion object {
        private const val CHANNEL = "focusbro.session"
        private const val NOTIFICATION_ID = 1
        const val ACTION_END = "com.cybersight.hyperfocus.appblocker.END"

        fun sync(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            if (SessionStore.endsAt(context) != 0L) {
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        }

        private fun endPendingIntent(context: Context): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                0,
                Intent(context, SessionService::class.java).setAction(ACTION_END),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

    private var launchRequested = false

    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LockController.launchSession(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = android.content.IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(unlockReceiver, filter)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        ForegroundWatcher.stop(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground FIRST, unconditionally. Android 12 kills a process
        // that was started with startForegroundService and then returns without
        // promoting itself — which is what happened when a session expired
        // between sync() and here, taking the finishSession() path. At boot that
        // crash is silent and looks exactly like "the lock did not come back".
        startForeground(NOTIFICATION_ID, buildNotification(SessionStore.endsAt(this)))

        // Recomputed from SessionStore every time, so a restart after a kill
        // resumes with the correct remaining time rather than a fresh session.
        val remaining = SessionStore.remainingMs(this)
        if (remaining <= 0L) {
            finishSession()
            return START_NOT_STICKY
        }

        com.cybersight.hyperfocus.appblocker.owner.LockReadiness.auditAsync(this)
        scheduleEnd()
        // Device owner blocks at the OS level and needs no watching. Every other
        // tier does, and this is the part that survives a reboot: the boot
        // receiver restarts this service, and this service restarts enforcement.
        android.util.Log.i("FocusBroRoutine", "service active end=${SessionStore.endsAt(this)}")
        LockController.prepare(this)
        if (OwnerSetup.isOwner(this) || !launchRequested) {
            LockController.launchSession(this)
            launchRequested = true
        }
        if (!OwnerSetup.isOwner(this)) ForegroundWatcher.start(this)
        return START_STICKY
    }

    /**
     * AlarmManager rather than a delayed handler: a handler does not fire during
     * doze, which would leave the phone locked until the user next looked at it.
     */
    private fun scheduleEnd() {
        val alarms = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val endsAt = SessionStore.endsAt(this)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, endPendingIntent(this))
        } else {
            // Degraded, and the UI says so per §8 rather than silently drifting.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, endPendingIntent(this))
        }
    }

    private fun finishSession() {
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(endPendingIntent(this))
        val startedAt = SessionStore.startedAt(this)
        if (startedAt > 0L) {
            SessionLog.record(this, startedAt, System.currentTimeMillis() - startedAt)
        }
        ForegroundWatcher.stop(this)
        BlockOverlay.hide(this)
        LockController.release(this)
        SessionStore.clear(this)
        // A session ending at 22:00 hands enforcement of browsers, Reddit and
        // Telegram over to Night Guard, which is independent of it (§24.6).
        NightGuardService.sync(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(endsAt: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Focus session",
                    // LOW: the countdown should be visible, never intrusive (§12).
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Focus session running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(endsAt)
            .build()
    }
}
