import 'package:flutter_test/flutter_test.dart';
import 'package:focusbro/platform/diagnostics.dart';
import 'package:focusbro/platform/stats.dart';
import 'package:focusbro/session.dart';
import 'package:focusbro/session/backup.dart';
import 'package:focusbro/session/routine.dart';

/// Guards the invariants that make FocusBro recoverable (§2) and the
/// arming -> locked -> idle machine (§4). Clock is injected so the whole
/// lifecycle runs without real time.
void main() {
  late DateTime clock;
  late SessionController c;

  setUp(() {
    clock = DateTime(2026, 1, 1, 9, 0, 0);
    c = SessionController(MockLocker())..now = () => clock;
  });

  void advance(Duration d) {
    clock = clock.add(d);
    c.tick();
  }

  test('duration is clamped to the recoverability cap', () {
    expect(clampDuration(0), kMinSessionMinutes);
    expect(clampDuration(600), kMaxSessionMinutes);
    expect(clampDuration(kMaxSessionMinutes), kMaxSessionMinutes);
    expect(kMaxSessionMinutes, 180, reason: 'the cap is a safety guarantee, '
        'not a preference — changing it must be deliberate');
    expect(clampDuration(29), 29);
    for (final m in kPresetMinutes) {
      expect(clampDuration(m), m, reason: 'preset $m must survive clamping');
    }
  });

  test('arming engages the lock, then the lock releases on time', () {
    c.start(2);
    expect(c.phase, SessionPhase.arming);

    advance(const Duration(seconds: kArmingSeconds));
    expect(c.phase, SessionPhase.locked);
    expect(c.remaining, const Duration(minutes: 2));

    advance(const Duration(minutes: 1));
    expect(c.phase, SessionPhase.locked);
    expect(c.remaining, const Duration(minutes: 1));

    advance(const Duration(minutes: 1));
    expect(c.phase, isNot(SessionPhase.locked));
  });

  test('arming can be cancelled, locked cannot', () {
    c.start(6);
    c.cancelArming();
    expect(c.phase, SessionPhase.idle);

    c.start(6);
    advance(const Duration(seconds: kArmingSeconds));
    c.cancelArming();
    expect(c.phase, SessionPhase.locked, reason: 'no escape once engaged');
  });

  test('a session cannot start on top of a running one', () {
    c.start(16);
    expect(c.canStart, isFalse);
    advance(const Duration(seconds: kArmingSeconds));
    expect(c.canStart, isFalse);
  });

  test('over-long requests are capped, never honoured', () {
    c.start(600);
    advance(const Duration(seconds: kArmingSeconds));
    expect(c.remaining.inMinutes, kMaxSessionMinutes);
  });

  _dockInvariants();
  _ratchetInvariants();
  _trendSplit();
  _backupInvariants();

  /// §5: every permission must carry its own justification. An unexplained
  /// permission request is the thing this app promises not to do.
  test('every permission explains itself', () {
    expect(kPermissions.map((p) => p.id).toSet().length, kPermissions.length,
        reason: 'permission ids must be unique');
    for (final p in kPermissions) {
      expect(p.why.trim(), isNotEmpty, reason: '${p.id} needs a why');
      expect(p.breaks.trim(), isNotEmpty,
          reason: '${p.id} must say what breaks without it');
    }
    expect(kPermissions.where((p) => p.critical).map((p) => p.id),
        ['deviceOwner'],
        reason: 'device owner is the only permission the lock cannot do without');
  });

  /// Edge case 5: after a reboot the OS restores the lock before Dart exists.
  /// The UI must adopt that, not start at idle showing a Start button.
  test('a lock already held natively is adopted on launch', () async {
    final c = SessionController(_HeldLocker(const Duration(minutes: 12)))
      ..now = () => clock;
    await c.init();

    expect(c.phase, SessionPhase.locked);
    expect(c.remaining, const Duration(minutes: 12));
    expect(c.canStart, isFalse);
  });

  test('resync drops the lock when native has released it', () async {
    final locker = _HeldLocker(const Duration(minutes: 12));
    final c = SessionController(locker)..now = () => clock;
    await c.init();
    expect(c.phase, SessionPhase.locked);

    locker.held = null;
    await c.resync();
    expect(c.phase, SessionPhase.idle);
  });

  test('countdown formats as mm:ss, and h:mm:ss past an hour', () {
    expect(formatCountdown(const Duration(minutes: 59)), '59:00');
    expect(formatCountdown(const Duration(seconds: 5)), '00:05');
    expect(formatCountdown(Duration.zero), '00:00');
    // Without the hour branch a 3h session would read as 180:00.
    expect(formatCountdown(const Duration(hours: 3)), '3:00:00');
    expect(formatCountdown(const Duration(hours: 1, minutes: 5, seconds: 9)),
        '1:05:09');
  });

  test('durations offered to the user read naturally', () {
    expect(formatDuration(45), '45 min');
    expect(formatDuration(60), '1h');
    expect(formatDuration(90), '1h 30m');
    expect(formatDuration(kMaxSessionMinutes), '3h');
  });

  test('a three hour session runs its full length', () {
    c.start(180);
    advance(const Duration(seconds: kArmingSeconds));
    expect(c.remaining, const Duration(hours: 3));
    advance(const Duration(hours: 2, minutes: 59));
    expect(c.phase, SessionPhase.locked, reason: 'still locked at 2h59m');
    advance(const Duration(minutes: 1));
    expect(c.phase, isNot(SessionPhase.locked));
  });
}

