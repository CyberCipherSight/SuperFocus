package com.cybersight.hyperfocus.appblocker.protection

/** Only packages with no remaining reason may be released by this admin. */
data class SuspensionPlan(val desired: Set<String>, val release: Set<String>) {
    companion object {
        fun from(applied: Set<String>, reasons: Collection<Set<String>>): SuspensionPlan {
            val desired = reasons.flatten().toSet()
            return SuspensionPlan(desired, applied - desired)
        }
    }
}
