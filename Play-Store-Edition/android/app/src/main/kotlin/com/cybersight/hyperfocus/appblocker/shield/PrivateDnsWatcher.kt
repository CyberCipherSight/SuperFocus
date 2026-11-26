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
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val thread = HandlerThread("focusbro-dns-watch").apply { start() }
        val handler = Handler(thread.looper)
        val check = object : Runnable {
            override fun run() {
                if (!OwnerSetup.isOwner(app)) return
                val errors = runCatching { PrivateDnsPolicy.enforce(app) }
                    .getOrElse { listOf("Private DNS: ${it.message}") }
                if (errors.isNotEmpty()) {
                    ProtectionState(app).lastShieldError = errors.joinToString("; ")
                    errors.forEach { android.util.Log.e("FocusBroDnsWatch", it) }
                    handler.removeCallbacks(this)
                    handler.postDelayed(this, 30_000)
                }
            }
        }
        fun queue() {
            handler.removeCallbacks(check)
            handler.post(check)
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = queue()
        }
        // Use the actual settings rather than an OEM screen's displayed choice.
        listOf("private_dns_mode", "private_dns_specifier").forEach {
            app.contentResolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer)
        }
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = queue()
        }
        runCatching {
            cm.registerDefaultNetworkCallback(networkCallback)
        }.onFailure { android.util.Log.w("FocusBroDnsWatch", "Network retry callback unavailable", it) }
        stopAction = {
            app.contentResolver.unregisterContentObserver(observer)
            runCatching { cm.unregisterNetworkCallback(networkCallback) }
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        queue()
    }
}
