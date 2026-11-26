package com.cybersight.hyperfocus.appblocker.night

import com.cybersight.hyperfocus.appblocker.BuildConfig

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import com.cybersight.hyperfocus.appblocker.MainActivity
import com.cybersight.hyperfocus.appblocker.R
import com.cybersight.hyperfocus.appblocker.dock.DockResolver
import com.cybersight.hyperfocus.appblocker.enforce.Allowlist
import com.cybersight.hyperfocus.appblocker.enforce.ForegroundWatcher
import com.cybersight.hyperfocus.appblocker.owner.LockController
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup
import com.cybersight.hyperfocus.appblocker.protection.ProtectionPolicy
import com.cybersight.hyperfocus.appblocker.protection.ProtectionState
import com.cybersight.hyperfocus.appblocker.root.RootEnforcer
import com.cybersight.hyperfocus.appblocker.stats.UsageBridge
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Night Guard: browsers, Reddit and Telegram are unavailable from 21:00 to
 * 06:00 every day (ARCHITECTURE.md §24.6). Not a device lock — every other
 * app, calls, payments and the emergency dock keep working — and entirely
 * independent of focus sessions and of Adult Shield.
 *
 * The same shape as [TetherPolicy]: an exact alarm at each boundary for
 * punctuality, and [reconcile], which derives the correct state from the
 * clock and is what actually guarantees correctness. A phone booted at 03:00
 * is restricted the moment the boot receiver runs; one booted at 07:00 is
 * released the same way, with no dependence on having seen yesterday's alarm.
 *
 * Only packages FocusBro suspended are ever unsuspended by FocusBro
 * ([ProtectionState.nightSuspended]); a package some other policy suspended
 * is not touched. Refusals are recorded and shown, never papered over.
 */
object NightGuard {

    private const val REQUEST_CODE = 5
    private val lock = Any()

    fun isActiveNow(): Boolean = !BuildConfig.PLAY_EDITION && NightSchedule.isNightNow()

    // --- what is restricted --------------------------------------------------

