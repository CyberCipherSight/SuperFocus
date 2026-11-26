import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../session.dart';

/// Binds [Locker] to the native lock-task layer (ARCHITECTURE.md §11).
///
/// Reports [EnforcementLevel.advisory] until device owner is actually
/// provisioned, so the UI says "not locking yet" rather than pretending
/// (edge case 4).
class AndroidLocker implements Locker {
  static const _owner = MethodChannel('focusbro/owner');
  static const _session = MethodChannel('focusbro/session');

  EnforcementLevel _level = EnforcementLevel.advisory;
  Map<String, dynamic> _caps = const {};

  @override
  EnforcementLevel get level => _level;

  /// The command the user runs over ADB to provision. Surfaced verbatim in the
  /// UI with a copy button — this is the one setup step that cannot be
  /// automated away.
  String get adbCommand =>
      _caps['adbCommand'] as String? ??
      'adb shell dpm set-device-owner '
          'com.cybersight.hyperfocus.appblocker.advanced/com.cybersight.hyperfocus.appblocker.owner.OwnerReceiver';

  bool get isDeviceOwner => _caps['deviceOwner'] as bool? ?? false;
  bool get pinningEnabled => _caps['pinningEnabled'] as bool? ?? false;

  @override
  Future<void> init() async {
    final caps = await _owner.invokeMapMethod<String, dynamic>('capabilities');
    if (caps == null) return;
    _caps = caps;
    _level = switch (caps['level'] as String?) {
      'strong' => EnforcementLevel.strong,
      'standard' => EnforcementLevel.standard,
      _ => EnforcementLevel.advisory,
    };
  }

  @override
  Future<List<DockApp>> dock() async {
    final rows = await _session.invokeListMethod<Map<Object?, Object?>>('dock');
    if (rows == null) return kMandatoryDock;
    final available = {
      for (final r in rows) r['id'] as String: r['available'] as bool? ?? false,
    };
    return [
      for (final slot in kMandatoryDock)
        slot.withAvailability(available[slot.id] ?? false),
    ];
  }

  @override
  Future<List<DockApp>> allowedApps() async {
    final rows = await _session.invokeListMethod<Map<Object?, Object?>>(
      'allowedApps',
    );
    return [
      for (final r in rows ?? [])
        DockApp(r['id'] as String, r['label'] as String, Icons.apps),
    ];
  }

  @override
  Future<void> lock(Duration duration) =>
      _session.invokeMethod('lock', {'durationMs': duration.inMilliseconds});

  @override
  Future<void> unlock() => _session.invokeMethod('unlock');

  @override
  Future<Duration?> lockedRemaining() async {
    final state = await _session.invokeMapMethod<String, dynamic>('state');
    if (state == null || state['locked'] != true) return null;
    return Duration(milliseconds: (state['remainingMs'] as num).toInt());
  }

  @override
  Future<void> launchDockApp(DockApp app) => app.id.contains('.')
      // Allowed apps carry a real package name; the fixed six carry slot ids.
      ? _session.invokeMethod('launchPackage', {'package': app.id})
      : _session.invokeMethod('launchDock', {'id': app.id});
}

/// Android when provisioned, an honest timer everywhere else.
Locker defaultLocker() => Platform.isAndroid ? AndroidLocker() : MockLocker();
