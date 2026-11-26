package com.cybersight.hyperfocus.appblocker

/** Pure state classification, independently testable on the JVM. */
object PlayPermissionPolicy {
    fun status(granted: Boolean, consent: Boolean = true, operational: Boolean = true): String =
        when {
            !consent -> "consent_required"
            !granted -> "not_granted"
            !operational -> "not_operational"
            else -> "ready"
        }
    fun notificationAction(sdk: Int, granted: Boolean, asked: Boolean, rationale: Boolean): String =
        if (sdk >= 33 && !granted && (!asked || rationale)) "runtime" else "settings"
}
