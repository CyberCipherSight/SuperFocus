package com.cybersight.hyperfocus.appblocker.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The cutoff window crosses midnight, which is where this kind of code
 * reliably goes wrong: a naive `start <= now < end` comparison never restricts
 * anything after 00:00.
 */
class TetherPolicyTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, day: Int = 15) =
        ZonedDateTime.of(2026, 9, day, h, m, 0, 0, kolkata)

    @Test
    fun `permitted through the day`() {
        assertFalse(TetherPolicy.restrictedAt(at(6, 0)))
        assertFalse(TetherPolicy.restrictedAt(at(12, 0)))
        assertFalse(TetherPolicy.restrictedAt(at(22, 29)))
    }

    @Test
    fun `restricted from the cutoff, across midnight, until release`() {
        assertTrue(TetherPolicy.restrictedAt(at(22, 30)))
        assertTrue(TetherPolicy.restrictedAt(at(23, 59)))
        assertTrue(TetherPolicy.restrictedAt(at(0, 0)))
        assertTrue(TetherPolicy.restrictedAt(at(5, 59)))
    }

    @Test
    fun `next boundary during the day is tonight's cutoff`() {
        assertEquals(at(22, 30), TetherPolicy.nextBoundary(at(14, 0)))
        assertEquals(at(22, 30), TetherPolicy.nextBoundary(at(22, 29)))
    }

    @Test
    fun `next boundary late at night is tomorrow's release`() {
        assertEquals(at(6, 0, day = 16), TetherPolicy.nextBoundary(at(22, 30)))
        assertEquals(at(6, 0, day = 16), TetherPolicy.nextBoundary(at(23, 45)))
    }

    @Test
    fun `next boundary in the small hours is today's release`() {
        assertEquals(at(6, 0), TetherPolicy.nextBoundary(at(0, 30)))
        assertEquals(at(6, 0), TetherPolicy.nextBoundary(at(5, 59)))
    }

    @Test
    fun `exactly at release the next boundary is the cutoff, not release again`() {
        // Strictly after: firing at 06:00 must not reschedule itself for 06:00.
        assertEquals(at(22, 30), TetherPolicy.nextBoundary(at(6, 0)))
    }
}