    /**
     * Packages that must never be suspended, whatever they classify as. The
     * session allowlist already resolves the launcher, keyboard, Settings,
     * SystemUI, the emergency dock and the user's allowed apps; the platform
     * components below are added because suspending them breaks the phone.
     */
    fun safetyAllowlist(context: Context): Set<String> {
        val pm = context.packageManager
        val set = Allowlist.packages(context).toMutableSet()
        set += DockResolver.packages(context)
        set += listOf(
            "android", "com.android.systemui", "com.android.phone",
            "com.android.server.telecom", "com.android.shell", "com.android.emergency",
            "com.android.cellbroadcastreceiver", "com.android.providers.settings",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.google.android.gms", "com.android.vending",
        )
        runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_INSTALL_PACKAGE), 0)?.activityInfo?.packageName
        }.getOrNull()?.let(set::add)
        set += context.packageName
        return set
    }

    /** Installed packages Night Guard restricts right now, with the safety allowlist removed. */
    fun targets(context: Context): Set<String> {
        val pm = context.packageManager
        val safety = safetyAllowlist(context)
        val state = ProtectionState(context)
        val social = if (NightSchedule.socialWindow.contains(ZonedDateTime.now()))
            ProtectionPolicy.socialNightPackages else emptySet()
        val explicit = (ProtectionPolicy.explicitNightPackages + state.manualNightPackages + social)
            .filter { isInstalled(pm, it) }
        val browsers = BrowserClassifier.browsers(context, safety).keys
        return (explicit + browsers).toSet() - safety - ProtectionPolicy.nightExemptPackages
    }

    /** For the accessibility tier and the watcher: should this foreground app be ejected? */
    fun isBlocked(context: Context, packageName: String): Boolean =
        isActiveNow() && packageName in targets(context)

    // --- reconcile -----------------------------------------------------------

    /**
     * Applies whatever the clock says. Idempotent. Device owner suspends at
     * the package level; root suspends through `pm`; the accessibility tier
     * ejects on sight via [NightGuardService] and the accessibility service.
     */
    fun reconcile(context: Context) {
        val active = isActiveNow()
        val state = ProtectionState(context)
        synchronized(lock) {
            when {
                OwnerSetup.isOwner(context) -> reconcileOwner(context, state, active)
                RootEnforcer.available() -> reconcileRoot(context, state, active)
                else -> {
                    // Nothing to persist: enforcement is on sight only.
                    state.nightSuspended = emptySet()
                    state.nightFailed = emptySet()
                }
            }
            state.lastNightActive = active
            state.lastNightReconcileAt = System.currentTimeMillis()
        }
        NightGuardService.sync(context)
    }

    private fun reconcileOwner(context: Context, state: ProtectionState, active: Boolean) {
        val want = if (active) targets(context) else emptySet()
        val failed = com.cybersight.hyperfocus.appblocker.protection.OwnerSuspensions.apply(context, "night", want)
        state.nightSuspended = want - failed
        state.nightFailed = failed
    }

    /**
     * Root tier: `pm suspend`, reversible and recorded exactly like the owner
     * path. Runs off the calling thread because `su` blocks. Every change is
     * verified by reading the package state back; an unverified suspension is
     * not counted, and the on-sight tier covers it meanwhile.
     */
    private fun reconcileRoot(context: Context, state: ProtectionState, active: Boolean) {
        val pm = context.packageManager
        Thread {
            synchronized(lock) {
                if (active) {
                    val want = targets(context)
                    val done = state.nightRootSuspended.toMutableSet()
                    for (pkg in want - done) if (RootEnforcer.setSuspended(pkg, true)) done += pkg
                    for (pkg in done - want) if (RootEnforcer.setSuspended(pkg, false)) done -= pkg
                    state.nightRootSuspended = done.filter { isInstalled(pm, it) }.toSet()
                    state.nightFailed = (want - done)
                } else {
                    val done = state.nightRootSuspended.toMutableSet()
                    for (pkg in done.toList()) {
                        if (!isInstalled(pm, pkg) || RootEnforcer.setSuspended(pkg, false)) done -= pkg
                    }
                    state.nightRootSuspended = done
                    state.nightFailed = emptySet()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    // --- alarms --------------------------------------------------------------

    /** Same alarm strategy as the tether cutoff: setAlarmClock, which OEMs honour. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context)
        alarms.cancel(pending)
        val at = NightSchedule.nextBoundary(ZonedDateTime.now(ZoneId.systemDefault()))
            .toInstant().toEpochMilli()
        val show = PendingIntent.getActivity(
            context, REQUEST_CODE, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending) }
            .onFailure { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
    }

    fun alarmScheduled(context: Context): Boolean {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return runCatching { alarms.nextAlarmClock != null }.getOrDefault(false) ||
            PendingIntent.getBroadcast(
                context, REQUEST_CODE, Intent(context, NightGuardReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) != null
    }

    fun sync(context: Context) {
        if (BuildConfig.PLAY_EDITION) return
        reconcile(context)
        schedule(context)
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, NightGuardReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // --- undo ------------------------------------------------------------------

    /** Relinquish path: releases every suspension FocusBro holds and the alarm. */
    fun tearDown(context: Context) {
        val state = ProtectionState(context)
        synchronized(lock) {
            if (OwnerSetup.isOwner(context)) {
                com.cybersight.hyperfocus.appblocker.protection.OwnerSuspensions.apply(context, "night", emptySet())
            }
            if (state.nightRootSuspended.isNotEmpty()) {
                val held = state.nightRootSuspended
                Thread { held.forEach { RootEnforcer.setSuspended(it, false) } }.start()
            }
            state.nightSuspended = emptySet()
            state.nightRootSuspended = emptySet()
            state.nightFailed = emptySet()
        }
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(context))
        NightGuardService.stop(context)
    }

    // --- observation -------------------------------------------------------------

    fun tier(context: Context): String = when {
        OwnerSetup.isOwner(context) -> "owner"
        RootEnforcer.available() -> "root"
        LockController.accessibilityRunning() || UsageBridge.granted(context) -> "accessibility"
        else -> "advisory"
    }

    fun status(context: Context): Map<String, Any> {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val state = ProtectionState(context)
        val pm = context.packageManager
        val safety = safetyAllowlist(context)
        val active = NightSchedule.isNight(now)
        val owner = OwnerSetup.isOwner(context)
        val dpm = OwnerSetup.dpm(context)
        val admin = OwnerSetup.admin(context)

        fun label(pkg: String) = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        fun suspendedNow(pkg: String): Boolean = when {
            owner -> runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
            else -> pkg in state.nightRootSuspended
        }

        val browsers = BrowserClassifier.browsers(context, safety).values.map {
            mapOf(
                "id" to it.packageName, "label" to label(it.packageName),
                "verdict" to it.verdict.name, "reasons" to it.reasons,
                "suspended" to suspendedNow(it.packageName),
            )
        }
        val explicit = (ProtectionPolicy.explicitNightPackages + state.manualNightPackages + ProtectionPolicy.socialNightPackages).map {
            mapOf(
                "id" to it, "label" to label(it), "installed" to isInstalled(pm, it),
                "manual" to (it in state.manualNightPackages),
                "blockedNow" to isBlocked(context, it),
                "social" to (it in ProtectionPolicy.socialNightPackages),
                "suspended" to suspendedNow(it),
            )
        }
        val targets = targets(context)
        val enforced = when {
            owner -> targets.all { suspendedNow(it) }
            RootEnforcer.available() -> targets.all { it in state.nightRootSuspended }
            else -> false
        }
        return mapOf(
            "active" to active,
            "tier" to tier(context),
            "startMinute" to NightSchedule.window.startMinute,
            "endMinute" to NightSchedule.window.endMinute,
            "socialStartMinute" to NightSchedule.socialWindow.startMinute,
            "nextChangeUtc" to NightSchedule.nextBoundary(now).toInstant().toEpochMilli(),
            "browsers" to browsers,
            "explicit" to explicit,
            "targets" to targets.toList().sorted(),
            "suspended" to (if (owner) state.nightSuspended else state.nightRootSuspended).toList().sorted(),
            "failed" to state.nightFailed.toList().sorted(),
            "alarmScheduled" to alarmScheduled(context),
            "stateMatchesClock" to (state.lastNightActive == active && (!active || enforced || !owner)),
            "fullyEnforced" to (!active || enforced),
            "watcherRunning" to NightGuardService.running,
            "lastReconcileAt" to state.lastNightReconcileAt,
        )
    }

    private fun isInstalled(pm: PackageManager, pkg: String) =
        runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
}

/**
 * Boundary alarm, plus clock and timezone changes — a moved clock is exactly
 * when the window must be re-evaluated, and those broadcasts are exempt from
 * the manifest-receiver ban.
 */
class NightGuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = NightGuard.sync(context)
}

/**
 * Hosts the foreground watcher through the night on the tiers that have no
 * package-level suspension. Device owner never starts this: suspension holds
 * with none of our processes alive.
 */
class NightGuardService : Service() {

    companion object {
        private const val CHANNEL = "focusbro.night"
        private const val NOTIFICATION_ID = 3

        @Volatile var running = false
            private set

        fun sync(context: Context) {
            val wanted = NightGuard.isActiveNow() && !OwnerSetup.isOwner(context) &&
                UsageBridge.granted(context)
            if (wanted) {
                runCatching {
                    context.startForegroundService(Intent(context, NightGuardService::class.java))
                }
            } else {
                stop(context)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NightGuardService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote first, unconditionally — see SessionService for why.
        startForeground(NOTIFICATION_ID, notification())
        if (!NightGuard.isActiveNow() || OwnerSetup.isOwner(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        ForegroundWatcher.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ForegroundWatcher.stop(this)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Night Guard", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val end = ProtectionPolicy.NIGHT_END_HOUR
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Night Guard active until ${"%d:00".format(end)}")
            .setContentText("Browsers and night-blocked social apps are off until 8 AM.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
