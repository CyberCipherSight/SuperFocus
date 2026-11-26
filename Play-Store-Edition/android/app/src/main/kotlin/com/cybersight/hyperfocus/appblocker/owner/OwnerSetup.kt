package com.cybersight.hyperfocus.appblocker.owner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.cybersight.hyperfocus.appblocker.night.NightGuard
import com.cybersight.hyperfocus.appblocker.root.RootEnforcer
import com.cybersight.hyperfocus.appblocker.shield.ShieldPolicy
import com.cybersight.hyperfocus.appblocker.BuildConfig

/**
 * Device-owner detection and one-time provisioning. See ARCHITECTURE.md §1, §17.1.
 *
 * Everything here is a no-op unless we are actually device owner, so the app
 * degrades to a plain timer rather than crashing on an unprovisioned device
 * (edge case 4).
 */
object OwnerSetup {

    fun admin(context: Context) =
        ComponentName(context.packageName, OwnerReceiver::class.java.name)

    fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isOwner(context: Context): Boolean =
        !BuildConfig.PLAY_EDITION && dpm(context).isDeviceOwnerApp(context.packageName)

    /**
     * Applied once, when ownership is first detected. Idempotent — safe to call
     * on every launch.
     */
    fun provision(context: Context) {
        if (!isOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)

        if (BuildConfig.TESTING_CONNECTIVITY) {
            // Remove only FocusBro's own restrictions. Debugging still requires
            // the user's Android USB/wireless-debugging setup and host approval.
            listOf(UserManager.DISALLOW_CONFIG_TETHERING, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
                UserManager.DISALLOW_DEBUGGING_FEATURES, UserManager.DISALLOW_USB_FILE_TRANSFER)
                .forEach { dpm.clearUserRestriction(admin, it) }
        }

        com.cybersight.hyperfocus.appblocker.shield.PrivateDnsWatcher.start(context)

        // Queue protection before unrelated provisioning; DNS validates on a worker.
        ShieldPolicy.reconcileAsync(context)

        // §17.1: uninstall protection is permanent, not per-session. Lifting it
        // is gated behind the same path as turning FocusBro off entirely.
        dpm.setUninstallBlocked(admin, context.packageName, true)

        // Close the two bypasses that Device Admin could never close (§1).
        dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)

        // Migrate existing installs too: this restriction disables management
        // of EVERY app in AOSP/LineageOS Settings, including uninstall.
        // Self-protection remains package-scoped above.
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ has a package-scoped force-stop/data-clear policy.
            runCatching { dpm.setUserControlDisabledPackages(admin, listOf(context.packageName)) }
                .onFailure { android.util.Log.e("FocusBroPolicy", "Self-protection failed", it) }
        }

        // §2.4: DISALLOW_FACTORY_RESET is deliberately NEVER set. Recovery-mode
        // factory reset is the last escape hatch and must stay open.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grant(context, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // So the in-session Bluetooth toggle needs no prompt (§ enableBluetooth,
        // MainActivity). BLUETOOTH_CONNECT is a runtime permission from API 31.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grant(context, android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun grant(context: Context, permission: String) {
        runCatching {
            dpm(context).setPermissionGrantState(
                admin(context),
                context.packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
    }

    /**
     * Hands device ownership back and makes FocusBro uninstallable again.
     *
     * This is the escape hatch the app owes the user: once device owner is set,
     * neither `adb uninstall` nor `dpm remove-active-admin` can remove it, so
     * the *only* code that can undo it is this. Shipping without it meant the
     * only way off a provisioned phone was a factory reset.
     *
     * Everything is unwound before ownership is dropped, because after
     * clearDeviceOwnerApp none of these calls are permitted any more.
     */
    fun relinquish(context: Context): Boolean {
        if (!isOwner(context)) return true
        val dpm = dpm(context)
        val admin = admin(context)
        val pkg = context.packageName

        com.cybersight.hyperfocus.appblocker.shield.PrivateDnsWatcher.stop()

        // §24.7: the permanent policies go first, while ownership still permits
        // undoing them — always-on VPN and lockdown, the VPN and Private DNS
        // restrictions, and every package suspension FocusBro itself made.
        // Leaving any of these behind would hand back a phone with no
        // networking or with its browsers permanently suspended.
        com.cybersight.hyperfocus.appblocker.applimits.AppLimits.tearDown(context)
        runCatching { ShieldPolicy.tearDown(context) }
        runCatching { NightGuard.tearDown(context) }

        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, pkg) }
        runCatching { dpm.setUninstallBlocked(admin, pkg, false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { dpm.setUserControlDisabledPackages(admin, emptyList()) }
        }
        listOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            // Must be here: leaving it set would hand back a phone whose
            // Settings > Apps is permanently inert, with no owner left to fix it.
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
        ).forEach { runCatching { dpm.clearUserRestriction(admin, it) } }

        return runCatching {
            dpm.clearDeviceOwnerApp(pkg)
            !isOwner(context)
        }.getOrDefault(false)
    }

    /** Capability report for the UI (§8). Never claim more than this says. */
    fun capabilities(context: Context): Map<String, Any> {
        val owner = isOwner(context)
        return mapOf(
            "deviceOwner" to owner,
            "apiLevel" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "statusBarControl" to LockController.statusBarControlWorks,
            "level" to LockController.level(context),
            "pinningEnabled" to LockController.pinningEnabled(context),
            "accessibility" to LockController.accessibilityRunning(),
            "root" to RootEnforcer.available(),
            "adbCommand" to if (BuildConfig.PLAY_EDITION) "" else
                "adb shell dpm set-device-owner ${context.packageName}/" +
                OwnerReceiver::class.java.name,
        )
    }
}
