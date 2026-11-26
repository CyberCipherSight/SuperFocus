package com.cybersight.hyperfocus.appblocker.root

import android.content.Context

/** The Play build has no shell command implementation. */
@Suppress("UNUSED_PARAMETER")
object RootEnforcer {
    fun available() = false
    fun probeAsync() = Unit
    fun grantAll(context: Context) = false
    fun healAccessibility(context: Context) = Unit
    fun stopApp(packageName: String) = Unit
    fun setSuspended(packageName: String, on: Boolean) = false
    fun setUsbTethering(on: Boolean) = false
    fun setWifi(on: Boolean) = false
    fun setMobileData(on: Boolean) = false
    fun mobileDataEnabled() = false
    fun stopHotspot() = Unit
}
