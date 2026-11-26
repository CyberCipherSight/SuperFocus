package com.cybersight.hyperfocus.appblocker.shield

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup
import com.cybersight.hyperfocus.appblocker.protection.ProtectionState

/** Some OEM Settings screens ignore DISALLOW_CONFIG_PRIVATE_DNS. Reconcile
 * actual setting changes without inspecting screen contents or blocking Settings.
 * This is reactive enforcement: it cannot promise zero time with DNS disabled.
 */
object PrivateDnsWatcher {
    private var started = false
    private var stopAction: (() -> Unit)? = null

    @Synchronized
    fun stop() {
        stopAction?.invoke()
        stopAction = null
        started = false
    }

    @Synchronized
    fun start(context: Context) = Unit // No automatic DNS changes in public preview.
}
