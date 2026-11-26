package com.cybersight.hyperfocus.appblocker.enforce

import com.cybersight.hyperfocus.appblocker.PlayPrivacy

import com.cybersight.hyperfocus.appblocker.BuildConfig

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.cybersight.hyperfocus.appblocker.night.NightGuard
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup
import com.cybersight.hyperfocus.appblocker.root.RootEnforcer
import com.cybersight.hyperfocus.appblocker.session.SessionStore

/**
 * The accessibility enforcement tier (ARCHITECTURE.md §19.5).
 *
 * Only for devices that cannot be device owner. Where lock-task mode blocks
 * everything at the OS level, this watches which app comes to the foreground
 * and ejects the ones that are not allowed.
 *
 * Honest about what it is: an accessibility service reacts *after* an app opens,
 * so there is a visible flicker before the eject, and turning the service off in
 * Settings ends enforcement immediately. It is strong friction, not a cage. The
 * capability level reports `standard`, never `strong`.
 *
 * Reads only the foreground package name from window-change events. It requests
 * no window content, so it cannot see what is on screen, typed, or sent — which
 * is why `canRetrieveWindowContent` is false in its config.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private var foreground: String? = null

    companion object {
        @Volatile private var activeService: BlockerAccessibilityService? = null

        fun checkCurrentAppLimit() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val service = activeService ?: return@post
                if (BuildConfig.PLAY_EDITION && com.cybersight.hyperfocus.appblocker.PlayMode.effective(service) != "strict") return@post
                val pkg = service.foreground ?: return@post
                if (service.getSystemService(android.os.PowerManager::class.java).isInteractive &&
                    !service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
                    com.cybersight.hyperfocus.appblocker.applimits.AppLimits.isBlockedNow(service, pkg)) {
                    service.showLimitedApp(pkg)
                }
            }
        }
        @Volatile var connected: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = PlayPrivacy.permitted(this, "accessibility")
        if (BuildConfig.PLAY_EDITION && connected) activeService = this
        Allowlist.invalidate()
        com.cybersight.hyperfocus.appblocker.applimits.AppLimits.requestSync(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!PlayPrivacy.permitted(this, "accessibility")) return
        connected = true
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (BuildConfig.PLAY_EDITION && com.cybersight.hyperfocus.appblocker.PlayMode.selected(this) == "basic") return

        if (!BuildConfig.PLAY_EDITION || com.cybersight.hyperfocus.appblocker.PlayMode.selected(this) == "strict") com.cybersight.hyperfocus.appblocker.applimits.AppLimits.onWindowChanged(this)

        // Device owner already blocks this at the OS level; running both would
        // fight lock-task mode for no gain.
        if (OwnerSetup.isOwner(this)) return

        val packageName = event.packageName?.toString() ?: return
        if (BuildConfig.PLAY_EDITION) {
            activeService = this
            foreground = packageName
        }
        val className = event.className?.toString().orEmpty()
        if (BuildConfig.PLAY_EDITION && com.cybersight.hyperfocus.appblocker.PlayMode.effective(this) != "strict") {
            BlockOverlay.hide(this)
            return
        }

        if (BuildConfig.PLAY_EDITION && packageName in NightGuard.safetyAllowlist(this)) {
            BlockOverlay.onAllowedForeground(this, packageName, className)
            return
        }
        if (com.cybersight.hyperfocus.appblocker.applimits.AppLimits.isBlockedNow(this, packageName)) {
            showLimitedApp(packageName)
            return
        }

        if (!SessionStore.isLocked(this)) {
            // No session: the only thing to enforce is Night Guard (§24.6),
            // which on this tier is on-sight ejection, and says so in Setup.
            if (NightGuard.isBlocked(this, packageName)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                BlockOverlay.show(this, packageName, BlockOverlay.Reason.NIGHT)
                RootEnforcer.stopApp(packageName)
            } else {
                BlockOverlay.onAllowedForeground(this, packageName, className)
            }
            return
        }

        // The accessibility settings screen is the one page inside Settings that
        // ends enforcement in two taps. The rest of Settings stays reachable
        // (§2.1) — this closes the trivial escape without ever making the phone
        // unrecoverable, which the session cap already guarantees.
        if (!BuildConfig.PLAY_EDITION && Allowlist.isAccessibilitySettings(packageName, className)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            SessionStore.recordTamper(this)
            return
        }

        if (Allowlist.permits(this, packageName) ||
            (BuildConfig.PLAY_EDITION && !com.cybersight.hyperfocus.appblocker.applimits.AppLimits.blocksInFocus(this, packageName))) {
            BlockOverlay.onAllowedForeground(this, packageName, className)
            return
        }

        performGlobalAction(GLOBAL_ACTION_HOME)
        // The overlay stays over the app so re-opening it shows the block again
        // rather than handing back a usable half-second of screen.
        BlockOverlay.show(this, packageName)
        RootEnforcer.stopApp(packageName)
        SessionStore.recordBlock(this, packageName)
    }

    private fun showLimitedApp(packageName: String) {
        foreground = null
        performGlobalAction(GLOBAL_ACTION_HOME)
        BlockOverlay.show(this, packageName, BlockOverlay.Reason.APP_LIMIT)
        RootEnforcer.stopApp(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (BuildConfig.PLAY_EDITION) {
            connected = false
            if (activeService === this) activeService = null
            foreground = null
            BlockOverlay.hide(this)
        }
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        if (activeService === this) activeService = null
        foreground = null
        BlockOverlay.hide(this)
        // Turning the service off mid-session is the expected escape attempt.
        // It cannot be prevented, so it is recorded (§2.1).
        if (SessionStore.isLocked(this)) SessionStore.recordTamper(this)
        return super.onUnbind(intent)
    }
}
