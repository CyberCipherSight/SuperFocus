package com.cybersight.hyperfocus.appblocker.protection

/**
 * Every constant the permanent protection layers read (ARCHITECTURE.md §24).
 *
 * Data only. Adding a package is a one-line edit here; nothing under
 * `night/` or `shield/` carries its own copy. Dynamic
 * discovery (browser classification, VpnService detection) still outranks
 * these lists — they are seeds and known cases, not the mechanism.
 */
object ProtectionPolicy {

    // --- Night Guard ---------------------------------------------------------

    /** 21:00 local. Not the 22:30 tethering cutoff; the two are independent. */
    const val NIGHT_START_HOUR = 21

    /** 08:00 local. */
    const val NIGHT_END_HOUR = 8

    /** Blocked every night regardless of classification. */
    val explicitNightPackages: Set<String> = setOf(
        "com.facebook.katana",             // Facebook
        "com.facebook.lite",               // Facebook Lite
        "com.snapchat.android",            // Snapchat
        "com.instagram.android",           // Instagram
        "com.instagram.lite",              // Instagram Lite
        "com.reddit.frontpage",            // Reddit
        "org.telegram.messenger",          // Telegram
        "org.telegram.messenger.web",      // Telegram (web-store build)
        "org.telegram.messenger.beta",     // Telegram beta
        "org.thunderdog.challegram",       // Telegram X
        "com.laurencedawson.reddit_sync",  // Sync for Reddit
        "me.ccrama.redditslide",           // Slide
        "com.rubenmayayo.reddit",          // Boost for Reddit
        "ml.docilealligator.infinityforreddit", // Infinity
    )

    /** Additional social platforms: 22:30 until the 08:00 release. */
    val socialNightPackages = setOf(
        "com.google.android.youtube", "com.discord", "com.linkedin.android",
        "com.pinterest", "com.facebook.orca",
        "com.facebook.mlite", "com.twitter.android",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.instagram.barcelona",
    )
    val nightExemptPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Browsers that classification may miss (OEM builds that hide the browser
     * category, for instance). Classification runs first; this is the floor.
     */
    val knownBrowserSeeds: Set<String> = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.duckduckgo.mobile.android",
        "com.mi.globalbrowser",
        "com.android.browser",
        "com.huawei.browser",
        "com.UCMobile.intl",
        "com.uc.browser.en",
        "acr.browser.lightning",
        "org.torproject.torbrowser",
        "org.torproject.torbrowser_alpha",
        "com.yandex.browser",
        "com.ecosia.android",
        "mark.via.gp",
        "com.google.android.apps.chrome",
    )

    // --- Adult Shield --------------------------------------------------------

    /**
     * VPN clients known by name. A secondary signal only: `DISALLOW_CONFIG_VPN`
     * is what makes them unusable, and [VpnApps] finds the rest by their
     * declared `android.net.VpnService`.
     */
    val knownVpnPackages: Set<String> = setOf(
        "com.nordvpn.android",
        "com.expressvpn.vpn",
        "com.surfshark.vpnclient.android",
        "ch.protonvpn.android",
        "com.cloudflare.onedotonedotonedotone",
        "com.mullvad.mullvadvpn",
        "com.privateinternetaccess.android",
        "com.windscribe.vpn",
        "hotspotshield.android.vpn",
        "com.kaspersky.secure.connection",
        "com.tunnelbear.android",
        "free.vpn.unblock.proxy.turbovpn",
        "com.wireguard.android",
        "net.openvpn.openvpn",
        "de.blinkt.openvpn",
        "com.tailscale.ipn",
        "org.torproject.android",           // Orbot
        "com.psiphon3",
        "com.psiphon3.subscription",
        "app.hiddify.com",
        "com.v2ray.ang",
        "io.nekohasekai.sagernet",
        "com.github.shadowsocks",
    )

    /**
     * Apps whose purpose is to route around DNS filtering without being a
     * VpnService: Tor bundles its own resolver and exits elsewhere. Suspended
     * 24/7 on device owner (reversibly, and recorded) because no network rule
     * fully covers them; see §24.9 for what remains possible.
     */
    val knownBypassPackages: Set<String> = setOf(
        "org.torproject.torbrowser",
        "org.torproject.torbrowser_alpha",
        "org.torproject.android",
    )
}
