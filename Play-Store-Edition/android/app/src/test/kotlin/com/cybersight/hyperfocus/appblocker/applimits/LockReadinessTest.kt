package com.cybersight.hyperfocus.appblocker.applimits

import org.junit.Assert.*
import org.junit.Test
import com.cybersight.hyperfocus.appblocker.owner.LockReadiness

class LockReadinessTest {
    @Test fun `owner does not require accessibility or overlay to lock`() {
        val missing = LockReadiness.missing(mapOf("deviceOwner" to true,
            "notifications" to true, "exactAlarms" to true,
            "batteryExempt" to true, "usageStats" to true))
        assertTrue(missing.isEmpty())
    }
    @Test fun `fallback reports missing enforcement permissions`() {
        val missing = LockReadiness.missing(mapOf("deviceOwner" to false))
        assertTrue("accessibility" in missing)
        assertTrue("overlay" in missing)
        assertTrue("exactAlarms" in missing)
    }
}
