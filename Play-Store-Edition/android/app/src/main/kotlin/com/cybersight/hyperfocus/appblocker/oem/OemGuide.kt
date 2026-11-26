package com.cybersight.hyperfocus.appblocker.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Per-OEM background-execution guidance (ARCHITECTURE.md §9.2, edge cases 31-33).
 *
 * These settings do not decide whether the lock holds — the OS holds it. They
 * decide whether the countdown stays accurate and whether routines fire on
 * time. Reference: dontkillmyapp.com.
 */
object OemGuide {

    private data class Guide(
        val name: String,
        val steps: List<String>,
        val component: ComponentName?,
    )

    private fun componentOf(pkg: String, cls: String) = ComponentName(pkg, cls)

    private val guides: Map<String, Guide> = mapOf(
        "xiaomi" to Guide(
            "Xiaomi / Redmi / POCO",
            listOf(
                "Security app → Permissions → Autostart → enable FocusBro",
                "Settings → Apps → FocusBro → Battery saver → No restrictions",
                "Settings → Apps → FocusBro → Other permissions → enable " +
                    "\"Display pop-up windows while running in background\"",
            ),
            componentOf(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        ),
        "samsung" to Guide(
            "Samsung",
            listOf(
                "Settings → Battery → Background usage limits → Never sleeping apps → add FocusBro",
                "Settings → Apps → FocusBro → Battery → Unrestricted",
                "Device Care → turn off \"Put unused apps to sleep\"",
            ),
            null,
        ),
        "oneplus" to Guide(
            "OnePlus",
            listOf(
                "Settings → Battery → Battery optimization → FocusBro → Don't optimize",
                "Settings → Apps → FocusBro → Battery → Allow background activity",
                "Recents → lock the FocusBro card so it is not cleared",
            ),
            componentOf(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
        ),
        "oppo" to Guide(
            "OPPO / Realme (ColorOS)",
            listOf(
                "Settings → Battery → App battery management → FocusBro → allow background running",
                "Phone Manager → Privacy permissions → Startup manager → enable FocusBro",
            ),
            componentOf(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
        ),
        "vivo" to Guide(
            "vivo / iQOO (Funtouch/OriginOS)",
            listOf(
                "Settings → Battery → High background power consumption → allow FocusBro",
                "iManager → App manager → Autostart manager → enable FocusBro",
            ),
            componentOf(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
        ),
        "huawei" to Guide(
            "Huawei / Honor",
            listOf(
                "Settings → Apps → FocusBro → Battery → App launch → Manage manually, all enabled",
                "Phone Manager → Protected apps → enable FocusBro",
            ),
            componentOf(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        ),
    )

    private val stock = Guide(
        "Stock Android",
        listOf("Settings → Apps → FocusBro → Battery → Unrestricted"),
        null,
    )

    private fun guide(): Guide {
        val m = Build.MANUFACTURER.lowercase()
        return guides.entries.firstOrNull { m.contains(it.key) }?.value
            ?: when {
                m.contains("redmi") || m.contains("poco") -> guides.getValue("xiaomi")
                m.contains("realme") -> guides.getValue("oppo")
                m.contains("honor") -> guides.getValue("huawei")
                m.contains("iqoo") -> guides.getValue("vivo")
                else -> stock
            }
    }

    fun describe(context: Context): Map<String, Any> {
        val g = guide()
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "name" to g.name,
            "steps" to g.steps,
            // Only offer the shortcut if the component actually exists on this
            // ROM — these move between OEM releases and a dead intent is worse
            // than no button.
            "hasShortcut" to (g.component?.let { resolves(context, it) } ?: false),
        )
    }

    fun openSettings(context: Context): Boolean {
        val component = guide().component ?: return false
        if (!resolves(context, component)) return false
        val intent = Intent().setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun resolves(context: Context, component: ComponentName): Boolean =
        runCatching {
            context.packageManager.resolveActivity(
                Intent().setComponent(component), 0
            ) != null
        }.getOrDefault(false)
}
