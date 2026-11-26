package com.cybersight.hyperfocus.appblocker.shield

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup

/** Called only by ShieldPolicy's worker: setting a resolver performs network I/O. */
object PrivateDnsPolicy {
    const val HOST = "family-filter-dns.cleanbrowsing.org"

    @Synchronized
    fun enforce(context: Context): List<String> {
        if (!OwnerSetup.isOwner(context)) return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return listOf("Private DNS requires Android 10 or later")
        val dpm = OwnerSetup.dpm(context)
        val admin = OwnerSetup.admin(context)
        return DnsEnforcement.run(object : DnsEnforcement.Platform {
            override fun lock() {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                check(dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)) {
                    "Android did not lock Private DNS"
                }
            }
            override fun isPinned() =
                dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                    dpm.getGlobalPrivateDnsHost(admin) == HOST
            override fun pin() {
                val result = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, HOST)
                check(result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    "Android refused the family DNS provider (code $result); will retry"
                }
            }
        })
    }
}
