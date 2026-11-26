import 'package:flutter/material.dart';
import '../../session.dart';
import '../../session/routine.dart';

/// Hick's Law (§12): three steps — when, how long, which days — rather than one
/// dense form. Uses Material's own Stepper; a hand-rolled wizard would be more
/// code for the same behaviour.
class RoutineEditor extends StatefulWidget {
  const RoutineEditor({super.key, this.existing});
  final Routine? existing;

  @override
  State<RoutineEditor> createState() => _RoutineEditorState();
}

class _RoutineEditorState extends State<RoutineEditor> {
  static const _dayNames = [
    'Monday',
    'Tuesday',
    'Wednesday',
    'Thursday',
    'Friday',
    'Saturday',
    'Sunday',
  ];

  int _step = 0;
  late TimeOfDay _time;
  late int _duration;
  late int _daysMask;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _time = e == null
        ? const TimeOfDay(hour: 6, minute: 0)
        : TimeOfDay(hour: e.startMinute ~/ 60, minute: e.startMinute % 60);
    _duration = e?.durationMin ?? kPresetMinutes[3];
    _daysMask = e?.daysMask ?? 0x1F; // weekdays
  }

  bool get _valid => _daysMask != 0;

  void _save() {
    if (!_valid) return;
    Navigator.of(context).pop(
      Routine(
        id:
            widget.existing?.id ??
            DateTime.now().microsecondsSinceEpoch.toRadixString(36),
        daysMask: _daysMask,
        startMinute: _time.hour * 60 + _time.minute,
        durationMin: clampDuration(_duration),
        enabled: widget.existing?.enabled ?? true,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.existing == null ? 'New routine' : 'Edit routine'),
      ),
      body: Stepper(
        currentStep: _step,
        onStepTapped: (i) => setState(() => _step = i),
        onStepContinue: _step < 2
            ? () => setState(() => _step++)
            : (_valid ? _save : null),
        onStepCancel: _step > 0 ? () => setState(() => _step--) : null,
        controlsBuilder: (context, details) => Padding(
          padding: const EdgeInsets.only(top: 16),
          child: Row(
            children: [
              FilledButton(
                onPressed: details.onStepContinue,
                child: Text(_step < 2 ? 'Next' : 'Save routine'),
              ),
              if (_step > 0)
                TextButton(
                  onPressed: details.onStepCancel,
                  child: const Text('Back'),
                ),
            ],
          ),
        ),
        steps: [
          Step(
            title: const Text('When does it start?'),
            isActive: _step >= 0,
            content: Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton.icon(
                icon: const Icon(Icons.schedule),
                label: Text(
                  _time.format(context),
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                onPressed: () async {
                  final picked = await showTimePicker(
                    context: context,
                    initialTime: _time,
                  );
                  if (picked != null) setState(() => _time = picked);
                },
              ),
            ),
          ),
          Step(
            title: const Text('How long?'),
            isActive: _step >= 1,
            content: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final m in kPresetMinutes)
                  ChoiceChip(
                    label: Text('$m min'),
                    selected: _duration == m,
                    onSelected: (_) => setState(() => _duration = m),
                  ),
              ],
            ),
          ),
          Step(
            title: const Text('Which days?'),
            isActive: _step >= 2,
            content: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (var i = 0; i < 7; i++)
                  CheckboxListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    title: Text(_dayNames[i]),
                    value: _daysMask & (1 << i) != 0,
                    onChanged: (on) => setState(() {
                      _daysMask = on == true
                          ? _daysMask | (1 << i)
                          : _daysMask & ~(1 << i);
                    }),
                  ),
                if (!_valid)
                  Text(
                    'Pick at least one day.',
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
