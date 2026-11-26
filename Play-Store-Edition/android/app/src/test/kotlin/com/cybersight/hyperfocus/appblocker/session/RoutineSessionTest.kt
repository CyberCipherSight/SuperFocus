package com.cybersight.hyperfocus.appblocker.session

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZonedDateTime

/** Exercise the real durable session transition with an in-memory preference
 * interface. No Android service or clock mutation is needed for these races. */
class RoutineSessionTest {
    private val values = mutableMapOf<String, Any?>()
    private val pending = mutableMapOf<String, Any?>()
    private var writesSucceed = true
    private val editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "putLong", "putBoolean" -> { pending[args!![0] as String] = args[1]; proxy }
            "remove" -> { pending[args!![0] as String] = null; proxy }
            "apply", "commit" -> {
                if (writesSucceed) values.putAll(pending)
                pending.clear()
                if (method.name == "commit") writesSucceed else null
            }
            else -> error("Unexpected editor call ${method.name}")
        }
    } as SharedPreferences.Editor
    private val preferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getLong", "getBoolean" -> values[args!![0]] ?: args[1]
            "edit" -> editor
            else -> error("Unexpected preferences call ${method.name}")
        }
    } as SharedPreferences
    private val context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int) = preferences
    }
    private val routine = Routine("morning", 127, 360, 60, true)
    private val start = ZonedDateTime.now().minusMinutes(20)

    @Test fun `late delivery keeps original end and duplicate never extends it`() {
        assertTrue(SessionStore.startRoutine(context, routine, start))
        val end = start.toInstant().toEpochMilli() + 60 * 60_000L
        assertEquals(end, SessionStore.endsAt(context))
        assertFalse(SessionStore.startRoutine(context, routine, start))
        assertEquals(end, SessionStore.endsAt(context))
    }

    @Test fun `completed occurrence cannot restart after cooldown`() {
        assertTrue(SessionStore.startRoutine(context, routine, start))
        SessionStore.clear(context)
        values["lastEndedAtUtc"] = System.currentTimeMillis() - 120_000
        assertFalse(SessionStore.startRoutine(context, routine, start))
        assertEquals(0L, SessionStore.endsAt(context))
    }

    @Test fun `overlap is consumed and cannot become a queued session`() {
        SessionStore.start(context, 60_000)
        val end = SessionStore.endsAt(context)
        assertFalse(SessionStore.startRoutine(context, routine, start))
        assertEquals(end, SessionStore.endsAt(context))
        SessionStore.clear(context)
        values["lastEndedAtUtc"] = System.currentTimeMillis() - 120_000
        assertFalse(SessionStore.startRoutine(context, routine, start))
    }

    @Test fun `cooldown is preserved`() {
        SessionStore.clear(context)
        assertFalse(SessionStore.startRoutine(context, routine, start))
    }

    @Test fun `expired period cannot start`() {
        assertFalse(SessionStore.startRoutine(context, routine, start.minusHours(2)))
        assertEquals(0L, SessionStore.endsAt(context))
    }

    @Test(expected = IllegalStateException::class)
    fun `failed durable write cannot report successful activation`() {
        writesSucceed = false
        SessionStore.startRoutine(context, routine, start)
    }
}
