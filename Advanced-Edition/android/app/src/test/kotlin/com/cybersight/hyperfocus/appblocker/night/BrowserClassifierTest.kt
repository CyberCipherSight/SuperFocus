package com.cybersight.hyperfocus.appblocker.night

import com.cybersight.hyperfocus.appblocker.night.BrowserClassifier.Signals
import com.cybersight.hyperfocus.appblocker.night.BrowserClassifier.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision only; the Android signal gathering is exercised on a device. */
class BrowserClassifierTest {

    @Test
    fun `handles all web URIs with a launcher entry is a confirmed browser`() {
        val c = BrowserClassifier.decide("x", Signals(handlesAllWebUris = true, hasLauncherActivity = true))
        assertEquals(Verdict.CONFIRMED_BROWSER, c.verdict)
        assertTrue(c.reasons.single().contains("no host constraint"))
    }

    @Test
    fun `an app with deep links to its own hosts is not a browser`() {
        // Reddit, banks, shops: they handle https URLs, but only for their hosts.
        val c = BrowserClassifier.decide("x", Signals(handlesAllWebUris = false, hasLauncherActivity = true))
        assertEquals(Verdict.NOT_BROWSER, c.verdict)
    }

    @Test
    fun `a headless URL handler without a launcher entry is not a browser`() {
        val c = BrowserClassifier.decide("x", Signals(handlesAllWebUris = true, hasLauncherActivity = false))
        assertEquals(Verdict.NOT_BROWSER, c.verdict)
    }

    @Test
    fun `browser category and default handler each confirm`() {
        assertEquals(Verdict.CONFIRMED_BROWSER, BrowserClassifier.decide("x", Signals(declaresBrowserCategory = true)).verdict)
        assertEquals(Verdict.CONFIRMED_BROWSER, BrowserClassifier.decide("x", Signals(isDefaultWebHandler = true)).verdict)
    }

    @Test
    fun `seed list alone is known, not confirmed`() {
        assertEquals(Verdict.KNOWN_BROWSER, BrowserClassifier.decide("x", Signals(inSeedList = true)).verdict)
    }

    @Test
    fun `safety allowlist wins over every signal`() {
        val c = BrowserClassifier.decide(
            "x",
            Signals(isDefaultWebHandler = true, declaresBrowserCategory = true, handlesAllWebUris = true,
                hasLauncherActivity = true, inSeedList = true, onSafetyAllowlist = true),
        )
        assertEquals(Verdict.NOT_BROWSER, c.verdict)
    }
}
