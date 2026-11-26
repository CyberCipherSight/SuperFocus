package com.cybersight.hyperfocus.appblocker.shield

/** Idempotent policy reconciliation; failures remain visible and are retried. */
object DnsEnforcement {
    interface Platform {
        fun lock()
        fun isPinned(): Boolean
        fun pin()
    }
    fun run(platform: Platform): List<String> {
        val errors = mutableListOf<String>()
        runCatching { platform.lock() }
            .onFailure { errors += "Private DNS lock: ${it.message}" }
        runCatching {
            if (!platform.isPinned()) platform.pin()
            check(platform.isPinned()) { "Provider did not remain applied" }
        }.onFailure { errors += "Private DNS provider: ${it.message}" }
        return errors
    }
}
