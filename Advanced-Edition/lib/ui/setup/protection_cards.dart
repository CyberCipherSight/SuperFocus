import 'package:flutter/material.dart';
import '../design/companion.dart';
import '../../platform/diagnostics.dart';

/// Native VPN prevention and Night Guard status.

String _hour(int minute) {
  final h = minute ~/ 60;
  final ampm = h >= 12 ? 'PM' : 'AM';
  final h12 = h % 12 == 0 ? 12 : h % 12;
  return '$h12:${(minute % 60).toString().padLeft(2, '0')} $ampm';
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value, {this.ok});
  final String label;
  final String value;
  final bool? ok;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = switch (ok) {
      true => theme.colorScheme.primary,
      false => theme.colorScheme.error,
      null => theme.colorScheme.onSurfaceVariant,
    };
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(child: Text(label, style: theme.textTheme.bodySmall)),
          Text(value, style: theme.textTheme.bodySmall?.copyWith(color: color)),
        ],
      ),
    );
  }
}

class ShieldCard extends StatelessWidget {
  const ShieldCard({
    super.key,
    required this.status,
    required this.diagnostics,
    required this.onChanged,
  });

  final Map<String, dynamic> status;
  final Diagnostics diagnostics;
  final VoidCallback onChanged;

  bool _b(String k) => status[k] == true;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final owner = _b('deviceOwner');
    final protected = _b('vpnConfigurationLocked');
    final failed = (status['shieldFailed'] as List?) ?? const [];
    final vpnApps = (status['vpnApps'] as Map?) ?? const {};
    final detected = (vpnApps['detected'] as List?) ?? const [];
    final lastError = status['lastError']?.toString() ?? '';

