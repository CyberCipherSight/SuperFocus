package com.cybersight.hyperfocus.appblocker.enforce

import org.junit.Assert.*
import org.junit.Test

class BlockPresentationTest {
    @Test fun noticeRemainsBrieflyOnLauncherOnly() {
        assertTrue(BlockPresentation.retainOnLauncher(true, 4_999))
        assertFalse(BlockPresentation.retainOnLauncher(true, 5_000))
        assertFalse(BlockPresentation.retainOnLauncher(false, 100))
    }
    @Test fun overlappingLimitsShowTheLastDeadline() {
        val result = BlockPresentation.limits(100, 500, 300, 200)
        assertEquals(500L, result.until)
        assertTrue(result.message.contains("Several"))
    }
    @Test fun anExpiredDailyLimitDoesNotHideAnActiveBreak() {
        val result = BlockPresentation.limits(100, 50, 0, 200)
        assertEquals(200L, result.until)
        assertTrue(result.message.contains("automatic break"))
    }
    @Test fun unreadableUsageDoesNotInventAReleaseTime() {
        val result = BlockPresentation.limits(100, 0, 0, 0)
        assertEquals(0L, result.until)
        assertTrue(result.message.contains("needs attention"))
    }
}
