import '../../platform/build_variant.dart';
import 'package:flutter/material.dart';
import '../../platform/diagnostics.dart';
import '../backup/backup_screen.dart';
import 'protection_cards.dart';
import '../design/companion.dart';

enum SetupSection { permissions, protection, diagnostics }

/// Setup and diagnostics (ARCHITECTURE.md §5, §15 phase 5).
///
/// Every permission states what it does, why it is needed, and what breaks
/// without it. Nothing here is a dark pattern: a denied permission gets a plain
/// description of the consequence, not a nag.
class SetupScreen extends StatefulWidget {
  const SetupScreen({super.key, this.section = SetupSection.permissions});
  final SetupSection section;

  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> {
  // Only the separate WeakFocusBro build exposes owner deactivation.
  static const _allowOwnerDeactivation = bool.fromEnvironment('WEAK_FOCUSBRO');
  final _diagnostics = Diagnostics();
  Map<String, bool> _health = const {};
  Map<String, dynamic> _oem = const {};
  Map<String, dynamic> _probe = const {'state': 'none'};
  TetherStatus _tether = const TetherStatus.unsupported();
  Map<String, dynamic> _shield = const {};
  bool _deactivating = false;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final health = await _diagnostics.health();
    final oem = await _diagnostics.oem();
    final probe = await _diagnostics.probeResult();
    final tether = await _diagnostics.tetherStatus();
    final shield = await _diagnostics.shieldStatus();
    if (!mounted) return;
    setState(() {
      _health = health;
      _oem = oem;
      _probe = probe;
      _tether = tether;
      _shield = shield;
    });
  }

