import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _owner = MethodChannel('focusbro/owner');
final _modeChanges = ValueNotifier<int>(0);
void refreshPlayModeCards() => _modeChanges.value++;

String playModeLabel(String? mode) => switch (mode) {
  'strict' => 'App blocking',
  'reminder' => 'Reminders',
  _ => 'Timer only',
};

Future<Map<String, dynamic>> _status() async =>
    await _owner.invokeMapMethod<String, dynamic>('playMode') ?? {};

class PlayOnboardingGate extends StatefulWidget {
  const PlayOnboardingGate({super.key, required this.child});
  final Widget child;
  @override
  State<PlayOnboardingGate> createState() => _PlayOnboardingGateState();
}

class _PlayOnboardingGateState extends State<PlayOnboardingGate> {
  bool? _configured;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final status = await _status();
      if (mounted) setState(() => _configured = status['configured'] == true);
    } catch (_) {
      if (mounted) setState(() => _configured = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) return widget.child;
    if (_configured == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_configured == false) return PlaySetupScreen(onDone: _load);
    return widget.child;
  }
}

class PlaySetupScreen extends StatefulWidget {
  const PlaySetupScreen({super.key, this.onDone});
  final VoidCallback? onDone;
  @override
  State<PlaySetupScreen> createState() => _PlaySetupScreenState();
}

