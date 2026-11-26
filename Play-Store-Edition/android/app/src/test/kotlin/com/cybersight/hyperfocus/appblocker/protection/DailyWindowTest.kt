package com.cybersight.hyperfocus.appblocker.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyWindowTest {

    private val zone = ZoneId.of("Europe/London")
    private fun at(h: Int, m: Int, day: Int = 15) = ZonedDateTime.of(2026, 6, day, h, m, 0, 0, zone)

    @Test
    fun `window inside one day`() {
        val w = DailyWindow(9 * 60, 17 * 60)
        assertFalse(w.contains(at(8, 59)))
        assertTrue(w.contains(at(9, 0)))
        assertTrue(w.contains(at(16, 59)))
        assertFalse(w.contains(at(17, 0)))
        assertEquals(at(9, 0), w.nextBoundary(at(3, 0)))
        assertEquals(at(17, 0), w.nextBoundary(at(12, 0)))
        assertEquals(at(9, 0, day = 16), w.nextBoundary(at(18, 0)))
    }

    @Test
    fun `window across midnight`() {
        val w = DailyWindow(22 * 60, 5 * 60)
        assertTrue(w.contains(at(22, 0)))
        assertTrue(w.contains(at(0, 0)))
        assertTrue(w.contains(at(4, 59)))
        assertFalse(w.contains(at(5, 0)))
        assertFalse(w.contains(at(21, 59)))
        assertEquals(at(5, 0, day = 16), w.nextBoundary(at(23, 0)))
        assertEquals(at(5, 0), w.nextBoundary(at(1, 0)))
        assertEquals(at(22, 0), w.nextBoundary(at(5, 0)))
    }

    @Test
    fun `next boundary is strictly after now`() {
        val w = DailyWindow(22 * 60, 5 * 60)
        assertTrue(w.nextBoundary(at(22, 0)).isAfter(at(22, 0)))
        assertTrue(w.nextBoundary(at(5, 0)).isAfter(at(5, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty window is refused`() {
        DailyWindow(60, 60)
    }
}
