package com.cybersight.hyperfocus.appblocker.dock

/** Only SIM dialers and their real call UI belong in the call safety set.
 * Browser tel: VIEW handlers and non-UI companion services are not dialers. */
object CallingPackagePolicy {
    data class CallUi(val pkg: String, val system: Boolean, val protectedService: Boolean, val hasUi: Boolean)

    fun packages(defaultDialer: String?, systemDialer: String?, dialers: Set<String>,
                 callUi: List<CallUi>, installedSystemFallbacks: Set<String>): Set<String> {
        val phones = (dialers + listOfNotNull(defaultDialer, systemDialer)).filter { it.isNotBlank() }.toSet()
        return phones + installedSystemFallbacks + callUi.filter {
            it.protectedService && it.hasUi && (it.system || it.pkg in phones)
        }.map { it.pkg }
    }
}
