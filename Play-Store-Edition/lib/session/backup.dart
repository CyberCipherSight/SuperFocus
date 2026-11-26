import 'dart:convert';
import 'package:flutter/services.dart';
import '../platform/stats.dart';
import 'routine.dart';

/// Local backup (ARCHITECTURE.md §9).
///
/// One JSON file, versioned, written wherever the user points the system file
/// picker. No encryption by default — it lives on the user's own storage and
/// holds no secrets.
const kBackupSchema = 1;

class BackupPayload {
  const BackupPayload({
    required this.exportedAt,
    required this.routines,
    required this.sessions,
  });

  final DateTime exportedAt;
  final List<Routine> routines;
  final List<PastSession> sessions;

  String encode() => const JsonEncoder.withIndent('  ').convert({
    'schema': kBackupSchema,
    'app': 'FocusBro',
    'exportedAt': exportedAt.toIso8601String(),
    'routines': routines.map((r) => r.toMap()).toList(),
    'sessions': [
      for (final s in sessions)
        {
          'startedAt': s.startedAt.millisecondsSinceEpoch,
          'durationMs': s.duration.inMilliseconds,
        },
    ],
  });
}

class BackupFormatException implements Exception {
  BackupFormatException(this.message);
  final String message;
  @override
  String toString() => message;
}

BackupPayload decodeBackup(String source) {
  final Object? raw;
  try {
    raw = jsonDecode(source);
  } on FormatException {
    throw BackupFormatException('That file is not a FocusBro backup.');
  }
  if (raw is! Map<String, dynamic>) {
    throw BackupFormatException('That file is not a FocusBro backup.');
  }

  final schema = raw['schema'];
  if (schema is! int) {
    throw BackupFormatException('That file is not a FocusBro backup.');
  }
  // Edge case 26: a newer schema is refused outright rather than partially
  // applied, which would leave settings in a state neither version intended.
  if (schema > kBackupSchema) {
    throw BackupFormatException(
      'This backup was made by a newer version of FocusBro. Update the app '
      'first — importing it partially would be worse than not importing it.',
    );
  }

  return BackupPayload(
    exportedAt:
        DateTime.tryParse(raw['exportedAt'] as String? ?? '') ?? DateTime.now(),
    routines: [
      for (final r in (raw['routines'] as List? ?? []))
        Routine.fromMap(r as Map<Object?, Object?>),
    ],
    sessions: [
      for (final s in (raw['sessions'] as List? ?? []))
        PastSession(
          DateTime.fromMillisecondsSinceEpoch(
            ((s as Map)['startedAt'] as num).toInt(),
          ),
          Duration(milliseconds: (s['durationMs'] as num).toInt()),
        ),
    ],
  );
}

class MergeOutcome {
  const MergeOutcome(this.routines, this.heldBack);

  /// What the routine list becomes if this import is applied.
  final List<Routine> routines;

  /// Human-readable reasons for each change the ratchet refused.
  final List<String> heldBack;

  bool get isClean => heldBack.isEmpty;
}

/// §9: an import may tighten instantly but may never loosen.
///
/// Without this, restoring last month's backup would be a one-tap way around
/// the five-minute dwell — the single most obvious hole in the whole ratchet.
MergeOutcome mergeBackup(List<Routine> current, List<Routine> incoming) {
  final byId = {for (final r in current) r.id: r};
  final result = <Routine>[];
  final heldBack = <String>[];

  for (final r in incoming) {
    final existing = byId.remove(r.id);
    if (existing == null) {
      // A routine the device does not have is pure addition.
      result.add(r);
    } else if (weakensRoutine(existing, r)) {
      result.add(existing);
      heldBack.add(
        'Kept the stricter ${existing.timeLabel} routine already on this '
        'device; the backup\'s version was weaker.',
      );
    } else {
      result.add(r);
    }
  }

  // Routines present locally but absent from the backup: dropping them would be
  // a removal, so they stay.
  for (final orphan in byId.values) {
    result.add(orphan);
    heldBack.add(
      'Kept the ${orphan.timeLabel} routine, which this backup does not '
      'contain. Delete it directly if you mean to.',
    );
  }

  result.sort((a, b) => a.startMinute.compareTo(b.startMinute));
  return MergeOutcome(result, heldBack);
}

/// Bridges to the Storage Access Framework. Returns null when the user cancels.
class BackupFiles {
  static const _channel = MethodChannel('focusbro/stats');

  Future<String?> pickAndWrite(String json) async {
    final now = DateTime.now();
    final stamp = '${now.year}-${_two(now.month)}-${_two(now.day)}';
    return _channel.invokeMethod<String>('exportFile', {
      'json': json,
      'name': 'focusbro-backup-$stamp.json',
    });
  }

  Future<String?> pickAndRead() => _channel.invokeMethod<String>('importFile');

  Future<void> restoreSessions(List<PastSession> sessions) =>
      _channel.invokeMethod('importSessions', [
        for (final s in sessions)
          {
            'startedAt': s.startedAt.millisecondsSinceEpoch,
            'durationMs': s.duration.inMilliseconds,
          },
      ]);

  static String _two(int n) => n.toString().padLeft(2, '0');
}