  Future<void> _deactivateOwner() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => CompanionDialog(
        mood: CompanionMood.stop,
        title: const Text('Deactivate FocusBro?'),
        content: const Text(
          'This removes Device Owner protection, stops FocusBro policies, and '
          'makes the app uninstallable. Android will then ask if you want to '
          'delete FocusBro.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Deactivate'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _deactivating = true);
    final released = await _diagnostics.relinquish();
    if (!mounted) return;
    if (!released) {
      setState(() => _deactivating = false);
      companionNotice(
        context,
        'Android could not remove Device Owner. Try again.',
      );
      return;
    }

    await _diagnostics.openUninstall();
    if (!mounted) return;
    setState(() {
      _deactivating = false;
      _health = {..._health, 'deviceOwner': false};
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(switch (widget.section) {
          SetupSection.permissions => 'Permissions & setup',
          SetupSection.protection => 'Device protection',
          SetupSection.diagnostics => 'Advanced diagnostics',
        }),
        actions: [
          IconButton(
            onPressed: _refresh,
            icon: const Icon(Icons.refresh),
            tooltip: 'Re-check',
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          CompanionBanner(
            title: switch (widget.section) {
              SetupSection.permissions => 'Ready when you are.',
              SetupSection.protection => 'Keep your space clear.',
              SetupSection.diagnostics => 'A closer look.',
            },
            message: switch (widget.section) {
              SetupSection.permissions =>
                'Check the permissions that keep your focus working.',
              SetupSection.protection =>
                'Your device protection and connectivity rules in one place.',
              SetupSection.diagnostics =>
                'Check background behavior and phone-specific settings.',
            },
            mood: CompanionMood.help,
          ),
          const SizedBox(height: 20),
          if (!_diagnostics.supported)
            Text(
              'This device runs FocusBro as a timer and a focus log. '
              'Locking is an Android-only capability.',
              style: theme.textTheme.bodyMedium,
            )
          else ...[
            if (widget.section == SetupSection.permissions) ...[
              for (final p in kPermissions)
                if ((!kPlayEdition ||
                        const {
                          'accessibility',
                          'usageStats',
                          'overlay',
                          'exactAlarms',
                          'notifications',
                        }.contains(p.id)) &&
                    (!p.fallbackOnly || !(_health['deviceOwner'] ?? false)))
                  _PermissionTile(
                    info: p,
                    granted: _health[p.id] ?? false,
                    onFix: () async {
                      await _diagnostics.fix(p.id);
                      await _refresh();
                    },
                  ),
              const SizedBox(height: 24),
              if (_health['root'] == true) ...[
                _RootCard(
                  everythingGranted: kPermissions
                      .where((p) => p.id != 'deviceOwner')
                      .every((p) => _health[p.id] ?? false),
                  onGrant: () async {
                    final ok = await _diagnostics.rootGrantAll();
                    await _refresh();
                    if (!context.mounted) return;
                    companionNotice(
                      context,
                      ok
                          ? 'Granted.'
                          : 'Root declined, or a command was refused.',
                    );
                  },
                ),
                const SizedBox(height: 24),
              ],
            ],
            if (widget.section == SetupSection.diagnostics) ...[
              _ProbeCard(
                probe: _probe,
                onStart: () async {
                  await _diagnostics.startProbe(15);
                  await _refresh();
                },
              ),
              const SizedBox(height: 24),
              if (_health['deviceOwner'] == true &&
                  _health['statusBarControl'] == false)
                Padding(
                  padding: const EdgeInsets.only(bottom: 24),
                  child: Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(
                        'Your phone ignored the request to hide the status bar '
                        'during sessions, so the notification shade stays '
                        'reachable. Everything else about the lock is unaffected.',
                        style: theme.textTheme.bodySmall,
                      ),
                    ),
                  ),
                ),
              if (_oem.isNotEmpty)
                _OemCard(oem: _oem, diagnostics: _diagnostics),
            ],
          ],
          if (widget.section == SetupSection.permissions) ...[
            const SizedBox(height: 24),
            Card(
              child: ListTile(
                leading: const Icon(Icons.save_alt),
                title: const Text('Backup'),
                subtitle: const Text('Export or restore routines and history'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => Navigator.of(
                  context,
                ).push(MaterialPageRoute(builder: (_) => const BackupScreen())),
              ),
            ),
          ],
          if (widget.section == SetupSection.protection &&
              _tether.enforceable) ...[
            const SizedBox(height: 24),
            _TetherCard(
              status: _tether,
              onFix: () async {
                final after = await _diagnostics.tetherSync();
                if (!context.mounted) return;
                setState(() => _tether = after);
                companionNotice(
                  context,
                  after.restricted
                      ? 'Inside the nightly window — tethering stays off until ${after.releaseLabel}.'
                      : 'Tethering is allowed.',
                );
              },
            ),
          ],
          if (widget.section == SetupSection.protection &&
              _diagnostics.supported) ...[
            const SizedBox(height: 24),
            ShieldCard(
              status: _shield,
              diagnostics: _diagnostics,
              onChanged: _refresh,
            ),
          ],
          if (widget.section == SetupSection.permissions &&
              _allowOwnerDeactivation &&
              _health['deviceOwner'] == true) ...[
            const SizedBox(height: 24),
            Card(
              color: theme.colorScheme.errorContainer,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Remove FocusBro',
                      style: theme.textTheme.titleSmall?.copyWith(
                        color: theme.colorScheme.onErrorContainer,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Deactivate Device Owner and open Android’s uninstall '
                      'prompt. All FocusBro restrictions are removed first.',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onErrorContainer,
                      ),
                    ),
                    const SizedBox(height: 12),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        onPressed: _deactivating ? null : _deactivateOwner,
                        icon: _deactivating
                            ? const SizedBox.square(
                                dimension: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.admin_panel_settings_outlined),
                        label: Text(
                          _deactivating
                              ? 'Deactivating…'
                              : 'Deactivate owner and uninstall',
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
          const SizedBox(height: 24),
          if (widget.section == SetupSection.permissions) _NotRequestedCard(),
          const SizedBox(height: 32),
        ],
      ),
    );
  }
}

class _PermissionTile extends StatelessWidget {
  const _PermissionTile({
    required this.info,
    required this.granted,
    required this.onFix,
  });

  final PermissionInfo info;
  final bool granted;
  final VoidCallback onFix;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colour = granted
        ? theme.colorScheme.primary
        : info.critical
        ? theme.colorScheme.error
        : theme.colorScheme.onSurfaceVariant;

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Semantics(
                  label: granted ? 'Granted' : 'Not granted',
                  child: Icon(
                    granted ? Icons.check_circle : Icons.radio_button_unchecked,
                    color: colour,
                    size: 20,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(info.title, style: theme.textTheme.titleSmall),
                ),
                if (!granted)
                  TextButton(onPressed: onFix, child: const Text('Grant')),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              kPlayEdition
                  ? switch (info.id) {
                      'accessibility' =>
                        'Detects which app opens and applies your selected blocking rules.',
                      'overlay' =>
                        'Shows an optional illustrated notice over blocked apps.',
                      'usageStats' =>
                        'Measures foreground time for daily limits and charts.',
                      _ => info.why,
                    }
                  : info.why,
              style: theme.textTheme.bodySmall,
            ),
            if (!granted) ...[
              const SizedBox(height: 8),
              Text(
                kPlayEdition
                    ? switch (info.id) {
                        'accessibility' =>
                          'Without it: FocusBro still works as a timer.',
                        'overlay' =>
                          'Without it: App blocking still works without the illustrated notice.',
                        'usageStats' =>
                          'Without it: Daily limits and usage charts are paused.',
                        _ => 'Without it: ${info.breaks}',
                      }
                    : 'Without it: ${info.breaks}',
                style: theme.textTheme.bodySmall?.copyWith(color: colour),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Edge cases 31-33: proves whether the OEM actually let us survive a
/// background period, instead of asking the user to trust that it did.
class _ProbeCard extends StatelessWidget {
  const _ProbeCard({required this.probe, required this.onStart});

  final Map<String, dynamic> probe;
  final VoidCallback onStart;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final state = probe['state'] as String? ?? 'none';
    final lateness = ((probe['latenessMs'] as num?) ?? 0) ~/ 1000;

    final (message, colour) = switch (state) {
      'pending' => (
        'Running. Lock your phone, leave it for 15 minutes, then come back.',
        theme.colorScheme.onSurfaceVariant,
      ),
      'onTime' => (
        'Passed — FocusBro survived 15 minutes in the background. Timers and '
            'routines will be accurate.',
        theme.colorScheme.primary,
      ),
      'late' => (
        'Fired ${lateness}s late. Your phone is throttling FocusBro: '
            'countdowns may stall and routines may start late. The OEM steps '
            'below usually fix it.',
        theme.colorScheme.error,
      ),
      'missed' => (
        'Never fired. Your phone killed FocusBro in the background. Routines '
            'will be unreliable until the OEM steps below are applied.',
        theme.colorScheme.error,
      ),
      _ => (
        'Checks whether your phone lets FocusBro run in the background. '
            'Takes 15 minutes and needs no attention.',
        theme.colorScheme.onSurfaceVariant,
      ),
    };

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Background survival check',
              style: theme.textTheme.titleSmall,
            ),
            const SizedBox(height: 8),
            Text(
              message,
              style: theme.textTheme.bodySmall?.copyWith(color: colour),
            ),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: state == 'pending' ? null : onStart,
                child: Text(state == 'none' ? 'Run check' : 'Run again'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _OemCard extends StatelessWidget {
  const _OemCard({required this.oem, required this.diagnostics});

  final Map<String, dynamic> oem;
  final Diagnostics diagnostics;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final steps = (oem['steps'] as List?)?.cast<String>() ?? const [];

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '${oem['name']} background settings',
              style: theme.textTheme.titleSmall,
            ),
            const SizedBox(height: 4),
            Text(
              'These affect timer accuracy, not whether the lock holds — the '
              'system holds the lock either way.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            for (final step in steps)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('•  '),
                    Expanded(
                      child: Text(step, style: theme.textTheme.bodySmall),
                    ),
                  ],
                ),
              ),
            if (oem['hasShortcut'] == true)
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  onPressed: diagnostics.openOemSettings,
                  child: const Text('Open that screen'),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// §16: the privacy claim, stated as a checkable list rather than a promise.
class _NotRequestedCard extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Never requested', style: theme.textTheme.titleSmall),
            const SizedBox(height: 4),
            Text(
              'Your focus history, routines and charts stay on this device. '
              'FocusBro has no content-filtering service.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              kPlayEdition
                  ? 'This edition works offline and has no internet permission.'
                  : kNetworkUse,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 10),
            for (final item in kNotRequested)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Row(
                  children: [
                    Icon(
                      Icons.block,
                      size: 14,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                    const SizedBox(width: 8),
                    Text(item, style: theme.textTheme.bodySmall),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// Root is offered, never assumed, never required (§20).
class _RootCard extends StatelessWidget {
  const _RootCard({required this.everythingGranted, required this.onGrant});

  final bool everythingGranted;
  final VoidCallback onGrant;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      color: theme.colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.terminal, size: 20),
                const SizedBox(width: 10),
                Text('Root detected', style: theme.textTheme.titleSmall),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              everythingGranted
                  ? 'Blocked apps are closed rather than covered, and if you '
                        'switch the accessibility service off mid-session it '
                        'comes back within a few seconds.'
                  : 'FocusBro can grant its own permissions instead of walking '
                        'you through six Settings screens. Blocking also gets '
                        'stronger: blocked apps are closed outright, and turning '
                        'the accessibility service off no longer ends a session.',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            Text(
              'Root is never used to change anything outside FocusBro, and '
              'never to fake device owner — that means editing system policy '
              'files, which bootloops phones.',
              style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            if (!everythingGranted) ...[
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton(
                  onPressed: onGrant,
                  child: const Text('Grant everything'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// The nightly tethering window, with the one-tap fix for a missed release.
/// Exists because a dropped 06:00 alarm once left the user unable to tether and
/// the only visible way out was handing back device owner (§23.4).
class _TetherCard extends StatelessWidget {
  const _TetherCard({required this.status, required this.onFix});

  final TetherStatus status;
  final VoidCallback onFix;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final s = status;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  s.restricted
                      ? Icons.wifi_tethering_off
                      : Icons.wifi_tethering,
                  size: 20,
                  color: s.restricted
                      ? theme.colorScheme.error
                      : theme.colorScheme.primary,
                ),
                const SizedBox(width: 10),
                Text('Tethering', style: theme.textTheme.titleSmall),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              s.testingConnectivity
                  ? s.restricted
                        ? 'Android still reports tethering restricted. Tap Re-check now.'
                        : 'Testing build: hotspot and USB tethering are allowed at all hours, including during full lock. FocusBro does not disable USB or wireless debugging.'
                  : s.restricted
                  ? 'Blocked right now — the nightly window runs '
                        '${s.cutoffLabel} to ${s.releaseLabel}.'
                  : 'Allowed. Cut every night at ${s.cutoffLabel}, back at '
                        '${s.releaseLabel}. USB tethering is the intended way to '
                        'share internet; hotspot is blocked during sessions.',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            Text(
              s.testingConnectivity
                  ? 'App blocking and automatic app breaks still apply. Android USB or wireless debugging must be enabled and the computer authorized.'
                  : 'If tethering is still blocked after ${s.releaseLabel}, an alarm '
                        'was dropped. Tap below — it re-reads the clock. You never need '
                        'to turn FocusBro off for this.',
              style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: onFix,
                child: const Text('Re-check now'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