    final headline = !owner
        ? 'Not enforced — needs device owner'
        : protected
        ? 'Protected'
        : 'VPN prevention needs attention';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  protected ? Icons.shield : Icons.shield_outlined,
                  size: 20,
                  color: protected
                      ? theme.colorScheme.primary
                      : theme.colorScheme.error,
                ),
                const SizedBox(width: 10),
                Text(
                  'VPN bypass prevention',
                  style: theme.textTheme.titleSmall,
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(headline, style: theme.textTheme.bodyMedium),
            const SizedBox(height: 10),
            if (owner) ...[
              _Row(
                'VPN',
                _b('vpnConfigurationLocked') ? 'Blocked' : 'Not blocked',
                ok: _b('vpnConfigurationLocked'),
              ),
              _Row(
                'Clock',
                _b('clockLocked') ? 'Locked, automatic' : 'User-editable',
                ok: _b('clockLocked'),
              ),
            ] else
              _Row('Device owner', 'Not provisioned — no VPN lock', ok: false),
            if (detected.isNotEmpty)
              _Row(
                'Other VPN apps',
                '${detected.length} installed — ${owner ? 'unusable' : 'not blocked without device owner'}',
                ok: owner,
              ),
            if (failed.isNotEmpty)
              _Row('Could not suspend', failed.join(', '), ok: false),
            if (lastError.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                lastError,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.error,
                ),
              ),
            ],
            const SizedBox(height: 8),
            Text(
              'Prevents VPN apps from bypassing focus restrictions. '
              'FocusBro does not filter DNS or route your internet traffic.',
              style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 4),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: () async {
                  await diagnostics.protectionSync();
                  onChanged();
                },
                child: const Text('Re-apply'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class NightGuardCard extends StatelessWidget {
  const NightGuardCard({
    super.key,
    required this.status,
    required this.diagnostics,
    required this.onChanged,
  });

  final Map<String, dynamic> status;
  final Diagnostics diagnostics;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final active = status['active'] == true;
    final tier = status['tier']?.toString() ?? 'advisory';
    final start = (status['startMinute'] as num?)?.toInt() ?? 21 * 60;
    final end = (status['endMinute'] as num?)?.toInt() ?? 8 * 60;
    final next = (status['nextChangeUtc'] as num?)?.toInt() ?? 0;
    final browsers = (status['browsers'] as List?) ?? const [];
    final explicit = (status['explicit'] as List?) ?? const [];
    final failed = (status['failed'] as List?) ?? const [];
    final enforced = status['fullyEnforced'] == true;

    final strength = switch (tier) {
      'owner' => 'Apps are suspended by device policy.',
      'root' => 'Apps are suspended through root; verified per app.',
      'accessibility' =>
        'Apps are closed on sight. Not as strong as device owner.',
      _ => 'Cannot enforce on this device — shown for information only.',
    };
    final installedExplicit = explicit
        .where((e) => e['installed'] == true)
        .toList();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  active ? Icons.nightlight : Icons.nightlight_outlined,
                  size: 20,
                  color: tier == 'advisory'
                      ? theme.colorScheme.error
                      : theme.colorScheme.primary,
                ),
                const SizedBox(width: 10),
                Text('Night Guard', style: theme.textTheme.titleSmall),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              active
                  ? 'Night Guard active until ${_hour(end)}'
                  : 'Every day · ${_hour(start)} – ${_hour(end)}',
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 8),
            const Text(
              'Facebook, Instagram and Snapchat: 9 PM–8 AM, alongside browsers, Reddit and Telegram. Other social apps: 10:30 PM–8 AM. WhatsApp is excluded.',
            ),
            const SizedBox(height: 10),
            _Row(
              'Browsers (${browsers.length} found)',
              active
                  ? (enforced ? 'Blocked now' : 'Blocked on sight')
                  : 'Blocked at night',
              ok: tier == 'advisory' ? false : null,
            ),
            for (final e in installedExplicit)
              _Row(
                e['label'].toString(),
                e['blockedNow'] == true
                    ? 'Blocked now'
                    : e['social'] == true
                    ? 'From 10:30 PM'
                    : 'Blocked at night',
              ),
            _Row(
              'Next change',
              next == 0
                  ? '—'
                  : _hour(
                      DateTime.fromMillisecondsSinceEpoch(
                        next,
                      ).let((t) => t.hour * 60 + t.minute),
                    ),
            ),
            _Row(
              'Boundary alarm',
              status['alarmScheduled'] == true
                  ? 'Scheduled'
                  : 'Not scheduled — reconcile on launch covers it',
              ok: status['alarmScheduled'] == true ? true : null,
            ),
            if (active && tier == 'owner')
              _Row(
                'Applied',
                enforced ? 'All targets suspended' : 'Incomplete',
                ok: enforced,
              ),
            if (failed.isNotEmpty)
              _Row('Refused by Android', failed.join(', '), ok: false),
            const SizedBox(height: 8),
            Text(
              strength,
              style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            if (browsers.isNotEmpty) ...[
              const SizedBox(height: 6),
              ExpansionTile(
                tilePadding: EdgeInsets.zero,
                title: Text(
                  'Detected browsers',
                  style: theme.textTheme.labelMedium,
                ),
                children: [
                  for (final b in browsers)
                    ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(b['label'].toString()),
                      subtitle: Text(
                        '${b['verdict']} · ${(b['reasons'] as List).join('; ')}',
                      ),
                    ),
                ],
              ),
            ],
            Align(
              alignment: Alignment.centerRight,
              child: Wrap(
                spacing: 4,
                children: [
                  TextButton(
                    onPressed: () => _addPackageDialog(context),
                    child: const Text('Add app'),
                  ),
                  TextButton(
                    onPressed: () async {
                      await diagnostics.protectionSync();
                      onChanged();
                    },
                    child: const Text('Re-check now'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _addPackageDialog(BuildContext context) async {
    final controller = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => CompanionDialog(
        title: const Text('Block another app at night'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(
            hintText: 'package name, e.g. com.example.app',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Add'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await diagnostics.nightPackage(controller.text);
    onChanged();
  }
}

extension<T> on T {
  R let<R>(R Function(T) f) => f(this);
}
