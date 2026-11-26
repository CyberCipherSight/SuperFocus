package com.cybersight.hyperfocus.appblocker.protection

import android.content.Context
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup

/** DPM identifies the suspending admin, not the feature. Releasing one feature
 * must never undo another feature's suspension from that same admin. */
object OwnerSuspensions {
    private val sources = listOf("night", "vpn", "limits")

    @Synchronized
    fun apply(context: Context, source: String, wanted: Set<String>): Set<String> {
        require(source in sources)
        if (!OwnerSetup.isOwner(context)) return wanted
        val p = context.getSharedPreferences("focusbro.suspensions", Context.MODE_PRIVATE)
        if (!p.getBoolean("migrated", false)) {
            val old = ProtectionState(context)
            check(p.edit().putStringSet("night", old.nightSuspended)
                .putStringSet("vpn", old.shieldSuspended)
                .putStringSet("applied", old.nightSuspended + old.shieldSuspended)
                .putBoolean("migrated", true).commit())
        }
        check(p.edit().putStringSet(source, wanted).commit())
        val previous = p.getStringSet("applied", emptySet()).orEmpty().toSet()
        val plan = SuspensionPlan.from(previous, sources.map { p.getStringSet(it, emptySet()).orEmpty() })
        val union = plan.desired
        val dpm = OwnerSetup.dpm(context)
        val admin = OwnerSetup.admin(context)
        // Persist intended applications first, so process death cannot orphan a
        // suspension between the binder call and the preferences write.
        check(p.edit().putStringSet("applied", previous + union).commit())
        val failed = if (union.isEmpty()) emptySet() else runCatching {
            dpm.setPackagesSuspended(admin, union.toTypedArray(), true).toSet()
        }.getOrDefault(union)
        val release = plan.release
        val releaseFailed = if (release.isEmpty()) emptySet() else runCatching {
            dpm.setPackagesSuspended(admin, release.toTypedArray(), false).toSet()
        }.getOrDefault(release).filter { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }.toSet()
        check(p.edit().putStringSet("applied", union + releaseFailed).commit())
        return (failed intersect wanted) + releaseFailed
    }

    @Synchronized
    fun clear(context: Context) {
        sources.forEach { apply(context, it, emptySet()) }
    }
}
