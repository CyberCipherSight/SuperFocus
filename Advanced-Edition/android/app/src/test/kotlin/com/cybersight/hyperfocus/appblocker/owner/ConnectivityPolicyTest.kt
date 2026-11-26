package com.cybersight.hyperfocus.appblocker.owner

import org.junit.Assert.*
import org.junit.Test

class ConnectivityPolicyTest {
    @Test fun `testing mode never applies the nightly tethering restriction`() {
        assertFalse(ConnectivityPolicy.tetherRestricted(true, true))
        assertFalse(ConnectivityPolicy.tetherRestricted(false, true))
        assertFalse(ConnectivityPolicy.stopHotspot(true))
    }
    @Test fun `regular mode retains night cutoff and session hotspot shutdown`() {
        assertTrue(ConnectivityPolicy.tetherRestricted(true, false))
        assertFalse(ConnectivityPolicy.tetherRestricted(false, false))
        assertTrue(ConnectivityPolicy.stopHotspot(false))
    }
    @Test fun `usb tether commands request primary functions without an invalid adb mask`() {
        assertEquals("rndis", ConnectivityPolicy.usbFunctions(true))
        assertEquals("", ConnectivityPolicy.usbFunctions(false))
        for (on in listOf(true, false)) assertFalse("adb" in ConnectivityPolicy.usbFunctions(on))
    }
}