class _PlaySetupScreenState extends State<PlaySetupScreen> {
  Map<String, dynamic> _state = {};
  String _mode = 'basic';
  bool _loaded = false;
  bool _busy = false;
  String? _error;
  late final AppLifecycleListener _lifecycle;
  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onResume: _refresh);
    _refresh();
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final s = await _status();
      if (!mounted) return;
      setState(() {
        _state = s;
        if (!_loaded) _mode = s['selected'] as String? ?? 'basic';
        _loaded = true;
        _error = null;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loaded = true;
          _error = 'Could not check permissions. Try again.';
        });
      }
    }
  }

  Future<void> _save() async {
    setState(() => _busy = true);
    try {
      await _owner.invokeMethod('setPlayMode', {'mode': _mode});
      refreshPlayModeCards();
      if (!mounted) return;
      if (widget.onDone != null) {
        widget.onDone!();
      } else {
        Navigator.pop(context);
      }
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not save focus mode.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _fix(String id) async {
    try {
      final opened = await _owner.invokeMethod<bool>('fix', {'id': id});
      await _refresh();
      if (opened == false && mounted) {
        setState(
          () => _error =
              'This settings screen is unavailable. Open Android Settings manually.',
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Android could not open this setting.');
      }
    }
  }

  Widget _permission(String id, String title, String detail) {
    final states = _state['permissionStates'] as Map?;
    final status =
        states?[id] as String? ??
        (_state[id] == true ? 'ready' : 'not_granted');
    final ready = status == 'ready' || status == 'automatic';
    final label = switch (status) {
      'ready' => 'Ready',
      'automatic' => 'Built in',
      'consent_required' => 'Consent needed',
      'not_operational' => 'Enabled, not working',
      'not_restricted' => 'Not restricted',
      'restricted' => 'Restricted',
      _ => 'Not enabled',
    };
    return Card(
      child: ListTile(
        leading: Icon(ready ? Icons.check_circle_outline : Icons.info_outline),
        title: Text(title),
        subtitle: Text('$detail\n$label'),
        trailing: ready
            ? null
            : TextButton(
                onPressed: () => _fix(id),
                child: const Text('Enable'),
              ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final first = widget.onDone != null;
    return Scaffold(
      appBar: AppBar(
        title: Text(first ? 'Set up FocusBro' : 'Focus mode & permissions'),
        actions: [
          IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
        ],
      ),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(20),
              children: [
                Text(
                  'Choose how you want to focus',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 8),
                const Text(
                  'You stay in control. You can change modes, end a session or revoke permissions at any time.',
                ),
                const SizedBox(height: 16),
                for (final entry in const [
                  (
                    'basic',
                    'Timer only',
                    'Focus timer and history. No monitoring or blocking.',
                  ),
                  (
                    'reminder',
                    'Reminders',
                    'Measure usage and show dismissible reminders when selected apps reach a limit or interrupt focus.',
                  ),
                  (
                    'strict',
                    'App blocking',
                    'Interrupt selected distracting apps when they open. Accessibility is needed; other apps remain available.',
                  ),
                ])
                  Card(
                    child: ListTile(
                      leading: Icon(
                        _mode == entry.$1
                            ? Icons.radio_button_checked
                            : Icons.radio_button_off,
                      ),
                      title: Text(entry.$2),
                      subtitle: Text(entry.$3),
                      selected: _mode == entry.$1,
                      onTap: () => setState(() => _mode = entry.$1),
                    ),
                  ),
                const SizedBox(height: 16),
                if (_mode != 'basic') ...[
                  const Text(
                    'Enable the permissions for your chosen mode. Android opens each setting separately. You can skip any permission.',
                  ),
                  if (_mode == 'strict')
                    _permission(
                      'accessibility',
                      'Accessibility',
                      'Detects app changes and interrupts selected apps. Does not read screen content.',
                    ),
                  _permission(
                    'usageStats',
                    'Usage access',
                    'Measures daily app time and enables reminder mode.',
                  ),
                  _permission(
                    'overlay',
                    'Display over other apps',
                    'Optional illustrated blocking notice or dismissible reminder popup.',
                  ),
                  _permission(
                    'notifications',
                    'Notifications',
                    'Focus alerts and reminders when enabled.',
                  ),
                ] else
                  _permission(
                    'notifications',
                    'Notifications (optional)',
                    'Session countdown and completion alerts.',
                  ),
                _permission(
                  'exactAlarms',
                  'Exact alarms (optional)',
                  'Makes scheduled routines and session endings more punctual.',
                ),
                if (_mode != 'basic' && _state['sdk'] != null) ...[
                  _permission(
                    'batteryExempt',
                    'Battery optimisation (optional)',
                    'Android may delay background work. You can review battery settings; no exemption is required to use the timer.',
                  ),
                  if (_state['backgroundRestricted'] == true)
                    _permission(
                      'background',
                      'Background activity restricted',
                      'Review battery and background activity settings for FocusBro.',
                    ),
                  if (_state['manufacturer'] != null)
                    Text(
                      'Device: ${_state['manufacturer']} · Android API ${_state['sdk']}',
                    ),
                ],
                if (_mode == 'reminder')
                  const Text(
                    'Configure the repeat interval after saving your mode.',
                  ),
                if (_mode == 'strict' && _state['accessibility'] != true)
                  const Text(
                    'Without active Accessibility, blocking is unavailable. FocusBro falls back to reminders or timer only.',
                  ),
                if (_mode == 'reminder' &&
                    (_state['usageStats'] != true ||
                        (_state['notifications'] != true &&
                            _state['overlay'] != true)))
                  const Text(
                    'Reminders need Usage Access and at least one alert permission. Until then, only the timer works.',
                  ),
                if (_error != null)
                  Text(
                    _error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: _busy ? null : _save,
                  child: Text(first ? 'Continue' : 'Save mode'),
                ),
                if (first)
                  TextButton(
                    onPressed: _busy
                        ? null
                        : () {
                            setState(() => _mode = 'basic');
                            _save();
                          },
                    child: const Text('Skip and use timer only'),
                  ),
                const SizedBox(height: 12),
                const Text(
                  'On Xiaomi and other aggressive battery managers, allow background activity or autostart if reminders stop. Android may still pause background work.',
                ),
              ],
            ),
    );
  }
}

class PlayModeCard extends StatefulWidget {
  const PlayModeCard({super.key, this.active = false});
  final bool active;
  @override
  State<PlayModeCard> createState() => _PlayModeCardState();
}

class _PlayModeCardState extends State<PlayModeCard> {
  Map<String, dynamic> _state = {};
  late final AppLifecycleListener _lifecycle;
  Timer? _poll;
  @override
  void initState() {
    super.initState();
    _modeChanges.addListener(_refresh);
    _lifecycle = AppLifecycleListener(
      onResume: _resume,
      onPause: _pause,
      onHide: _pause,
    );
    _resume();
    _refresh();
  }

  void _resume() {
    _poll?.cancel();
    _refresh();
    _poll = Timer.periodic(const Duration(seconds: 5), (_) {
      if (mounted && TickerMode.valuesOf(context).enabled) _refresh();
    });
  }

  void _pause() {
    _poll?.cancel();
    _poll = null;
  }

  @override
  void dispose() {
    _pause();
    _modeChanges.removeListener(_refresh);
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final s = await _status();
      if (mounted) setState(() => _state = s);
    } catch (_) {
      if (mounted) setState(() => _state = {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final selected = _state['selected'] as String?;
    final capability = _state['effective'] as String?;
    final monitorUnavailable =
        widget.active &&
        capability == 'reminder' &&
        _state['sessionRunning'] == false &&
        _state['limitsRunning'] != true;
    final effective = monitorUnavailable ? 'basic' : capability;
    final count = _state['focusCount'] as int? ?? 0;
    return Card(
      child: Column(
        children: [
          ListTile(
            leading: const Icon(Icons.shield_outlined),
            title: Text(
              effective == null
                  ? 'Checking focus mode…'
                  : 'Active: ${playModeLabel(effective)}',
            ),
            subtitle: Text(
              effective == null
                  ? 'Checking permissions…'
                  : monitorUnavailable
                  ? 'Reminder monitor is unavailable. Timer only until the monitor starts.'
                  : selected != effective
                  ? 'Requested: ${playModeLabel(selected)}. Missing permission; using a fallback.'
                  : effective == 'basic'
                  ? 'No other apps are restricted.'
                  : '$count apps selected for focus. Edit them in Apps.',
            ),
            trailing: widget.active ? null : const Icon(Icons.chevron_right),
            onTap: widget.active
                ? null
                : () async {
                    await Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => const PlaySetupScreen(),
                      ),
                    );
                    await _refresh();
                  },
          ),
          if ((_state['monitorError'] as String? ?? '').isNotEmpty &&
              widget.active)
            Padding(
              padding: const EdgeInsets.all(12),
              child: Text(_state['monitorError'] as String),
            ),
          if ((_state['limitsError'] as String? ?? '').isNotEmpty &&
              !widget.active)
            Padding(
              padding: const EdgeInsets.all(12),
              child: Text(_state['limitsError'] as String),
            ),
          if (_state['exactAlarms'] == false)
            const Padding(
              padding: EdgeInsets.all(12),
              child: Text(
                'Exact alarms unavailable. Scheduled starts or completion alerts may be delayed.',
              ),
            ),
          if (_state['backgroundRestricted'] == true)
            const Padding(
              padding: EdgeInsets.all(12),
              child: Text(
                'Android restricts background activity. Reminders may be interrupted.',
              ),
            ),
          if (!widget.active && selected != 'basic' && selected != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                children: [
                  const Expanded(child: Text('Repeat reminders every')),
                  DropdownButton<int>(
                    value: _state['interval'] as int? ?? 5,
                    items: [1, 3, 5, 10, 15]
                        .map(
                          (v) =>
                              DropdownMenuItem(value: v, child: Text('$v min')),
                        )
                        .toList(),
                    onChanged: (v) async {
                      if (v != null) {
                        await _owner.invokeMethod('setReminderInterval', {
                          'minutes': v,
                        });
                        await _refresh();
                      }
                    },
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

Future<bool> confirmPlayStart(BuildContext context) async {
  Map<String, dynamic> s;
  try {
    s = await _status();
  } catch (_) {
    return false;
  }
  if (!context.mounted) return false;
  final effective = s['effective'];
  final selected = s['selected'];
  final count = s['focusCount'] as int? ?? 0;
  if (effective == selected && (effective == 'basic' || count > 0)) return true;
  final proceed = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(
        effective == 'basic' || count == 0
            ? 'Start a timer only?'
            : 'Start with reminders?',
      ),
      content: Text(
        count == 0
            ? 'No apps are selected for focus. This session will not interrupt other apps. Choose apps in the Apps tab first if you want restrictions.'
            : 'Your chosen permissions are not fully available. Current mode: ${playModeLabel(effective as String?)}.',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx, false),
          child: const Text('Not now'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(ctx, true),
          child: const Text('Continue'),
        ),
      ],
    ),
  );
  return proceed == true;
}
