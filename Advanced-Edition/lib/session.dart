import 'dart:async';
import 'package:flutter/material.dart';

/// Session state machine + the enforcement seam.
///
/// Phase 1 runs against [MockLocker]. Phase 2 swaps in the Android
/// implementation backed by DevicePolicyManager lock-task mode; nothing above
/// this file changes. See ARCHITECTURE.md §4, §11.

enum SessionPhase { idle, arming, locked, releasing }

/// ARCHITECTURE.md §4. Custom durations are clamped to these bounds.
const kPresetMinutes = <int>[2, 6, 16, 29, 46, 59, 90, 120, 180];

/// §2 recoverability guarantee: no code path may produce a longer session.
/// Device Owner cannot be uninstalled via ADB, so the cap *is* the escape hatch.
/// Raised from 59 to 180 at the user's explicit request — the phone is still
/// guaranteed to free itself, just after three hours rather than one.
const kMaxSessionMinutes = 180;
const kMinSessionMinutes = 1;

/// §4: fat-finger grace before the lock actually engages.
const kArmingSeconds = 3;

/// §2: two sessions cannot be chained into a continuous block.
const kMinGapSeconds = 60;

int clampDuration(int minutes) =>
    minutes.clamp(kMinSessionMinutes, kMaxSessionMinutes);

/// One slot in the emergency dock (§3). Resolved natively from intents in
/// phase 3 — never hardcoded package names.
class DockApp {
  final String id;
  final String label;
  final IconData icon;

  /// Mandatory slots can never be removed by the user (§3, edge case 27).
  final bool mandatory;

  /// False when the device has no app for this slot — a tablet with no dialer,
  /// for instance. Rendered disabled rather than hidden, so the user can see
  /// what is and is not reachable during a lock.
  final bool available;

  const DockApp(
    this.id,
    this.label,
    this.icon, {
    this.mandatory = true,
    this.available = true,
  });

  DockApp withAvailability(bool value) =>
      DockApp(id, label, icon, mandatory: mandatory, available: value);
}

/// What this device can actually enforce (§8 capability model). The UI must
/// never claim a stronger level than this reports.
enum EnforcementLevel { strong, standard, degraded, advisory, none }

/// The six immovable slots (§3). Real packages are resolved from intents
/// natively in phase 3; the slots themselves can never be removed.
const kMandatoryDock = <DockApp>[
  DockApp('phone', 'Phone', Icons.phone),
  DockApp('emergency', 'Emergency', Icons.emergency),
  DockApp('contacts', 'Contacts', Icons.contacts),
  DockApp('messages', 'Messages', Icons.message),
  DockApp('camera', 'Camera', Icons.photo_camera),
  DockApp('clock', 'Clock', Icons.alarm),
];

abstract class Locker {
  EnforcementLevel get level;

  /// Probes real capability. Until this completes, [level] must stay pessimistic.
  Future<void> init();
  Future<List<DockApp>> dock();

  /// The user's chosen apps that stay usable during a session, plus their own
  /// development builds (§23.1). Installed ones only.
  Future<List<DockApp>> allowedApps();
  Future<void> lock(Duration duration);
  Future<void> unlock();

  /// Remaining time on a lock the native layer is already holding, or null if
  /// none. After a reboot the OS restores the lock before Dart exists, so the
  /// UI has to adopt that state rather than starting at idle (edge case 5).
  Future<Duration?> lockedRemaining();
  Future<void> launchDockApp(DockApp app);
}

/// Phase 1 stand-in. Reports [EnforcementLevel.advisory] honestly — the UI
/// shows "not locking yet" rather than pretending (edge case 4).
class MockLocker implements Locker {
  @override
  EnforcementLevel get level => EnforcementLevel.advisory;

  @override
  Future<void> init() async {}

  @override
  Future<List<DockApp>> dock() async => kMandatoryDock;

  @override
  Future<List<DockApp>> allowedApps() async => const [];

  @override
  Future<void> lock(Duration duration) async {}

  @override
  Future<void> unlock() async {}

  @override
  Future<Duration?> lockedRemaining() async => null;

  @override
  Future<void> launchDockApp(DockApp app) async {}
}

class SessionController extends ChangeNotifier {
  SessionController(this._locker);

  final Locker _locker;
  Timer? _ticker;

  SessionPhase _phase = SessionPhase.idle;
  DateTime? _endsAt;
  DateTime? _armedAt;
  DateTime? _lastEndedAt;
  Duration _pendingDuration = Duration.zero;
  List<DockApp> _dock = const [];
  List<DockApp> _allowed = const [];

  SessionPhase get phase => _phase;
  List<DockApp> get dock => _dock;
  List<DockApp> get allowedApps => _allowed;
  EnforcementLevel get level => _locker.level;
  Duration get pendingDuration => _pendingDuration;

