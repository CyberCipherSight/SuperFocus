package com.cybersight.hyperfocus.appblocker.session

import com.cybersight.hyperfocus.appblocker.BuildConfig

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.time.ZoneId
import java.time.ZonedDateTime
import com.cybersight.hyperfocus.appblocker.MainActivity
import com.cybersight.hyperfocus.appblocker.R
import com.cybersight.hyperfocus.appblocker.owner.TetherPolicy

/** Native timetable authority. Alarms reconcile stored periods before any UI launch. */
object RoutineAlarms {

    private const val CHANNEL = "focusbro.routine"
    private const val NOTIFICATION_ID = 2
    const val EXTRA_ROUTINE = "routineId"
    const val EXTRA_DURATION = "routineDurationMin"

    /** Cancels and reschedules for whichever routine is next. Idempotent. */
    fun reschedule(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = RoutineStore.nextFire(context)
        if (next == null) {
            alarms.cancel(firePendingIntent(context))
            android.util.Log.i("FocusBroRoutine", "schedule empty")
            return
        }
        val (routine, at) = next
        val triggerAt = at.toInstant().toEpochMilli()
        val intent = firePendingIntent(context, routine)

        // setAlarmClock: the strongest scheduling guarantee Android offers, and
        // the honest declaration of what this is — a user-set alarm.
        val show = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), intent)
            android.util.Log.i("FocusBroRoutine", "scheduled id=${routine.id} at=$triggerAt")
        } catch (e: SecurityException) {
            android.util.Log.e("FocusBroRoutine", "Exact alarm unavailable; using inexact fallback", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
    }

    @Synchronized
    fun reconcile(context: Context, reason: String) {
        android.util.Log.i("FocusBroRoutine", "reconcile reason=$reason now=${System.currentTimeMillis()}")
        try {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            RoutineStore.all(context).filter { it.enabled }.mapNotNull { r ->
                RoutineSchedule.activeOccurrence(now, r.daysMask, r.startMinute, r.durationMin)
                    ?.let { r to it }
            }.sortedWith(compareBy({ it.second.toInstant() }, { it.first.id })).forEach { (r, start) ->
                val started = SessionStore.startRoutine(context, r, start)
                if (started && !com.cybersight.hyperfocus.appblocker.owner.OwnerSetup.isOwner(context)) {
                    runCatching { announce(context, r.id, r.durationMin) }
                        .onFailure { android.util.Log.e("FocusBroRoutine", "notification failed", it) }
                }
                android.util.Log.i("FocusBroRoutine", "occurrence id=${r.id} start=$start accepted=$started end=${SessionStore.endsAt(context)}")
            }
            // Resume even if an earlier delivery died after committing state.
            if (SessionStore.isLocked(context)) {
                SessionService.sync(context)
            }
        } finally {
            reschedule(context)
        }
    }

    private fun firePendingIntent(context: Context, routine: Routine? = null): PendingIntent {
        val intent = Intent(context, RoutineReceiver::class.java)
        routine?.let {
            intent.putExtra(EXTRA_ROUTINE, it.id)
            intent.putExtra(EXTRA_DURATION, it.durationMin)
        }
        return PendingIntent.getBroadcast(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun announce(context: Context, routineId: String, durationMin: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Scheduled focus",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }

        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ROUTINE, routineId)
                .putExtra(EXTRA_DURATION, durationMin),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setContentTitle("Focus session starting")
                .setContentText("$durationMin minutes. Put things down.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(open)
                .apply { if (!BuildConfig.PLAY_EDITION) setFullScreenIntent(open, true) }
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .build()
        )
    }

    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun fullScreenIntentAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java)
            .canUseFullScreenIntent()
    }
}

class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Extras are hints only. Re-read all saved periods so stale alarms,
        // simultaneous starts and edits all pass through one authority.
        RoutineAlarms.reconcile(context, intent.action ?: "alarm")
        TetherPolicy.sync(context)
    }
}
