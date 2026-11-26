package com.cybersight.hyperfocus.appblocker.night

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The one rule: night = 21:00 <= t < 08:00, local. Every boundary from the
 * brief, both edges, midnight, and the two "phone was off" boot cases.
 */
class NightScheduleTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, s: Int = 0, day: Int = 15, zone: ZoneId = kolkata) =
        ZonedDateTime.of(2026, 9, day, h, m, s, 0, zone)

    @Test
    fun `20-59-59 is day`() = assertFalse(NightSchedule.isNight(at(20, 59, 59)))

    @Test
    fun `21-00-00 is night`() = assertTrue(NightSchedule.isNight(at(21, 0, 0)))

    @Test
    fun `23-59-59 is night`() = assertTrue(NightSchedule.isNight(at(23, 59, 59)))

    @Test
    fun `00-00-00 is night`() = assertTrue(NightSchedule.isNight(at(0, 0, 0)))

    @Test
    fun `05-59-59 is night`() = assertTrue(NightSchedule.isNight(at(5, 59, 59)))

    @Test
    fun `07-59-59 is night`() = assertTrue(NightSchedule.isNight(at(7, 59, 59)))

    @Test
    fun `08-00-00 is day`() = assertFalse(NightSchedule.isNight(at(8, 0, 0)))

    @Test
    fun `boot at 23-00 concludes night`() = assertTrue(NightSchedule.isNight(at(23, 0)))

    @Test
    fun `boot at 03-00 concludes night`() = assertTrue(NightSchedule.isNight(at(3, 0)))

    @Test
    fun `boot at 07-00 concludes night`() = assertTrue(NightSchedule.isNight(at(7, 0)))

    @Test
    fun `next boundary in the afternoon is tonight's start`() {
        assertEquals(at(21, 0), NightSchedule.nextBoundary(at(14, 0)))
    }

    @Test
    fun `next boundary at night is tomorrow's end`() {
        assertEquals(at(8, 0, day = 16), NightSchedule.nextBoundary(at(23, 30)))
    }

    @Test
    fun `next boundary in the small hours is today's end`() {
        assertEquals(at(8, 0), NightSchedule.nextBoundary(at(2, 0)))
    }

    @Test
    fun `exactly at a boundary the next one is the other edge`() {
        assertEquals(at(8, 0, day = 16), NightSchedule.nextBoundary(at(22, 30)))
        assertEquals(at(21, 0), NightSchedule.nextBoundary(at(8, 0)))
    }

    @Test
    fun `follows the local zone, not UTC`() {
        // 23:00 in Kolkata is 17:30 UTC: night locally, day if UTC were used.
        val local = at(23, 0)
        assertTrue(NightSchedule.isNight(local))
        assertFalse(NightSchedule.isNight(local.withZoneSameInstant(ZoneId.of("UTC"))))
    }

    @Test
    fun `a zone change re-evaluates the same instant`() {
        // 05:30 in Kolkata (night) is 02:00 in Berlin (also night) but 20:00
        // the previous evening in Los Angeles (day). Reconcile must follow
        // the new zone rather than a remembered answer.
        val instant = at(5, 30)
        assertTrue(NightSchedule.isNight(instant.withZoneSameInstant(ZoneId.of("Europe/Berlin"))))
        assertFalse(NightSchedule.isNight(instant.withZoneSameInstant(ZoneId.of("America/Los_Angeles"))))
    }

    @Test
    fun `window is independent of the tethering cutoff`() {
        assertEquals(21 * 60, NightSchedule.window.startMinute)
        assertEquals(8 * 60, NightSchedule.window.endMinute)
        assertEquals(22 * 60 + 30, com.cybersight.hyperfocus.appblocker.owner.TetherPolicy.CUTOFF_MINUTE)
    }
    @Test
    fun `new social block begins at 2230 and leaves WhatsApp out`() {
        assertFalse(NightSchedule.socialWindow.contains(at(22, 29, 59)))
        assertTrue(NightSchedule.socialWindow.contains(at(22, 30)))
        assertTrue(NightSchedule.socialWindow.contains(at(5, 59)))
        assertTrue(NightSchedule.socialWindow.contains(at(7, 59)))
        assertFalse(NightSchedule.socialWindow.contains(at(8, 0)))
        val policy = com.cybersight.hyperfocus.appblocker.protection.ProtectionPolicy
        assertTrue("com.instagram.android" in policy.explicitNightPackages)
        assertTrue("com.facebook.katana" in policy.explicitNightPackages)
        assertTrue("com.snapchat.android" in policy.explicitNightPackages)
        assertFalse("com.instagram.android" in policy.socialNightPackages)
        assertFalse("com.facebook.katana" in policy.socialNightPackages)
        assertFalse("com.snapchat.android" in policy.socialNightPackages)
        assertTrue("com.pinterest" in policy.socialNightPackages)
        assertTrue("com.whatsapp" in policy.nightExemptPackages)
        assertFalse("com.whatsapp" in policy.socialNightPackages)
        assertEquals(at(22, 30), NightSchedule.nextBoundary(at(21, 0)))
    }
}