  /// Seam for tests; production uses the wall clock. Native owns the real
  /// monotonic remainder in phase 4 (§7, edge cases 10-11).
  DateTime Function() now = DateTime.now;

  Future<void> init() async {
    await resync();
  }

  /// Adopts whatever the native layer is actually holding. Called on launch and
  /// on every resume — the OS is the authority on whether the phone is locked,
  /// and Dart's own timer is only a display.
  Future<void> resync() async {
    // Re-probe: the user may have just come back from Settings having granted
    // the very permission that changes this device's enforcement level.
    await _locker.init();
    // A newly selected SIM dialer must be reachable without restarting FocusBro.
    _dock = await _locker.dock();
    _allowed = await _locker.allowedApps();
    final remaining = await _locker.lockedRemaining();
    if (remaining != null && remaining > Duration.zero) {
      _phase = SessionPhase.locked;
      _endsAt = now().add(remaining);
      _pendingDuration = remaining;
      _startTicker();
    } else if (_phase == SessionPhase.locked) {
      // Native released while we were away.
      _phase = SessionPhase.idle;
      _endsAt = null;
      _stopTicker();
    }
    notifyListeners();
  }

  /// Exposed so the setup UI can show the exact ADB command to run.
  Locker get locker => _locker;

  Duration get remaining {
    final target = _phase == SessionPhase.arming ? _armingEndsAt : _endsAt;
    if (target == null) return Duration.zero;
    final left = target.difference(now());
    return left.isNegative ? Duration.zero : left;
  }

  DateTime? get _armingEndsAt =>
      _armedAt?.add(const Duration(seconds: kArmingSeconds));

  /// §2: blocks auto-chaining, and blocks starting on top of a live session.
  bool get canStart {
    if (_phase != SessionPhase.idle) return false;
    final last = _lastEndedAt;
    if (last == null) return true;
    return now().difference(last).inSeconds >= kMinGapSeconds;
  }

  Duration get cooldownRemaining {
    final last = _lastEndedAt;
    if (last == null || _phase != SessionPhase.idle) return Duration.zero;
    final left =
        const Duration(seconds: kMinGapSeconds) - now().difference(last);
    return left.isNegative ? Duration.zero : left;
  }

  void start(int minutes) {
    if (!canStart) return;
    _pendingDuration = Duration(minutes: clampDuration(minutes));
    _armedAt = now();
    _phase = SessionPhase.arming;
    _startTicker();
    notifyListeners();
  }

  void cancelArming() {
    if (_phase != SessionPhase.arming) return;
    _stopTicker();
    _phase = SessionPhase.idle;
    _armedAt = null;
    _pendingDuration = Duration.zero;
    notifyListeners();
  }

  /// Drives arming -> locked -> idle. Called by the ticker; exposed so tests
  /// can advance without real time.
  void tick() {
    switch (_phase) {
      case SessionPhase.arming:
        if (remaining == Duration.zero) _engage();
      case SessionPhase.locked:
        if (remaining == Duration.zero) _release();
      case SessionPhase.idle:
      case SessionPhase.releasing:
        break;
    }
    notifyListeners();
  }

  void _engage() {
    _phase = SessionPhase.locked;
    _endsAt = now().add(_pendingDuration);
    _armedAt = null;
    _locker.lock(_pendingDuration);
  }

  void endPlaySession() {
    if (_phase == SessionPhase.locked) _release();
  }

  void _release() {
    _phase = SessionPhase.releasing;
    _endsAt = null;
    _lastEndedAt = now();
    _locker.unlock().whenComplete(() {
      _stopTicker();
      _phase = SessionPhase.idle;
      notifyListeners();
    });
  }

  Future<void> launchDockApp(DockApp app) => _locker.launchDockApp(app);

  void _startTicker() {
    // One second, not 250ms: the countdown displays whole seconds, so the three
    // extra ticks rebuilt the tree for a frame that was identical. Transitions
    // can land up to a second late, which nothing here is sensitive to.
    _ticker ??= Timer.periodic(const Duration(seconds: 1), (_) => tick());
  }

  void _stopTicker() {
    _ticker?.cancel();
    _ticker = null;
  }

  @override
  void dispose() {
    _stopTicker();
    super.dispose();
  }
}

/// "45 min" / "1h 30m" / "3h". Used wherever a duration is offered rather than
/// counted down.
String formatDuration(int minutes) {
  if (minutes < 60) return '$minutes min';
  final h = minutes ~/ 60;
  final m = minutes % 60;
  return m == 0 ? '${h}h' : '${h}h ${m}m';
}

String formatCountdown(Duration d) {
  final s = (d.inSeconds % 60).toString().padLeft(2, '0');
  if (d.inHours == 0) return '${d.inMinutes.toString().padLeft(2, '0')}:$s';
  final m = (d.inMinutes % 60).toString().padLeft(2, '0');
  return '${d.inHours}:$m:$s';
}
