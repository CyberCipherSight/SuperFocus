import 'dart:io';
import 'package:flutter/services.dart';

class AppUsage {
  const AppUsage(this.label, this.duration);
  final String label;
  final Duration duration;
}

class DayFocus {
  const DayFocus(this.date, this.minutes);
  final DateTime date;
  final int minutes;
}

class PastSession {
  const PastSession(this.startedAt, this.duration);
  final DateTime startedAt;
  final Duration duration;
}

/// Local-only analytics (ARCHITECTURE.md §8, §16).
///
/// The focus trend comes from FocusBro's own session log and needs no
/// permission. Only the per-app breakdown depends on usage access, so denying
/// it costs one chart rather than the whole screen.
class Stats {
  static const _channel = MethodChannel('focusbro/stats');

  bool get supported => Platform.isAndroid;

  Future<bool> usageGranted() async {
    if (!supported) return false;
    return await _channel.invokeMethod<bool>('usageGranted') ?? false;
  }

  Future<List<AppUsage>> todayByApp() async {
    if (!supported) return const [];
    final rows = await _channel.invokeListMethod<Map<Object?, Object?>>(
      'todayByApp',
    );
    return (rows ?? [])
        .map(
          (r) => AppUsage(
            r['label'] as String? ?? '',
            Duration(milliseconds: (r['ms'] as num).toInt()),
          ),
        )
        .toList();
  }

  Future<List<DayFocus>> dailyMinutes(int days) async {
    if (!supported) return const [];
    final rows = await _channel.invokeListMethod<Map<Object?, Object?>>(
      'dailyMinutes',
      {'days': days},
    );
    return (rows ?? [])
        .map(
          (r) => DayFocus(
            DateTime.parse(r['date'] as String),
            (r['minutes'] as num).toInt(),
          ),
        )
        .toList();
  }

  Future<List<PastSession>> recentSessions({int limit = 20}) async {
    if (!supported) return const [];
    final rows = await _channel.invokeListMethod<Map<Object?, Object?>>(
      'recentSessions',
      {'limit': limit},
    );
    return (rows ?? [])
        .map(
          (r) => PastSession(
            DateTime.fromMillisecondsSinceEpoch(
              (r['startedAt'] as num).toInt(),
            ),
            Duration(milliseconds: (r['durationMs'] as num).toInt()),
          ),
        )
        .toList();
  }

  /// How often a blocked app was pushed away, and how often the accessibility
  /// service was switched off mid-session. Counts, never blame (§12).
  Future<({int blocked, int tampered})> blockAttempts() async {
    if (!supported) return (blocked: 0, tampered: 0);
    final raw = await _channel.invokeMapMethod<String, dynamic>(
      'blockAttempts',
    );
    return (
      blocked: (raw?['blocked'] as num?)?.toInt() ?? 0,
      tampered: (raw?['tampered'] as num?)?.toInt() ?? 0,
    );
  }

  Future<void> clearHistory() => _channel.invokeMethod('clearHistory');
}

/// Splits a trend into the current period and the one before it, so "better or
/// worse than last week" can be read off the chart without a number.
///
/// Returns (current, previous) of equal length, oldest first.
(List<DayFocus>, List<DayFocus>) splitPeriods(List<DayFocus> days) {
  if (days.length < 2) return (days, const []);
  final half = days.length ~/ 2;
  return (
    days.sublist(days.length - half),
    days.sublist(days.length - 2 * half, days.length - half),
  );
}

/// Plain difference in total focus minutes between the two halves. Presented as
/// a fact, never as praise or reproach (§12 ethical engagement).
int periodDelta(List<DayFocus> days) {
  final (current, previous) = splitPeriods(days);
  if (previous.isEmpty) return 0;
  int sum(List<DayFocus> l) => l.fold(0, (a, d) => a + d.minutes);
  return sum(current) - sum(previous);
}
