package com.cybersight.hyperfocus.appblocker.applimits

/** Event-based consecutive foreground use. Internal activity changes in an app
 * preserve the run; another app, screen off, locking or reboot ends it. */
class ContinuousBreaks(
    private val rules: Map<String, Rule>,
    initial: State = State(),
    locks: Map<String, Long> = emptyMap(),
    private val activeSince: Long = 0,
) {
    data class Rule(val useMinutes: Int, val breakMinutes: Int) {
        val useMs get() = useMinutes * 60_000L
        val breakMs get() = breakMinutes * 60_000L
    }
    data class State(
        val pkg: String = "", val activity: String = "",
        val startedAt: Long = 0, val pausedAt: Long = 0,
        val interactive: Boolean = true, val unlocked: Boolean = true,
    )
    var state = initial
        private set
    val lockedUntil = locks.toMutableMap()

    fun accept(event: UsageCounter.Event) {
        val at = maxOf(event.at, activeSince)
        advance(at)
        when (event.kind) {
            UsageCounter.Kind.RESUME -> {
                val sameRun = state.pkg == event.pkg && state.startedAt > 0 &&
                    (state.pausedAt == 0L || at - state.pausedAt <= ACTIVITY_GAP_MS)
                val canUse = event.pkg in rules && state.interactive && state.unlocked &&
                    (lockedUntil[event.pkg] ?: 0) <= at
                state = state.copy(pkg = event.pkg, activity = event.activity, pausedAt = 0,
                    startedAt = if (!canUse) 0 else if (sameRun) state.startedAt else at)
            }
            UsageCounter.Kind.PAUSE -> if (state.pkg == event.pkg && state.activity == event.activity) {
                state = state.copy(pausedAt = at)
            }
            UsageCounter.Kind.SCREEN_OFF -> state = State(interactive = false, unlocked = state.unlocked)
            UsageCounter.Kind.LOCK -> state = State(interactive = state.interactive, unlocked = false)
            UsageCounter.Kind.SCREEN_ON -> state = state.copy(interactive = true)
            UsageCounter.Kind.UNLOCK -> state = state.copy(unlocked = true)
            UsageCounter.Kind.REBOOT -> state = State()
        }
    }

    private fun advance(at: Long) {
        val rule = rules[state.pkg] ?: return
        if (!state.interactive || !state.unlocked) return
        val previousBreakEnd = lockedUntil[state.pkg] ?: 0
        // On the overlay fallback an attempted app can remain foreground while
        // covered. Removing the cover need not produce another RESUME event.
        if (state.startedAt <= 0 && state.pausedAt == 0L && previousBreakEnd > 0 && previousBreakEnd <= at) {
            state = state.copy(startedAt = previousBreakEnd)
        }
        if (state.startedAt <= 0) return
        val end = if (state.pausedAt > 0) minOf(at, state.pausedAt) else at
        val trigger = state.startedAt + rule.useMs
        if (end >= trigger) {
            lockedUntil[state.pkg] = maxOf(lockedUntil[state.pkg] ?: 0, trigger + rule.breakMs)
            state = State(interactive = state.interactive, unlocked = state.unlocked)
        }
    }

    fun finish(now: Long) {
        advance(now)
        if (state.pausedAt > 0 && now - state.pausedAt > ACTIVITY_GAP_MS) {
            state = State(interactive = state.interactive, unlocked = state.unlocked)
        }
    }

    fun nextThreshold(): Long? = rules[state.pkg]?.takeIf {
        state.startedAt > 0 && state.pausedAt == 0L && state.interactive && state.unlocked
    }?.let { state.startedAt + it.useMs }

    companion object {
        const val ACTIVITY_GAP_MS = 1000L
        private val social = Rule(15, 15)
        val RULES = mapOf(
            "com.facebook.katana" to social, "com.facebook.lite" to social,
            "com.reddit.frontpage" to social, "com.instagram.android" to social,
            "com.instagram.lite" to social, "com.snapchat.android" to social,
            "com.google.android.youtube" to Rule(30, 2),
        )
    }
}
