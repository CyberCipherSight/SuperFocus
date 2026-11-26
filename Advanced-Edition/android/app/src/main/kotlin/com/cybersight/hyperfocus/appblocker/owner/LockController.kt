package com.cybersight.hyperfocus.appblocker.owner

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.cybersight.hyperfocus.appblocker.MainActivity
import com.cybersight.hyperfocus.appblocker.enforce.BlockerAccessibilityService
import com.cybersight.hyperfocus.appblocker.root.RootEnforcer
import com.cybersight.hyperfocus.appblocker.shield.ShieldPolicy
import com.cybersight.hyperfocus.appblocker.dock.DockResolver
import com.cybersight.hyperfocus.appblocker.dock.CallingApps
import com.cybersight.hyperfocus.appblocker.enforce.Allowlist
import com.cybersight.hyperfocus.appblocker.BuildConfig

/**
 * The entire Android enforcement layer (ARCHITECTURE.md §1).
 *
 * Lock-task mode is the OS's own kiosk mode: it blocks every non-allowlisted
 * app, the home button *and* the home gesture, recents, and the status bar —
 * at a level no app can reach around. This is why FocusBro has no
 * accessibility service and no overlay.
 */
object LockController {

    /**
     * Some OEM skins ignore setStatusBarDisabled. We report what actually
     * happened rather than assuming success (edge case 32).
     */
    var statusBarControlWorks: Boolean = true
        private set

    /**
     * §2.5 — GLOBAL_ACTIONS stays enabled so the power menu, and with it
     * emergency dialing, is always reachable. Non-negotiable.
     * KEYGUARD lets the screen lock normally during a session (§4).
     *
     * HOME is deliberately **off**, which is a reversal (§22). Enabling it
     * required FocusBro to register as a launcher, and a launcher registration
     * that fails to unregister leaves the user with no home screen. It did,
     * twice, on two different phones. Home is simply dead during a session now;
     * back returns from a dock app, and the session cap ends it regardless.
     */
    private const val FEATURES =
        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

