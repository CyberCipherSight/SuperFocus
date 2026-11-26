package com.cybersight.hyperfocus.appblocker.protection

import android.content.Context
import android.content.SharedPreferences

/**
 * What the permanent policies have done, so they can undo exactly that and
 * nothing else (§24.7). Separate from the session store on purpose: a focus
 * session and Night Guard have different lifecycles and must not share state.
 *
 * Package sets are *ours*: only packages FocusBro itself suspended are ever
 * unsuspended by FocusBro. A package suspended by some other policy is never
 * touched.
 */
class ProtectionState(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("focusbro.protection", Context.MODE_PRIVATE)

    // --- Night Guard ---------------------------------------------------------

    /** Suspended through DevicePolicyManager by Night Guard. */
    var nightSuspended: Set<String> by stringSet("nightSuspended")

    /** Night Guard asked for these and the platform refused. Shown, not hidden. */
    var nightFailed: Set<String> by stringSet("nightFailed")

    /** Suspended through `pm suspend` on the root tier. */
    var nightRootSuspended: Set<String> by stringSet("nightRootSuspended")

    /** User additions, on top of [ProtectionPolicy.explicitNightPackages]. */
    var manualNightPackages: Set<String> by stringSet("manualNightPackages")

    var lastNightReconcileAt: Long by long("lastNightReconcileAt")
    var lastNightActive: Boolean by bool("lastNightActive")

    // --- Adult Shield --------------------------------------------------------

    /** Known VPN/bypass apps suspended by Adult Shield on device owner. */
    var shieldSuspended: Set<String> by stringSet("shieldSuspended")
    var shieldFailed: Set<String> by stringSet("shieldFailed")

    var lastShieldApplyAt: Long by long("lastShieldApplyAt")
    var lastShieldError: String by string("lastShieldError")

    // --- delegates -------------------------------------------------------------

    private fun stringSet(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, Set<String>> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Set<String> =
            prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Set<String>) {
            // A fresh HashSet: SharedPreferences may hand back its own instance.
            prefs.edit().putStringSet(key, HashSet(value)).apply()
        }
    }

    private fun bool(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            prefs.getBoolean(key, false)

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    private fun long(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            prefs.getLong(key, 0L)

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Long) {
            prefs.edit().putLong(key, value).apply()
        }
    }

    private fun string(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            prefs.getString(key, "") ?: ""

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }
}
