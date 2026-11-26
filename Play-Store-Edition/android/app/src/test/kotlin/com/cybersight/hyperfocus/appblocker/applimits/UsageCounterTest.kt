package com.cybersight.hyperfocus.appblocker.applimits

import org.junit.Assert.*
import org.junit.Test
import com.cybersight.hyperfocus.appblocker.applimits.UsageCounter.Kind.*

class UsageCounterTest {
    private fun event(at: Long, kind: UsageCounter.Kind, activity: String = "main", pkg: String = "app") =
        UsageCounter.Event(at, kind, pkg, activity)
    @Test fun `continues active usage through seven but clips earlier time`() {
        val counter = UsageCounter(100, 200)
        counter.accept(event(50, RESUME))
        counter.accept(event(150, PAUSE))
        assertEquals(50L, counter.finish()["app"])
    }
    @Test fun `overlapping activities count the app once`() {
        val counter = UsageCounter(0, 100)
        counter.accept(event(10, RESUME, "one"))
        counter.accept(event(20, RESUME, "two"))
        counter.accept(event(30, PAUSE, "one"))
        counter.accept(event(50, PAUSE, "two"))
        assertEquals(40L, counter.finish()["app"])
        assertTrue(counter.foreground().isEmpty())
    }
    @Test fun `screen off and keyguard time are not charged`() {
        val counter = UsageCounter(0, 100)
        counter.accept(event(0, RESUME))
        counter.accept(event(10, SCREEN_OFF))
        counter.accept(event(10, LOCK))
        counter.accept(event(50, SCREEN_ON))
        counter.accept(event(80, UNLOCK))
        assertEquals(30L, counter.finish()["app"])
    }
    @Test fun `shutdown closes usage without charging powered off hours`() {
        val counter = UsageCounter(0, 100)
        counter.accept(event(0, RESUME))
        counter.accept(event(20, REBOOT))
        assertEquals(20L, counter.finish()["app"])
    }
    @Test fun `replaying history produces same usage after process death`() {
        fun read(): Map<String, Long> {
            val c = UsageCounter(0, 100)
            listOf(event(10, RESUME), event(40, PAUSE), event(70, RESUME)).forEach(c::accept)
            return c.finish()
        }
        assertEquals(read(), read())
        assertEquals(60L, read()["app"])
    }
    @Test fun `multi window counts each foreground app independently`() {
        val c = UsageCounter(0, 100)
        c.accept(event(0, RESUME, pkg = "first"))
        c.accept(event(50, RESUME, pkg = "second"))
        assertEquals(mapOf("first" to 100L, "second" to 50L), c.finish())
    }
}
