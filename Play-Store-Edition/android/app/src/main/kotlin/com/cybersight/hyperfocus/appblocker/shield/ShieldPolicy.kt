package com.cybersight.hyperfocus.appblocker.shield

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.cybersight.hyperfocus.appblocker.night.NightGuard
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup
import com.cybersight.hyperfocus.appblocker.protection.ProtectionState
import java.util.concurrent.Executors

/** Legacy class name retained for the bridge. Enforces VPN bypass prevention
 * and Night Guard clock protection, with locked family filtering DNS.
 */
object ShieldPolicy {

    /** Permanent resolver enforced while FocusBro is device owner. */
    const val DNS_HOST = PrivateDnsPolicy.HOST

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "focusbro-shield").apply { isDaemon = true }
    }

    fun reconcileAsync(context: Context) {
        val app = context.applicationContext
        executor.execute { runCatching { reconcile(app) }.onFailure { ProtectionState(app).lastShieldError = it.toString() } }
    }

    /** Whether the permanent policy is what holds `DISALLOW_CONFIG_DATE_TIME` right now. */
    fun ownsClockLock(context: Context): Boolean = OwnerSetup.isOwner(context)

    fun reconcile(context: Context) {
        val state = ProtectionState(context)
        if (!OwnerSetup.isOwner(context)) return
        val dpm = OwnerSetup.dpm(context)
        val admin = OwnerSetup.admin(context)
        val errors = PrivateDnsPolicy.enforce(context).toMutableList()

        // 1. No VPN at all: the user cannot configure one, and any always-on
        //    package a previous build set (FocusBro's own) is cleared.
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN) }
            .onFailure { errors += "DISALLOW_CONFIG_VPN: ${it.message}" }
        if (runCatching { dpm.getAlwaysOnVpnPackage(admin) }.getOrNull() != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dpm.setAlwaysOnVpnPackage(admin, null, false, null)
                } else {
                    dpm.setAlwaysOnVpnPackage(admin, null, false)
                }
            }.onFailure { errors += "clear always-on: ${it.message}" }
        }

        // 3. Clock: automatic time and zone, and the user cannot change either.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setAutoTimeEnabled(admin, true)
                dpm.setAutoTimeZoneEnabled(admin, true)
            } else {
                @Suppress("DEPRECATION") dpm.setAutoTimeRequired(admin, true)
            }
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
        }.onFailure { errors += "clock: ${it.message}" }

        // 4. Known VPN clients and bypass apps: suspended, reversibly, recorded.
        reconcileSuspensions(context, state)

        state.lastShieldApplyAt = System.currentTimeMillis()
        state.lastShieldError = errors.joinToString("; ")
        errors.forEach { android.util.Log.e("FocusBroShield", it) }
    }

    private fun privateDnsPinned(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val dpm = OwnerSetup.dpm(context)
        val admin = OwnerSetup.admin(context)
        return runCatching {
            dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                dpm.getGlobalPrivateDnsHost(admin) == DNS_HOST
        }.getOrDefault(false)
    }

    private fun reconcileSuspensions(context: Context, state: ProtectionState) {
        val dpm = OwnerSetup.dpm(context)
        val admin = OwnerSetup.admin(context)
        val safety = NightGuard.safetyAllowlist(context)
        val want = (VpnApps.known(context) + VpnApps.bypassApps(context)) - safety
        val failed = com.cybersight.hyperfocus.appblocker.protection.OwnerSuspensions.apply(context, "vpn", want)
        state.shieldSuspended = want - failed
        state.shieldFailed = failed
    }

    /** Relinquish: remove exactly what this policy added, and nothing else. */
    fun tearDown(context: Context) {
        val state = ProtectionState(context)
        if (OwnerSetup.isOwner(context)) {
            val dpm = OwnerSetup.dpm(context)
            val admin = OwnerSetup.admin(context)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dpm.setAlwaysOnVpnPackage(admin, null, false, null)
                } else {
                    dpm.setAlwaysOnVpnPackage(admin, null, false)
                }
            }
            com.cybersight.hyperfocus.appblocker.protection.OwnerSuspensions.apply(context, "vpn", emptySet())
            listOf(UserManager.DISALLOW_CONFIG_VPN, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                .forEach { runCatching { dpm.clearUserRestriction(admin, it) } }
            // Preserve the user's DNS choice when ownership is relinquished.
        }
        state.shieldSuspended = emptySet()
        state.shieldFailed = emptySet()
    }

    // --- observation -------------------------------------------------------------

    fun status(context: Context): Map<String, Any> {
        val owner = OwnerSetup.isOwner(context)
        val state = ProtectionState(context)
        val dpm = OwnerSetup.dpm(context)
        val admin = OwnerSetup.admin(context)
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager

        val privateDnsHost = if (owner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { dpm.getGlobalPrivateDnsHost(admin) }.getOrNull().orEmpty()
        } else ""

        return mapOf(
            "deviceOwner" to owner,
            "dnsHost" to DNS_HOST,
            "privateDnsPinned" to (owner && privateDnsPinned(context)),
            "privateDnsHost" to privateDnsHost,
            "privateDnsLocked" to (owner && um.hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)),
            "vpnConfigurationLocked" to (owner && um.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)),
            "clockLocked" to (owner && um.hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME)),
            "vpnApps" to VpnApps.status(context),
            "shieldSuspended" to state.shieldSuspended.toList().sorted(),
            "shieldFailed" to state.shieldFailed.toList().sorted(),
            "lastApplyAt" to state.lastShieldApplyAt,
            "lastError" to state.lastShieldError,
        )
    }
}
