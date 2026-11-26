import 'dart:io';
import 'package:flutter/services.dart';

/// A scheduled auto-start (ARCHITECTURE.md §6).
class Routine {
  const Routine({
    required this.id,
    required this.daysMask,
    required this.startMinute,
    required this.durationMin,
    this.enabled = true,
  });

  final String id;

  /// Bit 0 = Monday … bit 6 = Sunday, matching [DateTime.weekday] - 1.
  final int daysMask;
  final int startMinute;
  final int durationMin;
  final bool enabled;

  bool runsOn(int weekday) => daysMask & (1 << (weekday - 1)) != 0;
  int get dayCount =>
      List.generate(7, (i) => (daysMask >> i) & 1).fold(0, (a, b) => a + b);

  Routine copyWith({
    int? daysMask,
    int? startMinute,
    int? durationMin,
    bool? enabled,
  }) => Routine(
    id: id,
    daysMask: daysMask ?? this.daysMask,
    startMinute: startMinute ?? this.startMinute,
    durationMin: durationMin ?? this.durationMin,
    enabled: enabled ?? this.enabled,
  );

  Map<String, dynamic> toMap() => {
    'id': id,
    'daysMask': daysMask,
    'startMinute': startMinute,
    'durationMin': durationMin,
    'enabled': enabled,
  };

  static Routine fromMap(Map<Object?, Object?> m) => Routine(
    id: m['id'] as String,
    daysMask: (m['daysMask'] as num).toInt(),
    startMinute: (m['startMinute'] as num).toInt(),
    durationMin: (m['durationMin'] as num).toInt(),
    enabled: m['enabled'] as bool? ?? true,
  );

  String get timeLabel {
    final h = (startMinute ~/ 60).toString().padLeft(2, '0');
    final m = (startMinute % 60).toString().padLeft(2, '0');
    return '$h:$m';
  }
}

/// §7 ratchet: tightening is instant, loosening costs a dwell.
///
/// "Weaker" means less focus time — fewer days, a shorter session, a later
/// start, or switched off. Anything else is free. Kept pure so the rule that
/// decides whether a user waits five minutes is directly testable.
bool weakensRoutine(Routine before, Routine after) {
  if (before.enabled && !after.enabled) return true;
  if (after.durationMin < before.durationMin) return true;
  if (after.dayCount < before.dayCount) return true;
  // Dropping a day while adding another is still a loosening of that day.
  if (before.daysMask & ~after.daysMask != 0) return true;
  return false;
}

/// Routine persistence. Native owns the copy that matters — the alarm fires
/// with no Flutter engine alive (§11) — so this is a client, not a store.
class RoutineRepo {
  static const _channel = MethodChannel('focusbro/session');

  bool get supported => Platform.isAndroid;
  final List<Routine> _memory = [];

  Future<List<Routine>> load() async {
    if (!supported) return List.unmodifiable(_memory);
    final rows = await _channel.invokeListMethod<Map<Object?, Object?>>(
      'routines',
    );
    return (rows ?? []).map(Routine.fromMap).toList();
  }

  Future<void> save(List<Routine> routines) async {
    if (!supported) {
      _memory
        ..clear()
        ..addAll(routines);
      return;
    }
    await _channel.invokeMethod(
      'saveRoutines',
      routines.map((r) => r.toMap()).toList(),
    );
  }

  Future<DateTime?> nextFire() async {
    if (!supported) return null;
    final row = await _channel.invokeMapMethod<String, dynamic>('nextFire');
    if (row == null) return null;
    return DateTime.fromMillisecondsSinceEpoch((row['atUtc'] as num).toInt());
  }

  // --- §7 removal gate -----------------------------------------------------

  Future<Duration> beginDwell(String key) async {
    if (!supported) return Duration.zero;
    final ms = await _channel.invokeMethod<int>('beginDwell', {'key': key});
    return Duration(milliseconds: ms ?? 0);
  }

  Future<Duration> dwellRemaining(String key) async {
    if (!supported) return Duration.zero;
    final ms = await _channel.invokeMethod<int>('dwellRemaining', {'key': key});
    return Duration(milliseconds: ms ?? 0);
  }
}