/// §3, edge case 27: the six emergency slots are structural. No code path may
/// shrink the dock or mark a slot optional.
/// §7: the rule that decides whether a user waits five minutes. Tightening must
/// never be gated, loosening must never slip through.
void _ratchetInvariants() {
  const base = Routine(
    id: 'r',
    daysMask: 0x1F, // weekdays
    startMinute: 6 * 60,
    durationMin: 29,
  );

  test('tightening a routine is always instant', () {
    expect(weakensRoutine(base, base.copyWith(durationMin: 46)), isFalse);
    expect(weakensRoutine(base, base.copyWith(daysMask: 0x7F)), isFalse);
    expect(weakensRoutine(base, base), isFalse);
    expect(
      weakensRoutine(base.copyWith(enabled: false), base.copyWith(enabled: true)),
      isFalse,
    );
  });

  test('every way of loosening a routine is gated', () {
    expect(weakensRoutine(base, base.copyWith(enabled: false)), isTrue,
        reason: 'switching off');
    expect(weakensRoutine(base, base.copyWith(durationMin: 16)), isTrue,
        reason: 'shorter session');
    expect(weakensRoutine(base, base.copyWith(daysMask: 0x0F)), isTrue,
        reason: 'fewer days');
  });

  test('swapping a day for another still counts as loosening that day', () {
    // Drop Friday, add Saturday: same day count, but Friday is now unprotected.
    expect(weakensRoutine(base, base.copyWith(daysMask: 0x2F)), isTrue);
  });
}

/// §8: the period comparison must line up like-for-like, or the "vs before"
/// figure quietly lies.
void _trendSplit() {
  List<DayFocus> series(List<int> minutes) => [
        for (var i = 0; i < minutes.length; i++)
          DayFocus(DateTime(2026, 1, 1).add(Duration(days: i)), minutes[i]),
      ];

  test('splits a trend into two equal halves, current last', () {
    final (current, previous) = splitPeriods(series([1, 2, 3, 4, 5, 6]));
    expect(previous.map((d) => d.minutes), [1, 2, 3]);
    expect(current.map((d) => d.minutes), [4, 5, 6]);
  });

  test('an odd-length series still compares equal spans', () {
    final (current, previous) = splitPeriods(series([9, 1, 2, 3, 4]));
    expect(current.length, previous.length,
        reason: 'unequal spans would make the delta meaningless');
    expect(current.map((d) => d.minutes), [3, 4]);
    expect(previous.map((d) => d.minutes), [1, 2]);
  });

  test('delta reports improvement and decline symmetrically', () {
    expect(periodDelta(series([10, 10, 30, 30])), 40);
    expect(periodDelta(series([30, 30, 10, 10])), -40);
    expect(periodDelta(series([20, 20, 20, 20])), 0);
  });

  test('too little history to compare reports no change, not a fake one', () {
    expect(periodDelta(series([42])), 0);
    expect(periodDelta(const []), 0);
  });
}

/// §9: restoring an old backup is the most obvious way around the ratchet, so
/// the merge is where it has to be closed.
void _backupInvariants() {
  const strict = Routine(
    id: 'a',
    daysMask: 0x7F, // every day
    startMinute: 6 * 60,
    durationMin: 59,
  );
  final weak = strict.copyWith(daysMask: 0x03, durationMin: 6);

  test('an import cannot weaken a routine already on the device', () {
    final outcome = mergeBackup([strict], [weak]);
    expect(outcome.routines.single.durationMin, 59);
    expect(outcome.routines.single.daysMask, 0x7F);
    expect(outcome.heldBack, hasLength(1));
  });

  test('an import may strengthen a routine instantly', () {
    final outcome = mergeBackup([weak], [strict]);
    expect(outcome.routines.single.durationMin, 59);
    expect(outcome.isClean, isTrue);
  });

  test('routines missing from the backup are kept, not deleted', () {
    final outcome = mergeBackup([strict], const []);
    expect(outcome.routines, hasLength(1));
    expect(outcome.heldBack, hasLength(1),
        reason: 'the user must be told what the import did not remove');
  });

  test('a routine only in the backup is added outright', () {
    final outcome = mergeBackup(const [], [strict]);
    expect(outcome.routines.single.id, 'a');
    expect(outcome.isClean, isTrue);
  });

  test('a backup survives a round trip', () {
    final payload = BackupPayload(
      exportedAt: DateTime(2026, 5, 1, 9, 30),
      routines: [strict, weak.copyWith()],
      sessions: const [],
    );
    final restored = decodeBackup(payload.encode());
    expect(restored.routines, hasLength(2));
    expect(restored.routines.first.durationMin, 59);
    expect(restored.exportedAt, DateTime(2026, 5, 1, 9, 30));
  });

  test('a newer schema is refused outright, never partly applied', () {
    final future = '{"schema": 99, "routines": [], "sessions": []}';
    expect(() => decodeBackup(future), throwsA(isA<BackupFormatException>()));
  });

  test('a file that is not a backup fails cleanly', () {
    expect(() => decodeBackup('not json'), throwsA(isA<BackupFormatException>()));
    expect(() => decodeBackup('[1,2,3]'), throwsA(isA<BackupFormatException>()));
  });
}

/// Stands in for a native layer that is already holding a lock.
class _HeldLocker extends MockLocker {
  _HeldLocker(this.held);
  Duration? held;

  @override
  Future<Duration?> lockedRemaining() async => held;
}

void _dockInvariants() {
  test('the emergency dock cannot be shrunk or made optional', () {
    expect(kMandatoryDock.length, 6);
    expect(kMandatoryDock.map((d) => d.id).toSet(),
        {'phone', 'emergency', 'contacts', 'messages', 'camera', 'clock'});
    for (final slot in kMandatoryDock) {
      expect(slot.mandatory, isTrue, reason: '${slot.id} must be immovable');
      expect(slot.withAvailability(false).mandatory, isTrue,
          reason: 'an unavailable slot is still mandatory');
    }
  });
}
