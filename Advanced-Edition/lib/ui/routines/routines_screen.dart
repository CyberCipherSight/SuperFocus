import '../../platform/build_variant.dart';
import 'dart:async';
import 'package:flutter/material.dart';
import '../../session.dart';
import '../../session/routine.dart';
import 'routine_editor.dart';
import '../../platform/diagnostics.dart';
import '../design/companion.dart';
import '../setup/protection_cards.dart';

const _dayLabels = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];

class RoutinesScreen extends StatefulWidget {
  const RoutinesScreen({super.key});

  @override
  State<RoutinesScreen> createState() => _RoutinesScreenState();
}

class _RoutinesScreenState extends State<RoutinesScreen> {
  final _repo = RoutineRepo();
  List<Routine> _routines = [];
  DateTime? _next;
  bool _nightTab = false;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    final routines = await _repo.load();
    final next = await _repo.nextFire();
    if (!mounted) return;
    setState(() {
      _routines = routines;
      _next = next;
    });
  }

  Future<void> _commit(List<Routine> routines) async {
    await _repo.save(routines);
    await _reload();
  }

  Future<void> _add() async {
    final created = await Navigator.of(
      context,
    ).push<Routine>(MaterialPageRoute(builder: (_) => const RoutineEditor()));
    if (created != null) await _commit([..._routines, created]);
  }

  /// §7: tightening is instant, loosening is gated. The check is the same for
  /// an edit and a delete, because a delete is just the strongest loosening.
  Future<void> _change(Routine before, Routine? after) async {
    final weakening = after == null || weakensRoutine(before, after);
    if (weakening && !await _serveDwell(before)) return;

    final next = [
      for (final r in _routines)
        if (r.id != before.id) r else after,
    ].whereType<Routine>().toList();
    await _commit(next);
  }

  Future<bool> _serveDwell(Routine routine) async {
    await _repo.beginDwell('routine:${routine.id}');
    if (!mounted) return false;
    final ok = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _DwellDialog(repo: _repo, routineId: routine.id),
    );
    return ok ?? false;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Schedule')),
      floatingActionButton: _nightTab
          ? null
          : FloatingActionButton.extended(
              onPressed: _add,
              icon: const Icon(Icons.add),
              label: const Text('Add focus time'),
            ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 96),
        children: [
          Wrap(
            spacing: 10,
            children: [
              ChoiceChip(
                label: const Text('Focus routines'),
                selected: !_nightTab,
                onSelected: (_) => setState(() => _nightTab = false),
              ),
              if (!kPlayEdition)
                ChoiceChip(
                  label: const Text('Night blocking'),
                  selected: _nightTab,
                  onSelected: (_) => setState(() => _nightTab = true),
                ),
            ],
          ),
          const SizedBox(height: 16),
          if (_nightTab)
            const _NightPanel()
          else ...[
            const CompanionBanner(
              title: 'Build your rhythm.',
              message: 'Choose a time. Let your intention become a habit.',
              mood: CompanionMood.help,
            ),
            const SectionLabel('Your focus routines'),
            if (_routines.isEmpty)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(20),
                  child: Text(
                    'No routines yet. Add a focus time and FocusBro will start your session automatically.',
                  ),
                ),
              ),
            if (_next != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(
                  'Up next · ${_formatNext(_next!)}',
                  style: theme.textTheme.bodySmall,
                ),
              ),
          ],
          if (!_nightTab)
            for (final r in _routines)
              _RoutineCard(
                routine: r,
                onToggle: (on) => _change(r, r.copyWith(enabled: on)),
                onDelete: () => _change(r, null),
                onEdit: () async {
                  final edited = await Navigator.of(context).push<Routine>(
                    MaterialPageRoute(
                      builder: (_) => RoutineEditor(existing: r),
                    ),
                  );
                  if (edited != null) await _change(r, edited);
                },
              ),
        ],
      ),
    );
  }

  String _formatNext(DateTime at) {
    final now = DateTime.now();
    final days = DateTime(
      at.year,
      at.month,
      at.day,
    ).difference(DateTime(now.year, now.month, now.day)).inDays;
    final time =
        '${at.hour.toString().padLeft(2, '0')}:${at.minute.toString().padLeft(2, '0')}';
    return switch (days) {
      0 => 'today at $time',
      1 => 'tomorrow at $time',
      _ => 'in $days days at $time',
    };
  }
}

