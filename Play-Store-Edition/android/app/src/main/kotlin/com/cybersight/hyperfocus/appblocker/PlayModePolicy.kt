package com.cybersight.hyperfocus.appblocker

/** Pure capability resolution. Missing permission never implies protection. */
object PlayModePolicy {
    fun effective(selected: String, accessibility: Boolean, usage: Boolean, alert: Boolean): String =
        when {
            selected == "strict" && accessibility -> "strict"
            selected in setOf("strict", "reminder") && usage && alert -> "reminder"
            else -> "basic"
        }
}
