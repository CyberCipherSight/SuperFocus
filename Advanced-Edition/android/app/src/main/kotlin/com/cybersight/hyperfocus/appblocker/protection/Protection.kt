package com.cybersight.hyperfocus.appblocker.protection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.cybersight.hyperfocus.appblocker.enforce.Allowlist
import com.cybersight.hyperfocus.appblocker.night.BrowserClassifier
import com.cybersight.hyperfocus.appblocker.night.NightGuard
import com.cybersight.hyperfocus.appblocker.shield.ShieldPolicy
import com.cybersight.hyperfocus.appblocker.dock.CallingApps
import com.cybersight.hyperfocus.appblocker.owner.LockController
import com.cybersight.hyperfocus.appblocker.session.SessionStore
import android.telecom.TelecomManager

/**
 * The one entry point for "make reality match policy" (§24.7). Called on
 * boot, on launch, on package events, on the Night Guard alarm, on clock and
 * zone changes, and when device ownership arrives. Each policy reconciles
 * from current state, so calling this too often is merely cheap, and calling
 * it after a missed alarm is what makes the alarm optional.
 */
object Protection {

    fun sync(context: Context) {
        NightGuard.sync(context)
        ShieldPolicy.reconcileAsync(context)
        com.cybersight.hyperfocus.appblocker.applimits.AppLimits.requestSync(context)
    }

    private val packageFilter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addAction(Intent.ACTION_PACKAGE_CHANGED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        addDataScheme("package")
    }

    private val dynamicReceiver = PackageEventReceiver()
    private val dialerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            CallingApps.invalidate()
            Allowlist.invalidate()
            if (SessionStore.isLocked(context)) LockController.refreshAllowedApps(context)
            Protection.sync(context)
        }
    }

    /**
     * Package broadcasts are not delivered to manifest receivers since API 26,
     * so [FocusBroApp] registers for them at runtime for the life of the
     * process.
     */
    fun registerPackageReceiver(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts are delivered regardless of the export flag;
            // the flag is mandatory for the call itself on API 34.
            context.registerReceiver(dynamicReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(dialerReceiver, IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(dynamicReceiver, packageFilter)
            context.registerReceiver(dialerReceiver, IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED))
        }
        true
    }.getOrDefault(false)

    fun unregisterPackageReceiver(context: Context) {
        runCatching { context.unregisterReceiver(dynamicReceiver) }
        runCatching { context.unregisterReceiver(dialerReceiver) }
    }
}

/**
 * Install, update, removal: classify again and apply the current policy. An
 * app installed at 23:10 is suspended before its first launch; an update at
 * night does not shed the suspension; a removal drops it from the record.
 */
class PackageEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Our own update is handled by BootReceiver (MY_PACKAGE_REPLACED).
        if (intent.data?.schemeSpecificPart == context.packageName) return
        BrowserClassifier.invalidate()
        Allowlist.invalidate()
        CallingApps.invalidate()
        if (SessionStore.isLocked(context)) LockController.refreshAllowedApps(context)
        Protection.sync(context)
    }
}