class _NightPanel extends StatefulWidget {
  const _NightPanel();
  @override
  State<_NightPanel> createState() => _NightPanelState();
}

class _NightPanelState extends State<_NightPanel> {
  final _diagnostics = Diagnostics();
  Map<String, dynamic> _status = {};
  bool _loading = true;
  String? _error;
  late final AppLifecycleListener _lifecycle;
  @override
  void initState() {
    super.initState();
    _load();
    _lifecycle = AppLifecycleListener(onResume: _load);
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final status = await _diagnostics.nightStatus();
      if (mounted) {
        setState(() {
          _status = status;
          _loading = false;
          _error = null;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = 'Could not load night blocking. Try again.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const CompanionBanner(
        title: 'Let the day settle.',
        message: 'See which apps rest at night, and when they return.',
        mood: CompanionMood.sleep,
      ),
      const SectionLabel('Night blocking'),
      if (_loading)
        const Center(child: CircularProgressIndicator())
      else if (_error != null) ...[
        Text(_error!),
        TextButton(onPressed: _load, child: const Text('Try again')),
      ] else if (!_diagnostics.supported)
        const Text('Night blocking is available on Android.')
      else
        NightGuardCard(
          status: _status,
          diagnostics: _diagnostics,
          onChanged: _load,
        ),
    ],
  );
}

class _RoutineCard extends StatelessWidget {
  const _RoutineCard({
    required this.routine,
    required this.onToggle,
    required this.onDelete,
    required this.onEdit,
  });

  final Routine routine;
  final ValueChanged<bool> onToggle;
  final VoidCallback onDelete;
  final VoidCallback onEdit;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(routine.timeLabel, style: theme.textTheme.headlineSmall),
                const SizedBox(width: 12),
                Text(
                  '${routine.durationMin} min',
                  style: theme.textTheme.bodyMedium,
                ),
                const Spacer(),
                Switch(value: routine.enabled, onChanged: onToggle),
              ],
            ),
            const SizedBox(height: 4),
            Row(
              children: [
                for (var i = 0; i < 7; i++)
                  Padding(
                    padding: const EdgeInsets.only(right: 6),
                    child: Text(
                      _dayLabels[i],
                      style: theme.textTheme.labelMedium?.copyWith(
                        color: routine.runsOn(i + 1)
                            ? theme.colorScheme.primary
                            : theme.colorScheme.onSurfaceVariant.withValues(
                                alpha: 0.4,
                              ),
                        fontWeight: routine.runsOn(i + 1)
                            ? FontWeight.bold
                            : FontWeight.normal,
                      ),
                    ),
                  ),
                const Spacer(),
                // Fitts's Law inverted (§12): destructive actions stay small
                // and out of the thumb's path.
                TextButton(onPressed: onEdit, child: const Text('Edit')),
                TextButton(onPressed: onDelete, child: const Text('Delete')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// §7: the five-minute dwell, served in the foreground. Leaving the app clears
/// it natively, so this dialog is a display of a decision made in Kotlin.
class _DwellDialog extends StatefulWidget {
  const _DwellDialog({required this.repo, required this.routineId});
  final RoutineRepo repo;
  final String routineId;

  @override
  State<_DwellDialog> createState() => _DwellDialogState();
}

class _DwellDialogState extends State<_DwellDialog> {
  Timer? _timer;
  Duration _remaining = const Duration(minutes: 5);

  @override
  void initState() {
    super.initState();
    _tick();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
  }

  Future<void> _tick() async {
    final left = await widget.repo.dwellRemaining(
      'routine:${widget.routineId}',
    );
    if (!mounted) return;
    setState(() => _remaining = left);
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ready = _remaining == Duration.zero;
    return CompanionDialog(
      mood: CompanionMood.rest,
      title: const Text('Loosening a routine'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Adding focus is instant. Taking it away waits five minutes, with '
            'this screen open. Leaving the app starts the wait over.',
          ),
          const SizedBox(height: 16),
          Center(
            child: Text(
              ready ? 'Ready' : formatCountdown(_remaining),
              style: Theme.of(context).textTheme.headlineMedium,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('Keep it'),
        ),
        FilledButton(
          onPressed: ready ? () => Navigator.of(context).pop(true) : null,
          child: const Text('Loosen'),
        ),
      ],
    );
  }
}
