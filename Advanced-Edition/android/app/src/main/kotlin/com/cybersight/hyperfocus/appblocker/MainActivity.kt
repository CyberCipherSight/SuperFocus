package com.cybersight.hyperfocus.appblocker

import com.cybersight.hyperfocus.appblocker.dock.DockResolver
import com.cybersight.hyperfocus.appblocker.backup.FileBridge
import com.cybersight.hyperfocus.appblocker.oem.OemGuide
import com.cybersight.hyperfocus.appblocker.owner.HealthCheck
import com.cybersight.hyperfocus.appblocker.owner.LockController
import com.cybersight.hyperfocus.appblocker.owner.OwnerSetup
import com.cybersight.hyperfocus.appblocker.owner.TetherPolicy
import com.cybersight.hyperfocus.appblocker.root.RootEnforcer
import com.cybersight.hyperfocus.appblocker.night.NightGuard
import com.cybersight.hyperfocus.appblocker.protection.Protection
import com.cybersight.hyperfocus.appblocker.protection.ProtectionState
import com.cybersight.hyperfocus.appblocker.shield.ShieldPolicy
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.Settings
import com.cybersight.hyperfocus.appblocker.owner.Radios
import com.cybersight.hyperfocus.appblocker.session.RatchetStore
import com.cybersight.hyperfocus.appblocker.session.Routine
import com.cybersight.hyperfocus.appblocker.session.RoutineAlarms
import com.cybersight.hyperfocus.appblocker.session.RoutineStore
import com.cybersight.hyperfocus.appblocker.session.SessionService
import com.cybersight.hyperfocus.appblocker.session.SessionStore
import com.cybersight.hyperfocus.appblocker.stats.SessionLog
import com.cybersight.hyperfocus.appblocker.stats.UsageBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Single activity, by design. Flutter renders both the home and the session
 * screen here, so this is also the lock-task host — a separate SessionActivity
 * would buy nothing.
 */
class MainActivity : FlutterActivity() {

