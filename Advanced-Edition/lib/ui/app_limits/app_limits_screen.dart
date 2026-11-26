import '../../platform/build_variant.dart';
import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../session.dart';
import '../design/companion.dart';

class AppLimitsScreen extends StatefulWidget {
  const AppLimitsScreen({super.key, this.allowedApps = const []});
  final List<DockApp> allowedApps;
  @override
  State<AppLimitsScreen> createState() => _AppLimitsScreenState();
}

class _AppLimitsScreenState extends State<AppLimitsScreen> {
  static const _channel = MethodChannel('focusbro/appLimits');
  static const _owner = MethodChannel('focusbro/owner');
  Map<String, dynamic> _state = {};
  String _search = '';
  String _filter = 'All';
  String? _error;
  bool _loading = true;
  bool _busy = false;
  Timer? _timer;
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 10), (_) => _refresh());
    _lifecycle = AppLifecycleListener(onResume: _refresh);
  }

  @override
  void dispose() {
    _timer?.cancel();
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_busy) return;
    if (defaultTargetPlatform != TargetPlatform.android) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = 'App limits are available on Android.';
        });
      }
      return;
    }
    await _request('status');
  }

  Future<void> _request(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final result = await _channel.invokeMapMethod<String, dynamic>(
        method,
        arguments,
      );
      if (mounted) {
        setState(() {
          _state = result ?? {};
          _error = null;
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() => _error = e.message ?? 'Could not update app limits.');
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = 'App limits are unavailable. Please try again.',
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _loading = false;
          _busy = false;
        });
      }
    }
  }

  Future<void> _edit(Map<String, dynamic> app, bool timed) async {
    final current = (app['dailyMinutes'] as num?)?.toInt() ?? 0;
    final minutes = await showDialog<int>(
      context: context,
      builder: (context) => _DurationDialog(
        initialMinutes: timed
            ? 30
            : current > 0
            ? current
            : 30,
        title: timed
            ? 'Take a break from ${app['label']}'
            : 'Daily time for ${app['label']}',
        timed: timed,
        allowRemove: !timed && current > 0,
      ),
    );
    if (minutes == null || !mounted) return;
    await _request(timed ? 'lockFor' : 'setDaily', {
      'package': app['package'],
      'minutes': minutes,
    });
  }

  String _time(int ms) => MaterialLocalizations.of(context).formatTimeOfDay(
    TimeOfDay.fromDateTime(DateTime.fromMillisecondsSinceEpoch(ms)),
  );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final now = DateTime.now().millisecondsSinceEpoch;
    final rows = ((_state['apps'] as List?) ?? [])
        .map((e) => Map<String, dynamic>.from(e as Map))
        .where(
          (e) => '${e['label']} ${e['package']}'.toLowerCase().contains(
            _search.toLowerCase(),
          ),
        )
        .where(
          (e) => switch (_filter) {
            'Limited' =>
              (e['dailyMinutes'] as num? ?? 0) > 0 ||
                  (e['breakUseMinutes'] as num? ?? 0) > 0,
            'Blocked now' =>
              e['blocked'] == true ||
                  e['nightBlocked'] == true ||
                  (e['lockedUntil'] as num? ?? 0) > now ||
                  (e['exhaustedUntil'] as num? ?? 0) > now ||
                  (e['breakUntil'] as num? ?? 0) > now,
            'Allowed in focus' => false,
            _ => true,
          },
        )
        .toList();
    return Scaffold(
      appBar: AppBar(
        title: const Text('Apps'),
        actions: [
          IconButton(
            tooltip: 'Refresh',
            onPressed: _busy ? null : _refresh,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(20),
              children: [
                const CompanionBanner(
                  title: 'Less scroll. More soul.',
                  message: 'Give each app a place in your day.',
                  mood: CompanionMood.stop,
                ),
                const SizedBox(height: 16),
                Card(
                  child: ExpansionTile(
                    title: const Text('How limits & breaks work'),
                    leading: const Icon(Icons.info_outline),
                    childrenPadding: const EdgeInsets.all(18),
                    children: [
                      const Text(
                        'Set a daily allowance or lock one app for a while. Daily usage runs from 7 AM to 7 AM, including time used before you set the limit. Once used up, the app stays locked until 7 AM.',
                      ),
                      const SizedBox(height: 8),
                      if (!kPlayEdition)
                        const Text(
                          'Automatic breaks: Facebook, Reddit, Instagram and Snapchat get a 15-minute break after 15 minutes of continuous use. YouTube gets a 2-minute break after 30 minutes. Each app has its own repeating cycle. Switching apps or turning the screen off starts a fresh use period; an active break keeps running.',
                        ),
                      const SizedBox(height: 8),
                      Text(
                        kPlayEdition
                            ? 'Only the apps you select are restricted. Phone and essential system apps are excluded.'
                            : 'Essential and permanently allowed apps are excluded. Night Guard and full-phone focus still apply independently.',
                        style: theme.textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
                if (_state['deviceOwner'] == false) ...[
                  const SizedBox(height: 12),
                  const Text(
                    kPlayEdition
                        ? 'Enable Accessibility to apply app limits. Display over other apps is optional and shows the illustrated block notice.'
                        : 'This phone uses accessibility and overlay blocking. Keep those permissions enabled for app limits to work.',
                  ),
                ],
                if (_state['exactAlarms'] == false)
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.alarm),
                      title: const Text('Enable exact alarms'),
                      subtitle: const Text(
                        'Needed to release app locks on time, including at 7 AM.',
                      ),
                      trailing: const Icon(Icons.open_in_new),
                      onTap: () =>
                          _owner.invokeMethod('fix', {'id': 'exactAlarms'}),
                    ),
                  ),
                if (_state['usageGranted'] == false)
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.timer_outlined),
                      title: const Text('Enable usage access'),
                      subtitle: const Text(
                        kPlayEdition
                            ? 'Usage Access measures app time. Daily limits pause when access is unavailable.'
                            : 'Needed for automatic breaks and daily app time. Apps with these rules stay paused while usage access is unavailable.',
                      ),
                      trailing: const Icon(Icons.open_in_new),
                      onTap: () =>
                          _owner.invokeMethod('fix', {'id': 'usageStats'}),
                    ),
                  ),
                if (_error != null ||
                    (_state['error'] as String? ?? '').isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: Text(
                      _error ?? _state['error'] as String,
                      style: TextStyle(color: theme.colorScheme.error),
                    ),
                  ),
                const SizedBox(height: 20),
                TextField(
                  onChanged: (v) => setState(() => _search = v),
                  decoration: const InputDecoration(
                    prefixIcon: Icon(Icons.search),
                    hintText: 'Find an app',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 16),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    for (final filter in const [
                      'All',
                      'Limited',
                      'Blocked now',
                      'Allowed in focus',
                    ])
                      ChoiceChip(
                        label: Text(filter),
                        selected: _filter == filter,
                        onSelected: (_) => setState(() => _filter = filter),
                      ),
                  ],
                ),
                const SizedBox(height: 16),
                if (_filter == 'Allowed in focus') ...[
                  const Text(
                    'These apps remain available during full focus. This list reflects the installed phone setup.',
                  ),
                  const SizedBox(height: 12),
                  for (final app in {
                    for (final app in widget.allowedApps) app.id: app,
                  }.values)
                    if (app.label.toLowerCase().contains(_search.toLowerCase()))
                      Card(
                        child: ListTile(
                          leading: Icon(app.icon),
                          title: Text(app.label),
                          subtitle: Text(
                            app.available
                                ? 'Available during focus'
                                : 'Not installed on this phone',
                          ),
                          trailing: Icon(
                            app.available
                                ? Icons.check_circle_outline
                                : Icons.remove_circle_outline,
                          ),
                        ),
                      ),
                  if (widget.allowedApps.isEmpty)
                    const Text(
                      'Open this tab from FocusBro to load this phone’s allowed apps.',
                    ),
                ],
                if (rows.isEmpty && _filter != 'Allowed in focus')
                  const Padding(
                    padding: EdgeInsets.all(20),
                    child: Text('No matching apps.'),
                  ),
                for (final app in rows)
                  Builder(
                    builder: (context) {
                      final limit = (app['dailyMinutes'] as num).toInt();
                      final used = (app['usedMs'] as num).toInt();
                      final until = (app['lockedUntil'] as num).toInt();
                      final exhausted = (app['exhaustedUntil'] as num).toInt();
                      final breakUse =
                          (app['breakUseMinutes'] as num?)?.toInt() ?? 0;
                      final breakMinutes =
                          (app['breakMinutes'] as num?)?.toInt() ?? 0;
                      final breakUntil =
                          (app['breakUntil'] as num?)?.toInt() ?? 0;
                      final continuousUsed =
                          (app['continuousUsedMs'] as num?)?.toInt() ?? 0;
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: Card(
                          child: ExpansionTile(
                            key: PageStorageKey('app:${app['package']}'),
                            tilePadding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 8,
                            ),
                            leading: CircleAvatar(
                              child: Text(
                                (app['label'] as String).isEmpty
                                    ? '?'
                                    : (app['label'] as String)
                                          .substring(0, 1)
                                          .toUpperCase(),
                              ),
                            ),
                            title: Text(app['label'] as String),
                            subtitle: Text(
                              app['nightBlocked'] == true
                                  ? 'Night blocking · until 8 AM'
                                  : app['blocked'] == true ||
                                        until > now ||
                                        exhausted > now ||
                                        breakUntil > now
                                  ? 'App lock active · See details'
                                  : limit > 0
                                  ? '${used ~/ 60000} of $limit min today'
                                  : breakUse > 0
                                  ? '$breakUse min use · $breakMinutes min break'
                                  : 'No daily limit',
                            ),
                            children: [
                              Padding(
                                padding: const EdgeInsets.all(16),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    if (kPlayEdition)
                                      SwitchListTile(
                                        contentPadding: EdgeInsets.zero,
                                        title: const Text('Block during focus'),
                                        value: app['focusBlocked'] == true,
                                        onChanged: _busy
                                            ? null
                                            : (enabled) =>
                                                  _request('setFocusBlocked', {
                                                    'package': app['package'],
                                                    'enabled': enabled,
                                                  }),
                                      ),
                                    if (true &&
                                        (limit > 0 ||
                                            until > now ||
                                            exhausted > now))
                                      TextButton(
                                        onPressed: _busy
                                            ? null
                                            : () => _request(
                                                'clearAppRestrictions',
                                                {'package': app['package']},
                                              ),
                                        child: const Text('Clear app limits'),
                                      ),
                                    const SectionLabel('Daily allowance'),
                                    Text(
                                      limit > 0
                                          ? '${used ~/ 60000} of $limit min used today'
                                          : 'No daily limit',
                                    ),
                                    if (limit > 0)
                                      Padding(
                                        padding: const EdgeInsets.symmetric(
                                          vertical: 10,
                                        ),
                                        child: LinearProgressIndicator(
                                          value: (used / (limit * 60000)).clamp(
                                            0,
                                            1,
                                          ),
                                        ),
                                      ),
                                    if (exhausted > now)
                                      Text(
                                        'Daily allowance used up · available at ${_time(exhausted)}',
                                      ),
                                    if (app['nightBlocked'] == true)
                                      const Padding(
                                        padding: EdgeInsets.only(top: 8),
                                        child: Text(
                                          'Night blocking · until 8 AM. Other app limits still apply.',
                                        ),
                                      ),
                                    if (until > now)
                                      Text(
                                        'Timed lock · ${((until - now) / 60000).ceil()} min left (until ${_time(until)})',
                                      ),
                                    if (breakUse > 0)
                                      const SectionLabel('Repeating breaks'),
                                    if (breakUse > 0) ...[
                                      const SizedBox(height: 8),
                                      Text(
                                        '$breakUse min continuous use → $breakMinutes min break, repeating',
                                      ),
                                      Text(
                                        breakUntil > now
                                            ? 'Automatic break · ${((breakUntil - now) / 60000).ceil()} min left (until ${_time(breakUntil)})'
                                            : '${continuousUsed ~/ 60000} of $breakUse min in this use period',
                                      ),
                                    ],
                                    const SizedBox(height: 8),
                                    Wrap(
                                      spacing: 8,
                                      children: [
                                        OutlinedButton.icon(
                                          onPressed: _busy || exhausted > now
                                              ? null
                                              : () => _edit(app, false),
                                          icon: const Icon(
                                            Icons.hourglass_bottom,
                                            size: 18,
                                          ),
                                          label: Text(
                                            limit > 0
                                                ? 'Edit daily limit'
                                                : 'Daily limit',
                                          ),
                                        ),
                                        FilledButton.tonalIcon(
                                          onPressed: _busy || until > now
                                              ? null
                                              : () => _edit(app, true),
                                          icon: const Icon(
                                            Icons.lock_clock_outlined,
                                            size: 18,
                                          ),
                                          label: const Text('Lock for a while'),
                                        ),
                                      ],
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
              ],
            ),
    );
  }
}

class _DurationDialog extends StatefulWidget {
  const _DurationDialog({
    required this.initialMinutes,
    required this.title,
    required this.timed,
    required this.allowRemove,
  });
  final int initialMinutes;
  final String title;
  final bool timed;
  final bool allowRemove;
  @override
  State<_DurationDialog> createState() => _DurationDialogState();
}

class _DurationDialogState extends State<_DurationDialog> {
  final _form = GlobalKey<FormState>();
  late final _value = TextEditingController(text: '${widget.initialMinutes}');
  @override
  void dispose() {
    _value.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => CompanionDialog(
    mood: widget.timed ? CompanionMood.rest : CompanionMood.help,
    title: Text(widget.title),
    content: SingleChildScrollView(
      child: Form(
        key: _form,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextFormField(
              controller: _value,
              autofocus: true,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(
                labelText: 'Minutes',
                helperText: '1 to 1440 minutes',
              ),
              validator: (v) {
                final n = int.tryParse(v ?? '');
                return n == null || n < 1 || n > 1440
                    ? 'Enter 1–1440 minutes'
                    : null;
              },
            ),
            const SizedBox(height: 16),
            Text(
              widget.timed
                  ? (kPlayEdition
                        ? 'The lock starts immediately. You can end it from this app’s details.'
                        : 'The lock starts immediately and cannot be ended early.')
                  : (kPlayEdition
                        ? 'Daily usage resets at 7 AM. You can clear this limit from the app’s details.'
                        : 'Once this allowance is used up, the app cannot be unlocked or its limit increased until 7 AM.'),
            ),
          ],
        ),
      ),
    ),
    actions: [
      if (widget.allowRemove)
        TextButton(
          onPressed: () => Navigator.pop(context, 0),
          child: const Text('Remove limit'),
        ),
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancel'),
      ),
      FilledButton(
        onPressed: () {
          if (_form.currentState!.validate()) {
            Navigator.pop(context, int.parse(_value.text));
          }
        },
        child: Text(widget.timed ? 'Lock app' : 'Save limit'),
      ),
    ],
  );
}
