package com.cybersight.hyperfocus.appblocker.owner

/** Pure decisions used by the separate connectivity-testing build. */
object ConnectivityPolicy {
    fun tetherRestricted(insideNightWindow: Boolean, testing: Boolean) = insideNightWindow && !testing
    fun stopHotspot(testing: Boolean) = !testing
    // ADB is managed separately by Android. Adding it to setFunctions is
    // rejected by AOSP; request only a valid primary function and leave the
    // existing debugging setting alone.
    fun usbFunctions(tetherOn: Boolean): String = if (tetherOn) "rndis" else ""
}
