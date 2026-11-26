package com.cybersight.hyperfocus.appblocker.enforce

/** Presentation only: this never changes a limit or releases a suspension. */
data class LimitPresentation(val title: String, val message: String, val until: Long, val portrait: Int)

object BlockPresentation {
    /** Retain only on the safe launcher, never over calls or another allowed app. */
    fun retainOnLauncher(isLauncher: Boolean, elapsedMs: Long): Boolean =
        isLauncher && elapsedMs in 0 until 5_000L

    fun limits(now: Long, daily: Long, manual: Long, repeating: Long): LimitPresentation {
        val active = listOf(daily, manual, repeating).count { it > now }
        val until = maxOf(daily, manual, repeating)
        if (active > 1) return LimitPresentation("A little space to reset.",
            "Several app limits are active. The last of these limits ends at", until, 2)
        return when {
            daily > now -> LimitPresentation("That’s today’s chapter.",
                "Your daily allowance is used up. This allowance resets at", daily, 1)
            manual > now -> LimitPresentation("A pause, on purpose.",
                "Your timed app lock ends at", manual, 2)
            repeating > now -> LimitPresentation("Stretch. Breathe. Reset.",
                "Your automatic break ends at", repeating, 2)
            else -> LimitPresentation("This app is taking a break.",
                "Open FocusBro to see which app rule needs attention.", 0, 4)
        }
    }
}
