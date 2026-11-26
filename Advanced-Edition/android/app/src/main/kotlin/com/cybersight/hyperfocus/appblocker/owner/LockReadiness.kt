package com.cybersight.hyperfocus.appblocker.owner

import android.app.ActivityManager
import android.content.Context
import com.cybersight.hyperfocus.appblocker.session.SessionStore
import java.util.concurrent.Executors

/** A permission audit must never be a prerequisite for starting enforcement. */
object LockReadiness {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "focusbro-lock-health") }
    fun missing(status: Map<String, Any>): List<String> {
        val required = mutableListOf("notifications", "exactAlarms", "batteryExempt", "usageStats")
        if (status["deviceOwner"] != true) required += listOf("accessibility", "overlay")
        return required.filter { status[it] != true }
    }
    fun auditAsync(c: Context) {
        val app = c.applicationContext
        executor.execute {
            runCatching {
                val absent = missing(HealthCheck.status(app))
                app.getSharedPreferences("focusbro.lockHealth", Context.MODE_PRIVATE).edit()
                    .putStringSet("missing", absent.toSet()).putLong("checkedAt", System.currentTimeMillis()).apply()
                android.util.Log.i("FocusBroLockHealth", "missing=${absent.joinToString()}")
            }.onFailure { android.util.Log.e("FocusBroLockHealth", "audit failed", it) }
        }
    }
    fun settingsAvailable(c: Context): Boolean =
        !SessionStore.isLocked(c) || (!OwnerSetup.isOwner(c) &&
            c.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE)

    fun status(c: Context): Map<String, Any> = mapOf(
        "missing" to missing(HealthCheck.status(c)),
        "settingsAvailable" to settingsAvailable(c),
    )

    /** Never add Settings to the lock-task allowlist or release the active lock.
     * Accessibility's own settings are also deferred while its guard is active. */
    fun fix(c: Context, id: String): Boolean {
        if (!settingsAvailable(c)) return false
        if (SessionStore.isLocked(c) && id == "accessibility") return false
        return HealthCheck.fix(c, id)
    }

    fun redirectOnce(c: Context): Boolean {
        if (com.cybersight.hyperfocus.appblocker.BuildConfig.PLAY_EDITION) return false
        if (!SessionStore.isLocked(c) || !settingsAvailable(c)) return false
        val p = c.getSharedPreferences("focusbro.lockHealth", Context.MODE_PRIVATE)
        val session = SessionStore.startedAt(c)
        if (p.getLong("redirectedSession", -1) == session) return false
        val id = missing(HealthCheck.status(c)).firstOrNull { it != "accessibility" } ?: return false
        p.edit().putLong("redirectedSession", session).apply()
        return fix(c, id)
    }
}
