package com.cybersight.hyperfocus.appblocker.owner

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.cybersight.hyperfocus.appblocker.protection.Protection

/**
 * The device-owner component. Its only structural job is to exist so
 * `adb shell dpm set-device-owner` has something to bind to; the one thing
 * it does is apply the permanent policies the moment ownership arrives,
 * rather than at the next launch (§24.7).
 */
class OwnerReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        OwnerSetup.provision(context)
        Protection.sync(context)
    }
}
