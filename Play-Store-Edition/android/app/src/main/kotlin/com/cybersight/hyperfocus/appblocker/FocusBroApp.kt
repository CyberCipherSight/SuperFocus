package com.cybersight.hyperfocus.appblocker

import android.app.Application
import com.cybersight.hyperfocus.appblocker.protection.Protection

class FocusBroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // ponytail: the process is the receiver's host now that there is no
        // always-on VPN service. If it is killed, the next boot/launch/night
        // alarm reconcile catches up; DISALLOW_CONFIG_VPN holds regardless.
        Protection.registerPackageReceiver(this)
        com.cybersight.hyperfocus.appblocker.shield.PrivateDnsWatcher.start(this)
    }
}
