package com.cybersight.hyperfocus.appblocker.session

import android.content.Context
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Routines, owned natively (ARCHITECTURE.md §11).
 *
 * The alarm that starts a routine fires with no Flutter engine alive, so the
 * schedule has to be readable from Kotlin alone. Dart writes; Kotlin reads.
 *
 * Encoded as one line per routine rather than JSON: five fields, no nesting,
 * and org.json is stubbed out under JVM unit tests.
 */
data class Routine(
    val id: String,
    val daysMask: Int,
    val startMinute: Int,
    val durationMin: Int,
    val enabled: Boolean,
) {
    init {
        require(id.isNotBlank() && id.none { it == '|' || it == '\n' || it == '\r' })
        require(daysMask in 0..127 && startMinute in 0..1439 && durationMin in 1..180)
    }

    fun encode() = "$id|$daysMask|$startMinute|$durationMin|${if (enabled) 1 else 0}"

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "daysMask" to daysMask,
        "startMinute" to startMinute,
        "durationMin" to durationMin,
        "enabled" to enabled,
    )

    companion object {
        fun decode(line: String): Routine? {
            val f = line.split("|")
            if (f.size != 5) return null
            return runCatching {
                Routine(f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4] == "1")
            }.getOrNull()
        }
    }
}

object RoutineStore {

    private const val PREFS = "focusbro.routines"
    private const val KEY = "routines"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Routine> =
        prefs(context).getString(KEY, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { Routine.decode(it) }

    fun replaceAll(context: Context, routines: List<Routine>) {
        require(routines.map { it.id }.distinct().size == routines.size)
        check(prefs(context).edit()
            .putString(KEY, routines.joinToString("\n") { it.encode() })
            .commit()) { "Could not save timetable" }
    }

    /** The soonest upcoming fire across every enabled routine, or null. */
    fun nextFire(context: Context, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())):
        Pair<Routine, ZonedDateTime>? =
        all(context)
            .filter { it.enabled }
            .mapNotNull { r ->
                RoutineSchedule.nextOccurrence(now, r.daysMask, r.startMinute)?.let { r to it }
            }
            .minByOrNull { it.second }
}
