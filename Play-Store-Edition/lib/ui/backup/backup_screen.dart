import '../../platform/build_variant.dart';
import 'package:flutter/material.dart';
import '../../platform/stats.dart';
import '../../session/backup.dart';
import '../../session/routine.dart';
import '../design/companion.dart';

/// Export and import (ARCHITECTURE.md §9).
class BackupScreen extends StatefulWidget {
  const BackupScreen({super.key});

  @override
  State<BackupScreen> createState() => _BackupScreenState();
}

class _BackupScreenState extends State<BackupScreen> {
  final _files = BackupFiles();
  final _routines = RoutineRepo();
  final _stats = Stats();
  bool _busy = false;

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
    } on BackupFormatException catch (e) {
      _say(e.message);
    } catch (e) {
      _say('Could not read that file.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _say(String message) {
    if (!mounted) return;
    companionNotice(context, message);
  }

  Future<void> _export() => _run(() async {
    final payload = BackupPayload(
      exportedAt: DateTime.now(),
      routines: await _routines.load(),
      sessions: await _stats.recentSessions(limit: 10000),
    );
    final written = await _files.pickAndWrite(payload.encode());
    _say(written == null ? 'Export cancelled.' : 'Backup saved.');
  });

  Future<void> _import() => _run(() async {
    final source = await _files.pickAndRead();
    if (source == null) return _say('Import cancelled.');

    final payload = decodeBackup(source);
    final outcome = mergeBackup(await _routines.load(), payload.routines);

    if (!mounted) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (_) => _ImportDialog(payload: payload, outcome: outcome),
    );
    if (confirmed != true) return;

    await _routines.save(outcome.routines);
    await _files.restoreSessions(payload.sessions);
    _say('Backup imported.');
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Backup')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const CompanionBanner(
            title: 'Keep your rhythm safe.',
            message: 'Take your routines and focus history with you.',
            mood: CompanionMood.help,
          ),
          const SizedBox(height: 20),
          Text(
            kPlayEdition
                ? 'Export your routines and focus history to a file you choose. FocusBro does not upload the file.'
                : 'One JSON file holding your routines and your session history. It '
                      'goes wherever you point the file picker and nowhere else — '
                      'FocusBro has no server, and the only thing it uses the network '
                      'for is the Adult Shield filter.',
            style: theme.textTheme.bodyMedium,
          ),
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: _busy ? null : _export,
            icon: const Icon(Icons.save_alt),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(52),
            ),
            label: const Text('Export backup'),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: _busy ? null : _import,
            icon: const Icon(Icons.folder_open),
            style: OutlinedButton.styleFrom(
              minimumSize: const Size.fromHeight(52),
            ),
            label: const Text('Import backup'),
          ),
          const SizedBox(height: 24),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('How import behaves', style: theme.textTheme.titleSmall),
                  const SizedBox(height: 8),
                  Text(
                    'An import can add routines and make existing ones stricter '
                    'straight away. It can never weaken or delete one — '
                    'otherwise restoring an old backup would be a one-tap way '
                    'around the five-minute wait. Anything held back is listed '
                    'before you confirm.',
                    style: theme.textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ImportDialog extends StatelessWidget {
  const _ImportDialog({required this.payload, required this.outcome});

  final BackupPayload payload;
  final MergeOutcome outcome;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final when = payload.exportedAt;
    return CompanionDialog(
      title: const Text('Import this backup?'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Made ${when.day}/${when.month}/${when.year} · '
              '${payload.routines.length} routines · '
              '${payload.sessions.length} sessions',
              style: theme.textTheme.bodySmall,
            ),
            if (!outcome.isClean) ...[
              const SizedBox(height: 16),
              Text(
                'Held back by the ratchet',
                style: theme.textTheme.titleSmall,
              ),
              const SizedBox(height: 8),
              for (final reason in outcome.heldBack)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('•  '),
                      Expanded(
                        child: Text(reason, style: theme.textTheme.bodySmall),
                      ),
                    ],
                  ),
                ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text('Import'),
        ),
      ],
    );
  }
}
