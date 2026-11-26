package com.cybersight.hyperfocus.appblocker.shield

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.cybersight.hyperfocus.appblocker.protection.ProtectionPolicy

/**
 * Other VPN apps on the phone (§24.5). Detected by what they declare — a
 * service bound with `android.net.VpnService` — not by name; the name list
 * is a secondary signal. Neither list is what stops them: on device owner
 * `DISALLOW_CONFIG_VPN` means no VPN but FocusBro's can be established, and
 * this is diagnostics plus, for the known ones, a reversible suspension.
 */
object VpnApps {

    fun detected(context: Context): Set<String> {
        val pm = context.packageManager
        val intent = Intent("android.net.VpnService")
        val services = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
            }
        }.getOrDefault(emptyList())
        return services.map { it.serviceInfo.packageName }.toSet() - context.packageName
    }

    fun known(context: Context): Set<String> =
        ProtectionPolicy.knownVpnPackages.filter { installed(context, it) }.toSet()

    fun bypassApps(context: Context): Set<String> =
        ProtectionPolicy.knownBypassPackages.filter { installed(context, it) }.toSet()

    fun status(context: Context): Map<String, Any> {
        val pm = context.packageManager
        fun label(pkg: String) = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val known = known(context)
        return mapOf(
            "detected" to detected(context).sorted().map { mapOf("id" to it, "label" to label(it), "known" to (it in known)) },
            "known" to known.sorted().map { mapOf("id" to it, "label" to label(it)) },
            "bypass" to bypassApps(context).sorted().map { mapOf("id" to it, "label" to label(it)) },
        )
    }

    private fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
}
