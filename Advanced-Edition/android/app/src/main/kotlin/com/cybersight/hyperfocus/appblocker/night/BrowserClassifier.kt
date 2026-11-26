package com.cybersight.hyperfocus.appblocker.night

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.cybersight.hyperfocus.appblocker.protection.ProtectionPolicy

/**
 * Which installed packages are browsers (§24.6).
 *
 * "Handles an https URL" is not the test — Reddit, banking apps and every
 * app with deep links do that. The platform's own definition is used
 * instead: an activity that handles *all* web URIs with no host constraint
 * (`ResolveInfo.handleAllWebDataURI`), which is exactly how Android builds
 * its own default-browser candidate list. Declaring `CATEGORY_APP_BROWSER`
 * or being the current default handler count too. The seed list is a
 * floor for OEM browsers that hide those signals, never the mechanism.
 *
 * Every verdict records its reasons, so diagnostics can show *why* an app
 * was or was not classified rather than just the outcome.
 */
object BrowserClassifier {

    enum class Verdict { CONFIRMED_BROWSER, KNOWN_BROWSER, NOT_BROWSER }

    data class Signals(
        val isDefaultWebHandler: Boolean = false,
        val declaresBrowserCategory: Boolean = false,
        val handlesAllWebUris: Boolean = false,
        val hasLauncherActivity: Boolean = false,
        val inSeedList: Boolean = false,
        val onSafetyAllowlist: Boolean = false,
    )

    data class Classification(
        val packageName: String,
        val verdict: Verdict,
        val reasons: List<String>,
    ) {
        val isBrowser get() = verdict != Verdict.NOT_BROWSER
    }

    // --- pure --------------------------------------------------------------

    /** The decision, with no Android in it, so it can be unit-tested. */
    fun decide(packageName: String, s: Signals): Classification {
        val reasons = mutableListOf<String>()
        if (s.onSafetyAllowlist) {
            return Classification(packageName, Verdict.NOT_BROWSER, listOf("safety allowlist wins"))
        }
        if (s.isDefaultWebHandler) reasons += "default handler for web URLs"
        if (s.declaresBrowserCategory) reasons += "declares CATEGORY_APP_BROWSER"
        if (s.handlesAllWebUris && s.hasLauncherActivity) {
            reasons += "handles all web URIs (no host constraint) and has a launcher entry"
        }
        if (reasons.isNotEmpty()) return Classification(packageName, Verdict.CONFIRMED_BROWSER, reasons)

        if (s.inSeedList) {
            return Classification(packageName, Verdict.KNOWN_BROWSER, listOf("on the known-browser seed list"))
        }
        val why = when {
            s.handlesAllWebUris -> "handles all web URIs but has no launcher entry"
            else -> "no browser signal"
        }
        return Classification(packageName, Verdict.NOT_BROWSER, listOf(why))
    }

    // --- Android ---------------------------------------------------------------

    private const val CACHE_MS = 120_000L
    @Volatile private var cache: Map<String, Classification> = emptyMap()
    @Volatile private var cachedAt = 0L

    fun invalidate() {
        cachedAt = 0L
    }

    /** Every installed package that classifies as a browser, with reasons. */
    fun browsers(context: Context, safetyAllowlist: Set<String>): Map<String, Classification> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS && cache.isNotEmpty()) return cache
        cache = classifyAll(context, safetyAllowlist)
        cachedAt = now
        return cache
    }

    fun classify(context: Context, packageName: String, safetyAllowlist: Set<String>): Classification =
        browsers(context, safetyAllowlist)[packageName]
            ?: decide(packageName, signals(context, packageName, safetyAllowlist))

    private fun classifyAll(context: Context, safety: Set<String>): Map<String, Classification> {
        val pm = context.packageManager
        val candidates = mutableSetOf<String>()
        candidates += allWebHandlers(pm)
        candidates += browserCategory(pm)
        candidates += ProtectionPolicy.knownBrowserSeeds.filter { isInstalled(pm, it) }
        defaultWebHandler(pm)?.let(candidates::add)
        candidates -= context.packageName
        return candidates.associateWith { decide(it, signals(context, it, safety)) }
            .filterValues { it.isBrowser }
    }

    private fun signals(context: Context, pkg: String, safety: Set<String>): Signals {
        val pm = context.packageManager
        return Signals(
            isDefaultWebHandler = defaultWebHandler(pm) == pkg,
            declaresBrowserCategory = pkg in browserCategory(pm),
            handlesAllWebUris = pkg in allWebHandlers(pm),
            hasLauncherActivity = pm.getLaunchIntentForPackage(pkg) != null,
            inSeedList = pkg in ProtectionPolicy.knownBrowserSeeds,
            onSafetyAllowlist = pkg in safety || pkg == context.packageName,
        )
    }

    private val webIntent get() = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
        .addCategory(Intent.CATEGORY_BROWSABLE)

    /**
     * Packages whose VIEW filter has no host constraint — the platform's own
     * browser test (`IntentFilter.handleAllWebDataURI`, which is hidden, so
     * it is recomputed here from the resolved filter: BROWSABLE, a web
     * scheme, and no data authority).
     */
    private fun allWebHandlers(pm: PackageManager): Set<String> = runCatching {
        pm.queryIntentActivities(webIntent, PackageManager.MATCH_ALL or PackageManager.GET_RESOLVED_FILTER)
            .filter { r ->
                val f = r.filter ?: return@filter false
                f.hasCategory(Intent.CATEGORY_BROWSABLE) &&
                    (f.hasDataScheme("http") || f.hasDataScheme("https")) &&
                    f.countDataAuthorities() == 0
            }
            .map { it.activityInfo.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    private fun browserCategory(pm: PackageManager): Set<String> = runCatching {
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0,
        ).map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    private fun defaultWebHandler(pm: PackageManager): String? = runCatching {
        pm.resolveActivity(webIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            // "android" is the resolver chooser, i.e. no default is set.
            ?.takeIf { it != "android" }
    }.getOrNull()

    private fun isInstalled(pm: PackageManager, pkg: String) =
        runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
}
