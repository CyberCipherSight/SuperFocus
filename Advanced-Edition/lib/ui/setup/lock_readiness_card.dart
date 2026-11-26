import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../design/companion.dart';

/// Checks run alongside the native lock; this widget never delays activation.
class LockReadinessCard extends StatefulWidget {
  const LockReadinessCard({super.key});
  @override
  State<LockReadinessCard> createState() => _LockReadinessCardState();
}

class _LockReadinessCardState extends State<LockReadinessCard> {
  static const _channel = MethodChannel('focusbro/owner');
  static const _labels = {
    'notifications': 'Notifications',
    'exactAlarms': 'Exact alarms',
    'batteryExempt': 'Battery optimization exemption',
    'usageStats': 'Usage access',
    'accessibility': 'Accessibility',
    'overlay': 'Display over other apps',
  };
  List<String> _missing = [];
  bool _available = false;
  bool _loading = true;
  String? _error;
  late final AppLifecycleListener _lifecycle;
  Timer? _timer;
  @override
  void initState() {
    super.initState();
    _refresh(redirect: true);
    _lifecycle = AppLifecycleListener(onResume: _refresh);
    _timer = Timer.periodic(const Duration(seconds: 15), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _refresh({bool redirect = false}) async {
    if (defaultTargetPlatform != TargetPlatform.android) {
      if (mounted) setState(() => _loading = false);
      return;
    }
    try {
      final state =
          await _channel.invokeMapMethod<String, dynamic>('lockReadiness') ??
          {};
      if (!mounted) return;
      setState(() {
        _missing = List<String>.from(state['missing'] as List? ?? []);
        _available = state['settingsAvailable'] == true;
        _loading = false;
        _error = null;
      });
      if (redirect && _available && _missing.isNotEmpty) {
        await _channel.invokeMethod('redirectMissingPermission');
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error =
              'Could not check permissions. Your focus timer is still running.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return const SizedBox.shrink();
    }
    if (_loading) {
      return const ListTile(
        leading: Icon(Icons.fact_check_outlined),
        title: Text('Checking focus permissions…'),
      );
    }
    if (_error == null && _missing.isEmpty) return const SizedBox.shrink();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Companion(mood: CompanionMood.help, size: 80),
            const Text('Some permissions need attention'),
            const SizedBox(height: 8),
            Text(
              _error ??
                  (_available
                      ? 'Focus continues while you complete setup.'
                      : 'Focus is already active. Android Settings stays restricted during this lock. Complete these steps in Settings after the session.'),
            ),
            for (final id in _missing)
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(_labels[id] ?? id),
                trailing: _available && id != 'accessibility'
                    ? TextButton(
                        child: const Text('Open setting'),
                        onPressed: () async {
                          final opened =
                              await _channel.invokeMethod<bool>(
                                'fixDuringLock',
                                {'id': id},
                              ) ??
                              false;
                          if (!opened && context.mounted) {
                            companionNotice(
                              context,
                              'This setting can be changed after your focus session.',
                            );
                          }
                        },
                      )
                    : const Icon(Icons.lock_outline, size: 18),
              ),
          ],
        ),
      ),
    );
  }
}
