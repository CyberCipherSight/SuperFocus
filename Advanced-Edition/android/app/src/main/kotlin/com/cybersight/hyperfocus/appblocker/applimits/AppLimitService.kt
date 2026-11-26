package com.cybersight.hyperfocus.appblocker.applimits

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import com.cybersight.hyperfocus.appblocker.BuildConfig
import com.cybersight.hyperfocus.appblocker.MainActivity
import com.cybersight.hyperfocus.appblocker.R
import com.cybersight.hyperfocus.appblocker.enforce.ForegroundWatcher
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup

/** Sampling happens natively, off the UI thread. Alarms and usage history
 * reconcile across death/reboot; an always-running Flutter engine is unnecessary. */
class AppLimitService : Service() {
    companion object {
        @Volatile var running = false
            private set
        fun sync(c: Context) {
            val intent = Intent(c, AppLimitService::class.java)
            if (AppLimits.hasRules(c)) runCatching { c.startForegroundService(intent) }
                .onFailure { Log.e("FocusBroAppLimits", "monitor startup refused", it) }
            else c.stopService(intent)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private val tick = object : Runnable {
        override fun run() {
            AppLimits.executor.execute {
                runCatching { AppLimits.reconcile(applicationContext) }
                    .onFailure { Log.e("FocusBroAppLimits", "monitor failed", it) }
                handler.post {
                    if (!running) return@post
                    if (!AppLimits.hasRules(this@AppLimitService)) stopSelf()
                    else handler.postDelayed(this, 5000)
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("focusbro.limits", "App limits", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 41, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(41, Notification.Builder(this, "focusbro.limits")
            .setSmallIcon(R.drawable.ic_focusbro_monochrome).setContentTitle("Your app limits are active")
            .setContentText(if (BuildConfig.PLAY_EDITION) "Daily allowances renew at 7 AM."
                else "Automatic app breaks are active. Daily allowances renew at 7 AM.").setContentIntent(open).setOngoing(true).build())
        running = true
        if (!started) { started = true; handler.post(tick) }
        if (!OwnerSetup.isOwner(this)) ForegroundWatcher.start(this)
        return START_STICKY
    }
    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        ForegroundWatcher.stop(this)
        super.onDestroy()
    }
}

class AppLimitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLimitService.sync(context)
        val pending = goAsync()
        AppLimits.executor.execute {
            try { AppLimits.reconcile(context.applicationContext) }
            catch (e: Exception) { Log.e("FocusBroAppLimits", "alarm reconcile failed", e) }
            finally { pending.finish() }
        }
    }
}
