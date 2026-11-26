package com.cybersight.hyperfocus.appblocker
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayPermissionPolicyTest {
    @Test fun missingConsentIsNotReadyEvenWithGrant() {
        assertEquals("consent_required", PlayPermissionPolicy.status(true,false))
    }
    @Test fun enabledButDisconnectedIsNotReady() {
        assertEquals("not_operational", PlayPermissionPolicy.status(true,true,false))
    }
    @Test fun revokedPermissionIsNotReady() {
        assertEquals("not_granted", PlayPermissionPolicy.status(false,true,true))
    }
    @Test fun actualGrantAndConsentAndServiceAreReady() {
        assertEquals("ready", PlayPermissionPolicy.status(true,true,true))
    }
    @Test fun notificationRequestForEverySupportedApi() {
        for (sdk in 28..37) {
            assertEquals(if (sdk >= 33) "runtime" else "settings", PlayPermissionPolicy.notificationAction(sdk,false,false,false))
            assertEquals("settings", PlayPermissionPolicy.notificationAction(sdk,false,true,false))
            assertEquals(if (sdk >= 33) "runtime" else "settings", PlayPermissionPolicy.notificationAction(sdk,false,true,true))
            assertEquals("settings", PlayPermissionPolicy.notificationAction(sdk,true,true,false))
        }
    }
}
