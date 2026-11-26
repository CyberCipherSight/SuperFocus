package com.cybersight.hyperfocus.appblocker.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup
import com.cybersight.hyperfocus.appblocker.owner.TetherPolicy
import com.cybersight.hyperfocus.appblocker.protection.Protection

/** Restore native session state and alarms after reboot or replacement. */
class BootReceiver : BroadcastReceiver() {
    private companion object {
        // Several OEMs send a quickboot broadcast instead of BOOT_COMPLETED on a
        // fast restart, and the session silently fails to resume if only the
        // standard action is handled.
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        // Re-assert ownership policy here rather than waiting for the app to be
        // opened. Idempotent by design, and it means uninstall protection is back the moment the phone boots — not the
        // first time the user happens to launch FocusBro.
        OwnerSetup.provision(context)
        RoutineAlarms.reconcile(context, intent.action ?: "boot")
        SessionService.sync(context)
        // The 22:30 tethering cutoff is alarm-driven and alarms do not survive
        // a reboot; reconciling here restores both the alarm and, if the phone
        // was off across the boundary, the restriction itself.
        TetherPolicy.sync(context)
        // Night Guard and Adult Shield reconcile from the clock and the
        // installed packages (§24.7): a phone that was off at 21:00 and boots
        // at 02:00 is restricted here, and the always-on VPN is re-asserted.
        Protection.sync(context)
    }
}
