package com.cybersight.hyperfocus.appblocker.enforce

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Any installed app that registers a CATEGORY_APP_MUSIC launcher activity —
 * resolved by Android's own category, not a maintained list.
 *
 * Deliberately different from [AllowedApps]: that list is curated on purpose
 * (§23.1), because a scheme/category query for payment apps once admitted
 * every UPI app on the phone. Music apps don't carry that risk — a player is
 * a player — so resolving them the same way the dock resolves the dialer or
 * SMS app is the right call here, not a reversal of that lesson.
 */
object MusicApps {
    private val QUERY = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)

    fun installed(context: Context): List<String> =
        context.packageManager
            .queryIntentActivities(QUERY, 0)
            .map { it.activityInfo.packageName }
            .distinct()

    fun labelled(context: Context): List<Map<String, Any>> {
        val pm = context.packageManager
        return installed(context).map { pkg ->
            mapOf(
                "id" to pkg,
                "label" to runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg),
            )
        }
    }
}