    private val HOME_FILTER = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    /**
     * Immersive fullscreen for the session screen: status and nav bars hidden,
     * revealable only as a transient swipe overlay.
     *
     * Complements setStatusBarDisabled rather than replacing it — that one stops
     * the shade expanding, this one stops the bars taking up space and showing
     * notification icons. Transient-by-swipe is deliberate: a fully unreachable
     * nav bar would break the emergency dock, and the power menu stays reachable
     * either way (§2.5).
     */
    private fun setFullscreen(activity: Activity, on: Boolean) {
        runCatching {
            val window = activity.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController ?: return@runCatching
                if (on) {
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsets.Type.systemBars())
                } else {
                    controller.show(WindowInsets.Type.systemBars())
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (on) {
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                } else {
                    View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
    }

    private fun alreadyLocked(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /**
     * Screen pinning: the fallback when device owner is not available.
     *
     * `startLockTask()` from a package that is *not* on the lock-task allowlist
     * enters Android's own screen-pinning mode instead of kiosk mode. Home and
     * recents are blocked, but the user can leave by holding Back + Recents —
     * and with Settings > Security > "Ask for PIN before unpinning" that costs
     * their PIN.
     *
     * Friction, not a cage. The UI labels it that way and never claims more.
     */
    fun pinningEnabled(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, "lock_to_app_enabled", 0) == 1

    /** What this device can honestly claim (§8 capability model). */
    fun accessibilityRunning(): Boolean = BlockerAccessibilityService.connected

    /**
     * Accessibility outranks pinning: it blocks other apps while still letting
     * the emergency and payment apps through, where pinning allows exactly one
     * task and no dock at all.
     */
    fun level(context: Context): String = when {
        OwnerSetup.isOwner(context) -> "strong"
        // Root makes the accessibility tier comparable: a blocked app is closed
        // rather than covered, and switching the service off self-heals within
        // seconds. It is still weaker than device owner, which needs none of our
        // processes alive to hold.
        RootEnforcer.available() && accessibilityRunning() -> "strong"
        accessibilityRunning() -> "standard"
        !BuildConfig.PLAY_EDITION && pinningEnabled(context) -> "degraded"
        else -> "advisory"
    }

    fun engage(activity: Activity) {
        if (BuildConfig.PLAY_EDITION) return
        val ctx: Context = activity
        // Before the owner check: every tier gets the fullscreen session screen,
        // including the pinning and accessibility fallbacks.
        setFullscreen(activity, true)
        if (!OwnerSetup.isOwner(ctx)) {
            Allowlist.invalidate()
            // With the accessibility service running, the user must stay free to
            // move between allowed apps — pinning would trap them in FocusBro
            // and block the payment and emergency apps entirely.
            if (!accessibilityRunning() && pinningEnabled(ctx)) {
                runCatching { activity.startLockTask() }
            }
            return
        }
        prepare(activity)

        // Guarded and caught: startLockTask throws if the activity is not yet
        // resumed, which is exactly the state it is in during a cold start.
        if (!alreadyLocked(ctx) && !ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            runCatching { activity.startLockTask() }
        }
    }

    /** Apply device policy without needing a Flutter engine or resumed UI. */
    fun prepare(ctx: Context) {
        if (!OwnerSetup.isOwner(ctx)) return
        val dpm = OwnerSetup.dpm(ctx)
        val admin = OwnerSetup.admin(ctx)

        // Must be set before startLockTask, or Android shows the escapable
        // "pin this screen?" prompt instead of a real lock.
        refreshAllowedApps(ctx)

        // No launcher registration. Reboot recovery runs through
        // SessionService instead (§22), which does not require FocusBro to own
        // the home screen and therefore cannot strand the user without one.
        releaseHomeRole(ctx, dpm, admin)
        dpm.setLockTaskFeatures(admin, FEATURES)
        statusBarControlWorks = runCatching {
            dpm.setStatusBarDisabled(admin, true)
        }.getOrDefault(false)

        // No clock, mobile-data or tethering policy changes in this preview.

    }

    /** Also used when a dialer is installed/replaced or the default changes
     * during a session. It updates packages without restarting the lock. */
    fun refreshAllowedApps(ctx: Context) {
        if (!OwnerSetup.isOwner(ctx)) return
        CallingApps.invalidate()
        Allowlist.invalidate()
        val allowed = (listOf(ctx.packageName) + DockResolver.packages(ctx)).distinct()
        OwnerSetup.dpm(ctx).setLockTaskPackages(OwnerSetup.admin(ctx), allowed.toTypedArray())
        android.util.Log.i("FocusBroCalls", "call safety packages=${CallingApps.resolve(ctx).packages.sorted()}")
    }

    /** Device owner is itself a background-launch exemption. */
    fun launchSession(context: Context) {
        if (BuildConfig.PLAY_EDITION) return
        if (!com.cybersight.hyperfocus.appblocker.session.SessionStore.isLocked(context)) return
        if (alreadyLocked(context)) return
        if (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
        val owner = OwnerSetup.isOwner(context)
        if (!owner && !Settings.canDrawOverlays(context)) return
        runCatching {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val options = android.app.ActivityOptions.makeBasic()
            if (owner) options.setLockTaskEnabled(true)
            context.startActivity(intent, options.toBundle())
            android.util.Log.i("FocusBroRoutine", "lock task launch requested owner=$owner")
        }.onFailure { android.util.Log.e("FocusBroRoutine", "lock task launch failed", it) }
    }

    /**
     * [activity] is optional because the session service ends sessions during
     * doze, when no Activity exists. Emptying the lock-task allowlist is what
     * actually terminates the lock — the system finishes any task whose package
     * is no longer allowed — so stopLockTask is only an immediacy optimisation.
     */
    /**
     * Repair for installs that already carry the old launcher mapping.
     *
     * Earlier builds registered FocusBro as a persistent home activity and
     * failed to unregister it on release, leaving the phone with FocusBro as
     * its home screen after the session ended. Clearing is cheap and idempotent,
     * so it runs on engage and on release rather than only where the bug was.
     */
    fun releaseHomeRole(
        context: Context,
        dpm: DevicePolicyManager = OwnerSetup.dpm(context),
        admin: ComponentName = OwnerSetup.admin(context),
    ) {
        if (!OwnerSetup.isOwner(context)) return
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, context.packageName) }
    }

    fun release(context: Context, activity: Activity? = null) {
        val ctx: Context = context
        // Null when the session service ends a session during doze; the next
        // resumed launch takes the non-locked branch and restores the bars.
        activity?.let { setFullscreen(it, false) }
        if (!OwnerSetup.isOwner(ctx)) {
            runCatching { activity?.stopLockTask() }
            return
        }
        val dpm = OwnerSetup.dpm(ctx)
        val admin = OwnerSetup.admin(ctx)

        runCatching { activity?.stopLockTask() }
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        // Hand the launcher back. Called on every unlocked launch as well, so a
        // process death mid-session can never strand the user with FocusBro as
        // their permanent home screen.
        releaseHomeRole(ctx, dpm, admin)
        // Restored so the user's own clock stays theirs outside a session —
        // unless Adult Shield holds it permanently (§24.7), in which case a
        // session ending must not hand back a clock Night Guard depends on.
        if (!ShieldPolicy.ownsClockLock(ctx)) {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
        }
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
    }
}
