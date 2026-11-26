package com.cybersight.hyperfocus.appblocker
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayModePolicyTest {
    @Test fun basicNeverEnforces() {
        assertEquals("basic", PlayModePolicy.effective("basic", true, true, true))
    }
    @Test fun reminderDoesNotRequireAccessibility() {
        assertEquals("reminder", PlayModePolicy.effective("reminder", false, true, true))
    }
    @Test fun reminderNeedsUsageAndAlert() {
        assertEquals("basic", PlayModePolicy.effective("reminder", true, false, true))
        assertEquals("basic", PlayModePolicy.effective("reminder", true, true, false))
    }
    @Test fun strictDegradesWhenAccessIsRevoked() {
        assertEquals("strict", PlayModePolicy.effective("strict", true, false, false))
        assertEquals("reminder", PlayModePolicy.effective("strict", false, true, true))
        assertEquals("basic", PlayModePolicy.effective("strict", false, false, true))
    }
}
