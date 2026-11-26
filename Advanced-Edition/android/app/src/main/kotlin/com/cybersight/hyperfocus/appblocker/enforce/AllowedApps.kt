package com.cybersight.hyperfocus.appblocker.enforce

import android.content.Context

/** No preconfigured personal apps in the public candidate.
 * Essential system roles are resolved separately by DockResolver.
 */
object AllowedApps {
    fun installed(context: Context): List<String> = emptyList()
    fun labelled(context: Context): List<Map<String, Any>> = emptyList()
}
