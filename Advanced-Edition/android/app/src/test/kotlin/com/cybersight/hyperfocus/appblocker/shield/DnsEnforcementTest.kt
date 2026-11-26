package com.cybersight.hyperfocus.appblocker.shield

import org.junit.Assert.*
import org.junit.Test

class DnsEnforcementTest {
    private class Fake : DnsEnforcement.Platform {
        var pinned = false
        var locked = false
        var failLock = false
        var failPin = false
        var ignorePin = false
        var pins = 0
        override fun lock() { check(!failLock); locked = true }
        override fun isPinned() = pinned
        override fun pin() { pins++; check(!failPin); if (!ignorePin) pinned = true }
    }
    @Test fun appliesAndLocks() {
        val p = Fake()
        assertTrue(DnsEnforcement.run(p).isEmpty())
        assertTrue(p.locked && p.pinned)
    }
    @Test fun alreadyPinnedDoesNotRepeatNetworkValidation() {
        val p = Fake().apply { pinned = true }
        assertTrue(DnsEnforcement.run(p).isEmpty())
        assertEquals(0, p.pins)
        assertTrue(p.locked)
    }
    @Test fun failedProviderRetriesOnNextReconciliation() {
        val p = Fake().apply { failPin = true }
        assertFalse(DnsEnforcement.run(p).isEmpty())
        p.failPin = false
        assertTrue(DnsEnforcement.run(p).isEmpty())
        assertEquals(2, p.pins)
    }
    @Test fun lockFailureDoesNotPreventProviderApplication() {
        val p = Fake().apply { failLock = true }
        assertFalse(DnsEnforcement.run(p).isEmpty())
        assertTrue(p.pinned)
    }
    @Test fun verifiesProviderReadback() {
        val p = Fake().apply { ignorePin = true }
        assertFalse(DnsEnforcement.run(p).isEmpty())
    }
    @Test fun restoresProviderAfterSettingsTurnsItOff() {
        val p = Fake()
        assertTrue(DnsEnforcement.run(p).isEmpty())
        p.pinned = false
        assertTrue(DnsEnforcement.run(p).isEmpty())
        assertTrue(p.pinned && p.locked)
        assertEquals(2, p.pins)
    }
}
