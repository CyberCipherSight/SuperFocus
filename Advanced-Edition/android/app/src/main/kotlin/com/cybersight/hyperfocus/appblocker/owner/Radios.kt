package com.cybersight.hyperfocus.appblocker.owner

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import com.cybersight.hyperfocus.appblocker.root.RootEnforcer

/**
 * The radios a session may need to reach: headphones, and the internet.
 *
 * Settings and the quick-settings shade are both unreachable under lock task,
 * so without these the only way to a toggle is to end the session. None of it
 * loosens the lock — the allowlist decides what may run, not whether a radio
 * is powered.
 */
object Radios {

    private fun adapter(context: Context) =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun wifi(context: Context) =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * BLUETOOTH_CONNECT is self-granted at provisioning time, so this needs no
     * prompt — which matters, because a permission dialog cannot be answered
     * from inside lock task.
     */
    @Suppress("DEPRECATION")
    fun setBluetooth(context: Context, on: Boolean): Boolean = runCatching {
        val a = adapter(context) ?: return false
        if (on) a.enable() else a.disable()
    }.getOrDefault(false)

    /**
     * setWifiEnabled is a no-op for ordinary apps from API 29 onward. Device
     * owner is one of the documented exemptions, which is why this works here
     * and would not in an unprovisioned build; root covers that case.
     */
    fun setWifi(context: Context, on: Boolean): Boolean {
        val direct = runCatching { wifi(context)?.setWifiEnabled(on) == true }.getOrDefault(false)
        return if (direct) true else RootEnforcer.setWifi(on)
    }

    /** No public API toggles mobile data, device owner included. Root or nothing. */
    fun setMobileData(on: Boolean): Boolean = RootEnforcer.setMobileData(on)

    /** Current state, plus whether mobile data can be toggled at all. */
    fun status(context: Context): Map<String, Any> = mapOf(
        "bluetooth" to runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false),
        "wifi" to runCatching { wifi(context)?.isWifiEnabled == true }.getOrDefault(false),
        "mobileData" to RootEnforcer.mobileDataEnabled(),
        "mobileDataControllable" to RootEnforcer.available(),
    )
}
