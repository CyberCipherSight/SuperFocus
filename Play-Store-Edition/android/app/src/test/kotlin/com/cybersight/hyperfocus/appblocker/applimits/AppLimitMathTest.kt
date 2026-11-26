package com.cybersight.hyperfocus.appblocker.applimits

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.Duration

class AppLimitMathTest {
    @Test fun `day rolls over exactly at seven not midnight`() {
        val early = ZonedDateTime.parse("2026-10-09T06:59:59+05:30[Asia/Kolkata]")
        assertEquals("2026-10-08", AppLimitMath.dayStart(early).toLocalDate().toString())
        val seven = early.plusSeconds(1)
        assertEquals(seven, AppLimitMath.dayStart(seven))
        assertEquals(seven.toInstant().toEpochMilli(), AppLimitMath.nextReset(early))
        assertEquals(seven.plusDays(1).toInstant().toEpochMilli(), AppLimitMath.nextReset(seven))
    }
    @Test fun `reset uses local seven across DST not twenty four hours`() {
        val start = ZonedDateTime.parse("2026-03-28T07:00:00Z[Europe/London]")
        val next = AppLimitMath.nextReset(start)
        assertEquals(23L, Duration.ofMillis(next - start.toInstant().toEpochMilli()).toHours())
        assertEquals(7, java.time.Instant.ofEpochMilli(next).atZone(start.zone).hour)
    }
    @Test fun `timed and daily blocks release only when both end`() {
        assertTrue(AppLimitMath.blocked(100, 110, 90))
        assertTrue(AppLimitMath.blocked(100, 90, 110))
        assertFalse(AppLimitMath.blocked(100, 100, 100))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `exhausted limit cannot be removed`() { AppLimitMath.effectiveLimit(30, 0, true) }
    @Test(expected = IllegalArgumentException::class)
    fun `exhausted limit cannot be increased`() { AppLimitMath.effectiveLimit(30, 60, true) }
    @Test fun `unexhausted limits remain editable`() { assertEquals(60, AppLimitMath.effectiveLimit(30, 60, false)) }
    @Test fun `readding a removed limit next day does not charge old usage`() {
        assertEquals(10L, AppLimitMath.usageForDay(1, 2, 500, 10))
    }
    @Test fun `same day usage cannot be erased by missing history or reinstalls`() {
        assertEquals(500L, AppLimitMath.usageForDay(2, 2, 500, 0))
        assertEquals(600L, AppLimitMath.usageForDay(2, 2, 500, 600))
    }
}
