package com.cybersight.hyperfocus.appblocker.applimits

import org.junit.Assert.*
import org.junit.Test

class ContinuousBreaksTest {
    private val social = "com.instagram.android"
    private val youtube = "com.google.android.youtube"
    private val base = 1_000_000L
    private fun at(minutes: Int) = base + minutes * 60_000L
    private fun event(at: Long, kind: UsageCounter.Kind, pkg: String = social, activity: String = "Main") =
        UsageCounter.Event(at, kind, pkg, activity)
    private fun resumed(at: Long, pkg: String = social, activity: String = "Main") =
        event(at, UsageCounter.Kind.RESUME, pkg, activity)

    @Test fun `social threshold triggers fifteen minute break and repeated checks do not extend it`() {
        val b = ContinuousBreaks(ContinuousBreaks.RULES)
        b.accept(resumed(base))
        b.finish(at(15) - 1)
        assertNull(b.lockedUntil[social])
        b.finish(at(15))
        assertEquals(at(30), b.lockedUntil[social])
        b.accept(resumed(at(20)))
        b.finish(at(29))
        assertEquals(at(30), b.lockedUntil[social])
    }

    @Test fun `youtube allows thirty minutes and pauses only two minutes`() {
        val b = ContinuousBreaks(ContinuousBreaks.RULES)
        b.accept(resumed(base, youtube))
        b.finish(at(29))
        assertNull(b.lockedUntil[youtube])
        b.finish(at(30))
        assertEquals(at(32), b.lockedUntil[youtube])
    }

    @Test fun `each return after a break starts a new repeating cycle`() {
        val b = ContinuousBreaks(ContinuousBreaks.RULES)
        b.accept(resumed(base))
        b.finish(at(15))
        b.accept(resumed(at(30)))
        b.finish(at(45))
        assertEquals(at(60), b.lockedUntil[social])
    }

    @Test fun `switching to another app ends the previous run`() {
        val b = ContinuousBreaks(ContinuousBreaks.RULES)
        b.accept(resumed(base))
        b.accept(resumed(at(14), "com.example.notes"))
        b.accept(resumed(at(16)))
        b.finish(at(30))
        assertNull(b.lockedUntil[social])
        b.finish(at(31))
        assertEquals(at(46), b.lockedUntil[social])
    }

    @Test fun `activity navigation within an app does not reset its allowance`() {
        val b = ContinuousBreaks(ContinuousBreaks.RULES)
        b.accept(resumed(base))
        b.accept(event(at(14), UsageCounter.Kind.PAUSE))
        b.accept(resumed(at(14) + 500, activity = "Reels"))
        b.accept(event(at(14) + 600, UsageCounter.Kind.PAUSE)) // old activity stops late
        b.finish(at(15))
        assertEquals(at(30), b.lockedUntil[social])
    }

    @Test fun `leaving an app for more than the activity transition gap resets the run`() {
        val b = ContinuousBreaks(ContinuousBreaks.RULES)
        b.accept(resumed(base))
        b.accept(event(at(14), UsageCounter.Kind.PAUSE))
        b.finish(at(16))
        b.accept(resumed(at(17)))
        b.finish(at(31))
        assertNull(b.lockedUntil[social])
    }

    @Test fun `screen off and reboot do not count as continuous use`() {
        for (kind in listOf(UsageCounter.Kind.SCREEN_OFF, UsageCounter.Kind.LOCK, UsageCounter.Kind.REBOOT)) {
            val b = ContinuousBreaks(ContinuousBreaks.RULES)
            b.accept(resumed(base))
            b.accept(event(at(14), kind))
            b.finish(at(40))
            assertNull(b.lockedUntil[social])
        }
    }

    @Test fun `persisted run survives a process restart and fills a missed interval`() {
        val b = ContinuousBreaks(ContinuousBreaks.RULES)
        b.accept(resumed(base))
        b.finish(at(10))
        val restored = ContinuousBreaks(ContinuousBreaks.RULES, b.state, b.lockedUntil)
        restored.finish(at(16))
        assertEquals(at(30), restored.lockedUntil[social])
        val again = ContinuousBreaks(ContinuousBreaks.RULES, restored.state, restored.lockedUntil)
        again.finish(at(31))
        assertEquals(at(30), again.lockedUntil[social])
    }

    @Test fun `rules start counting at activation rather than retroactively blocking old usage`() {
        val b = ContinuousBreaks(ContinuousBreaks.RULES, activeSince = at(60))
        b.accept(resumed(base))
        b.finish(at(60))
        assertNull(b.lockedUntil[social])
        assertEquals(at(75), b.nextThreshold())
    }

    @Test fun `rules only cover requested apps and WhatsApp has no automatic break`() {
        val rules = ContinuousBreaks.RULES
        assertEquals(ContinuousBreaks.Rule(15, 15), rules["com.facebook.katana"])
        assertEquals(ContinuousBreaks.Rule(15, 15), rules["com.reddit.frontpage"])
        assertEquals(ContinuousBreaks.Rule(15, 15), rules["com.snapchat.android"])
        assertFalse("com.whatsapp" in rules)
    }

    @Test fun `foreground app covered by a break begins counting when the cover expires`() {
        val b = ContinuousBreaks(ContinuousBreaks.RULES)
        b.accept(resumed(base))
        b.finish(at(15))
        b.accept(resumed(at(20))) // attempted foreground app remains covered
        b.finish(at(44))
        assertEquals(at(30), b.lockedUntil[social])
        b.finish(at(45))
        assertEquals(at(60), b.lockedUntil[social])
    }
}
