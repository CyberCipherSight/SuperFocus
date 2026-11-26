package com.cybersight.hyperfocus.appblocker.enforce

import android.content.Context

/** Play edition has no developer-defined personal allowlist. */
object AllowedApps {
    fun installed(context: Context): List<String> = emptyList()
    fun labelled(context: Context): List<Map<String, Any>> = emptyList()
}
