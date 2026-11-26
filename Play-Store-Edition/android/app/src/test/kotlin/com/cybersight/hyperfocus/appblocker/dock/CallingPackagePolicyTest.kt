package com.cybersight.hyperfocus.appblocker.dock

import org.junit.Assert.*
import org.junit.Test

class CallingPackagePolicyTest {
    @Test fun `Samsung keypad and separate incoming call screen are both allowed`() {
        val result = CallingPackagePolicy.packages("com.samsung.android.dialer", "com.samsung.android.dialer",
            setOf("com.samsung.android.dialer"),
            listOf(CallingPackagePolicy.CallUi("com.samsung.android.incallui", true, true, true)), emptySet())
        assertEquals(setOf("com.samsung.android.dialer", "com.samsung.android.incallui"), result)
    }
    @Test fun `third party default retains preloaded emergency dialer and backend`() {
        val result = CallingPackagePolicy.packages("com.example.dialer", "com.android.dialer", emptySet(),
            emptyList(), setOf("com.android.phone", "com.android.server.telecom"))
        assertTrue(result.containsAll(setOf("com.example.dialer", "com.android.dialer",
            "com.android.phone", "com.android.server.telecom")))
    }
    @Test fun `missing default role falls back to installed dial handlers`() {
        assertEquals(setOf("com.android.dialer", "com.example.otherdialer"),
            CallingPackagePolicy.packages(null, null, setOf("com.android.dialer", "com.example.otherdialer"),
                emptyList(), emptySet()))
    }
    @Test fun `watch companion and services without call UI are not granted app access`() {
        val result = CallingPackagePolicy.packages("phone", null, emptySet(), listOf(
            CallingPackagePolicy.CallUi("watch", true, true, false),
            CallingPackagePolicy.CallUi("unprotected", true, false, true),
            CallingPackagePolicy.CallUi("unrelated", false, true, true)), emptySet())
        assertEquals(setOf("phone"), result)
    }
    @Test fun `selected third party dialer can provide its own protected call UI`() {
        val result = CallingPackagePolicy.packages("thirdparty", null, emptySet(),
            listOf(CallingPackagePolicy.CallUi("thirdparty", false, true, true)), emptySet())
        assertEquals(setOf("thirdparty"), result)
    }
}
