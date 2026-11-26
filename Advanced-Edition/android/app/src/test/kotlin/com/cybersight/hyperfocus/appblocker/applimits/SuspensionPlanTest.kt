package com.cybersight.hyperfocus.appblocker.applimits

import org.junit.Assert.*
import org.junit.Test
import com.cybersight.hyperfocus.appblocker.protection.SuspensionPlan

class SuspensionPlanTest {
    @Test fun `six AM night release leaves exhausted apps held until seven`() {
        val plan = SuspensionPlan.from(setOf("instagram", "browser"), listOf(emptySet(), setOf("instagram")))
        assertEquals(setOf("instagram"), plan.desired)
        assertEquals(setOf("browser"), plan.release)
    }
    @Test fun `daily reset cannot release an active timed or VPN lock`() {
        val plan = SuspensionPlan.from(setOf("app", "vpn"), listOf(emptySet(), setOf("app"), setOf("vpn")))
        assertTrue(plan.release.isEmpty())
    }
    @Test fun `retry keeps desired failures and only releases our own previous set`() {
        val plan = SuspensionPlan.from(setOf("old"), listOf(setOf("new")))
        assertEquals(setOf("old"), plan.release)
        assertEquals(setOf("new"), plan.desired)
    }
    @Test fun `duplicate reasons do not produce duplicate package commands`() {
        assertEquals(setOf("app"), SuspensionPlan.from(emptySet(), listOf(setOf("app"), setOf("app"))).desired)
    }
}