    /**
     * The one-line back freeze (§1). Home, recents and the home gesture are
     * already dead under lock-task mode; back is the only key that still
     * navigates, so it is swallowed here rather than in Dart, where a crashed
     * Flutter engine would let it through.
     */
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // Public preview does not suppress Android Back.
        super.onBackPressed()
    }

    private val files by lazy { FileBridge(this) }

    /**
     * Torch and screen brightness live in the quick-settings shade, which lock
     * task hides. setTorchMode needs no permission, so the "no camera" claim
     * in Setup still holds. Brightness is set on this window only — it needs
     * no WRITE_SETTINGS, and this window is the whole screen during a session.
     */
    private val camera by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var torchOn = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCamera()) torchOn = enabled
        }
    }

    private fun torchCamera(): String? = runCatching {
        camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    private fun brightness(): Double {
        val own = window.attributes.screenBrightness
        if (own >= 0) return own.toDouble()
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255.0
        }.getOrDefault(0.5)
    }

    override fun onDestroy() {
        runCatching { camera.unregisterTorchCallback(torchCallback) }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (files.onActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** A routine fired and the system brought us up. Start it immediately. */
    private fun consumeRoutineIntent(source: Intent?) {
        if (source?.hasExtra(RoutineAlarms.EXTRA_ROUTINE) != true) return
        source.removeExtra(RoutineAlarms.EXTRA_ROUTINE)
        RoutineAlarms.reconcile(this, "notification")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeRoutineIntent(intent)
    }

    /**
     * §7: the dwell must be served with the app awake and in front of the user.
     * Leaving resets it — which is the entire point of a dwell rather than a
     * plain timer.
     */
    override fun onPause() {
        super.onPause()
        RatchetStore.clearAll(this)
    }

    /**
     * Reconcile against the native store here, not in onCreate.
     *
     * `startLockTask()` requires a resumed activity, so doing this during engine
     * setup meant a cold start — the exact path a reboot takes — silently failed
     * to re-enter lock task and the phone came up unlocked.
     *
     * If we were killed mid-session the lock is re-engaged; if the timer expired
     * while the process was dead, the launcher and status bar are handed back
     * (edge cases 5-9).
     */
    override fun onResume() {
        super.onResume()
        if (SessionStore.isLocked(this)) {
            LockController.engage(this)
        } else {
            LockController.release(this, this)
        }
        SessionService.sync(this)
        consumeRoutineIntent(intent)
        RoutineAlarms.reconcile(this, "resume")
    }

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        MethodChannel(engine.dartExecutor.binaryMessenger, "focusbro/appearance")
            .setMethodCallHandler { call, result ->
                val preferences = getSharedPreferences("focusbro.appearance", Context.MODE_PRIVATE)
                when (call.method) {
                    "load" -> result.success(mapOf(
                        "mode" to preferences.getString("mode", "dark"),
                        "reduceMotion" to preferences.getBoolean("reduceMotion", false),
                    ))
                    "save" -> {
                        val mode = call.argument<String>("mode") ?: "dark"
                        if (mode !in listOf("system", "light", "dark")) {
                            result.error("appearance", "Unknown color mode", null)
                        } else {
                            val saved = preferences.edit().putString("mode", mode)
                                .putBoolean("reduceMotion", call.argument<Boolean>("reduceMotion") == true)
                                .commit()
                            if (saved) result.success(null)
                            else result.error("appearance", "Could not save appearance", null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
        OwnerSetup.provision(this)
        // Off the main thread: su blocks until the user answers the grant prompt.
        RootEnforcer.probeAsync()

        RoutineAlarms.reschedule(this)
        TetherPolicy.sync(this)
        // Cheap and idempotent: Night Guard from the clock, Adult Shield from
        // the device-owner state (§24.7).
        Protection.sync(this)
        runCatching { camera.registerTorchCallback(torchCallback, null) }

        MethodChannel(engine.dartExecutor.binaryMessenger, "focusbro/owner")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "capabilities" -> result.success(OwnerSetup.capabilities(this))
                    "health" -> result.success(HealthCheck.status(this))
                    "fix" -> {
                        val id = call.argument<String>("id").orEmpty()
                        result.success(PlayPrivacy.disclose(this, id) { HealthCheck.fix(this, id) } || HealthCheck.fix(this, id))
                    }
                    "callingStatus" -> result.success(com.cybersight.hyperfocus.appblocker.dock.CallingApps.status(this))
                    "lockReadiness" -> result.success(com.cybersight.hyperfocus.appblocker.owner.LockReadiness.status(this))
                    "fixDuringLock" -> result.success(com.cybersight.hyperfocus.appblocker.owner.LockReadiness.fix(this, call.argument<String>("id") ?: ""))
                    "redirectMissingPermission" -> result.success(com.cybersight.hyperfocus.appblocker.owner.LockReadiness.redirectOnce(this))
                    "relinquish" -> result.success(OwnerSetup.relinquish(this))
                    "openUninstall" -> result.success(runCatching {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                        true
                    }.getOrDefault(false))
                    // Settings and the status bar are both unreachable under
                    // lock task, so these are the only way to reach the radios
                    // during a session — both directions, not just on.
                    "radios" -> result.success(Radios.status(this))
                    "setRadio" -> {
                        val on = call.argument<Boolean>("on") ?: false
                        result.success(
                            when (call.argument<String>("kind")) {
                                "bluetooth" -> Radios.setBluetooth(this, on)
                                "wifi" -> Radios.setWifi(this, on)
                                "mobileData" -> Radios.setMobileData(on)
                                else -> false
                            }
                        )
                    }
                    "screen" -> result.success(
                        mapOf(
                            "torchAvailable" to (torchCamera() != null),
                            "torch" to torchOn,
                            "brightness" to brightness(),
                        )
                    )
                    "setTorch" -> {
                        val id = torchCamera()
                        val on = call.argument<Boolean>("on") ?: false
                        result.success(
                            id != null && runCatching { camera.setTorchMode(id, on) }.isSuccess
                        )
                    }
                    "setBrightness" -> {
                        val value = (call.argument<Double>("value") ?: -1.0).toFloat()
                        window.attributes = window.attributes.apply { screenBrightness = value }
                        result.success(true)
                    }
                    "tetherStatus" -> result.success(TetherPolicy.status(this))
                    // Re-derives the restriction from the clock. If a release
                    // alarm was dropped, this is the one-tap fix that does not
                    // cost device owner.
                    "tetherSync" -> {
                        TetherPolicy.sync(this)
                        result.success(TetherPolicy.status(this))
                    }
                    "setUsbTethering" -> {
                        val on = call.argument<Boolean>("on") ?: false
                        result.success(RootEnforcer.setUsbTethering(on))
                    }
                    "rootGrantAll" -> {
                        Thread {
                            val granted = RootEnforcer.grantAll(this)
                            runOnUiThread { result.success(granted) }
                        }.start()
                    }

                    "oem" -> result.success(OemGuide.describe(this))
                    "openOemSettings" -> result.success(OemGuide.openSettings(this))
                    "startProbe" -> result.success(
                        HealthCheck.startProbe(this, call.argument<Int>("minutes") ?: 15)
                    )
                    "probeResult" -> result.success(HealthCheck.probeResult(this))
                    "fullScreenIntent" ->
                        result.success(RoutineAlarms.fullScreenIntentAllowed(this))

                    // --- Adult Shield / Night Guard (§24) ---
                    "shieldStatus" -> result.success(ShieldPolicy.status(this))
                    "nightStatus" -> result.success(NightGuard.status(this))
                    "protectionSync" -> {
                        Protection.sync(this)
                        result.success(true)
                    }
                    "nightPackage" -> {
                        val state = ProtectionState(this)
                        val pkg = call.argument<String>("package").orEmpty().trim()
                        val add = call.argument<Boolean>("add") ?: true
                        if (pkg.isNotEmpty()) {
                            state.manualNightPackages =
                                if (add) state.manualNightPackages + pkg else state.manualNightPackages - pkg
                            NightGuard.sync(this)
                        }
                        result.success(pkg.isNotEmpty())
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(engine.dartExecutor.binaryMessenger, "focusbro/appLimits")
            .setMethodCallHandler { call, result ->
                val app = applicationContext
                com.cybersight.hyperfocus.appblocker.applimits.AppLimits.executor.execute {
                    try {
                        val limits = com.cybersight.hyperfocus.appblocker.applimits.AppLimits
                        when (call.method) {
                            "status" -> limits.reconcile(app)
                            "setDaily" -> limits.setDaily(app,
                                call.argument<String>("package") ?: "",
                                call.argument<Int>("minutes") ?: -1)
                            "clearAppRestrictions" -> limits.clearAppRestrictions(app, call.argument<String>("package").orEmpty())
                            "setFocusBlocked" -> limits.setFocusBlocked(app,
                                call.argument<String>("package").orEmpty(),
                                call.argument<Boolean>("enabled") == true)
                            "lockFor" -> limits.lockFor(app,
                                call.argument<String>("package") ?: "",
                                call.argument<Int>("minutes") ?: -1)
                            else -> { runOnUiThread { result.notImplemented() }; return@execute }
                        }
                        val state = limits.status(app)
                        runOnUiThread {
                            com.cybersight.hyperfocus.appblocker.applimits.AppLimitService.sync(app)
                            result.success(state)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { result.error("app_limits", e.message, null) }
                    }
                }
            }

        MethodChannel(engine.dartExecutor.binaryMessenger, "focusbro/session")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "lock" -> {
                        val ms = (call.argument<Number>("durationMs") ?: 0).toLong()
                        if (!SessionStore.canStart(this)) {
                            result.success(mapOf("started" to false))
                        } else {
                            val endsAt = SessionStore.start(this, ms)
                            LockController.engage(this)
                            SessionService.sync(this)
                            result.success(mapOf("started" to true, "endsAtUtc" to endsAt))
                        }
                    }

                    "unlock" -> {
                        LockController.release(this, this)
                        SessionStore.clear(this)
                        SessionService.sync(this)
                        result.success(mapOf("released" to true))
                    }


                    "dock" -> result.success(DockResolver.availability(this))

                    "allowedApps" -> result.success(DockResolver.allowedApps(this))

                    "launchPackage" -> result.success(
                        DockResolver.launchPackage(this, call.argument<String>("package") ?: "")
                    )

                    "launchDock" -> {
                        val id = call.argument<String>("id") ?: ""
                        result.success(DockResolver.launch(this, id))
                    }

                    "routines" -> result.success(
                        RoutineStore.all(this).map { it.toMap() }
                    )

                    "saveRoutines" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rows = call.arguments as? List<Map<String, Any>> ?: emptyList()
                        RoutineStore.replaceAll(
                            this,
                            rows.map {
                                Routine(
                                    id = it["id"] as String,
                                    daysMask = (it["daysMask"] as Number).toInt(),
                                    startMinute = (it["startMinute"] as Number).toInt(),
                                    durationMin = (it["durationMin"] as Number).toInt(),
                                    enabled = it["enabled"] as Boolean,
                                )
                            },
                        )
                        RoutineAlarms.reconcile(this, "save")
                        result.success(true)
                    }

                    "nextFire" -> {
                        val next = RoutineStore.nextFire(this)
                        result.success(
                            if (next == null) null
                            else mapOf(
                                "routineId" to next.first.id,
                                "atUtc" to next.second.toInstant().toEpochMilli(),
                            )
                        )
                    }

                    // §7 removal gate. Tightening never routes through here.
                    "beginDwell" -> {
                        RatchetStore.begin(this, call.argument<String>("key") ?: "")
                        result.success(RatchetStore.remainingMs(this, call.argument<String>("key") ?: ""))
                    }

                    "dwellRemaining" -> result.success(
                        RatchetStore.remainingMs(this, call.argument<String>("key") ?: "")
                    )

                    "dwellSatisfied" -> result.success(
                        RatchetStore.satisfied(this, call.argument<String>("key") ?: "")
                    )

                    "state" -> result.success(
                        mapOf(
                            "locked" to SessionStore.isLocked(this),
                            "remainingMs" to SessionStore.remainingMs(this),
                            "canStart" to SessionStore.canStart(this),
                        )
                    )

                    else -> result.notImplemented()
                }
            }

        MethodChannel(engine.dartExecutor.binaryMessenger, "focusbro/stats")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "usageGranted" -> result.success(UsageBridge.granted(this))
                    "todayByApp" -> result.success(UsageBridge.todayByApp(this))
                    "dailyMinutes" -> result.success(
                        SessionLog.dailyMinutes(this, call.argument<Int>("days") ?: 7)
                    )
                    "blockAttempts" -> result.success(
                        mapOf(
                            "blocked" to SessionStore.blockCount(this),
                            "tampered" to SessionStore.tamperCount(this),
                        )
                    )

                    "recentSessions" -> result.success(
                        SessionLog.recent(this, call.argument<Int>("limit") ?: 20)
                    )
                    // Clearing history is not ratchet-gated: it removes no
                    // restriction, and the user's own record is their own (§8).
                    "clearHistory" -> {
                        SessionLog.clear(this)
                        result.success(true)
                    }
                    "importSessions" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rows = call.arguments as? List<Map<String, Any>> ?: emptyList()
                        // record() dedupes on startedAt, so re-importing the
                        // same backup cannot inflate the history.
                        rows.forEach {
                            SessionLog.record(
                                this,
                                (it["startedAt"] as Number).toLong(),
                                (it["durationMs"] as Number).toLong(),
                            )
                        }
                        result.success(true)
                    }
                    "exportFile" -> files.export(
                        call.argument<String>("json") ?: "",
                        call.argument<String>("name") ?: "focusbro-backup.json",
                        result,
                    )
                    "importFile" -> files.import(result)
                    else -> result.notImplemented()
                }
            }
    }
}
