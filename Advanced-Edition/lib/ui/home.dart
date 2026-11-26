import 'package:flutter/material.dart';
import '../session.dart';
import '../platform/build_variant.dart';
import '../platform/stats.dart';
import '../session/routine.dart';
import 'app_limits/app_limits_screen.dart';
import 'design/companion.dart';
import 'routines/routines_screen.dart';
import 'setup/setup_screen.dart';
import 'stats/stats_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.controller, this.onNavigate});
  final SessionController controller;
  final ValueChanged<int>? onNavigate;
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _selected = kPresetMinutes[2];
  int _today = 0;
  DateTime? _next;
  late final AppLifecycleListener _lifecycle;
  @override
  void initState() {
    super.initState();
    _summary();
    _lifecycle = AppLifecycleListener(onResume: _summary);
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _summary() async {
    try {
      final days = await Stats().dailyMinutes(2);
      final next = await RoutineRepo().nextFire();
      final now = DateTime.now();
      final today = days
          .where(
            (d) =>
                d.date.year == now.year &&
                d.date.month == now.month &&
                d.date.day == now.day,
          )
          .fold(0, (sum, d) => sum + d.minutes);
      if (mounted) {
        setState(() {
          _today = today;
          _next = next;
        });
      }
    } catch (_) {
      /* The timer stays usable if a summary cannot load. */
    }
  }

  Future<void> _pickCustom() async {
    final chosen = await showDialog<int>(
      context: context,
      builder: (_) => _CustomDurationDialog(initial: _selected),
    );
    if (chosen != null && mounted) {
      setState(() => _selected = clampDuration(chosen));
    }
  }

  Future<void> _open(int tab, Widget screen) async {
    if (widget.onNavigate != null) {
      widget.onNavigate!(tab);
      return;
    }
    await Navigator.of(
      context,
    ).push(MaterialPageRoute<void>(builder: (_) => screen));
    _summary();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final controller = widget.controller;
    final strong = controller.level == EnforcementLevel.strong;
    final status = switch (controller.level) {
      EnforcementLevel.strong => 'Focus protection is ready',
      EnforcementLevel.standard => 'App blocking is ready',
      EnforcementLevel.degraded => 'Screen pinning is ready',
      _ => 'Timer mode · Set up app blocking',
    };
    final availability = controller.level == EnforcementLevel.degraded
        ? 'Emergency calls are available from the power menu.'
        : strong || controller.level == EnforcementLevel.standard
        ? 'Calls and your allowed apps stay within reach.'
        : 'Your time is tracked. Set up protection to block other apps.';
    return Scaffold(
      bottomNavigationBar: SafeArea(
        top: false,
        minimum: const EdgeInsets.fromLTRB(20, 10, 20, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (controller.cooldownRemaining > Duration.zero)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  'Take a breath. Ready again in ${formatCountdown(controller.cooldownRemaining)}.',
                ),
              ),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: controller.canStart
                    ? () => controller.start(_selected)
                    : null,
                icon: const Icon(Icons.play_arrow_rounded),
                label: Text('Begin ${formatDuration(_selected)} of focus'),
              ),
            ),
          ],
        ),
      ),
      appBar: AppBar(
        title: const Text(kAppTitle),
        actions: [
          IconButton(
            tooltip: 'Settings and diagnostics',
            onPressed: () => _open(4, const SetupScreen()),
            icon: const Icon(Icons.tune_rounded),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _summary,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
          children: [
            const CompanionBanner(
              title: 'Find your focus.',
              message:
                  'One intention. A little room to grow.\nYour next chapter starts here.',
            ),
            const SectionLabel(
              'Your next session',
              subtitle: 'Choose how much time you want to protect.',
            ),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(22),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '$_selected',
                      style: theme.textTheme.displayLarge?.copyWith(
                        fontSize: 76,
                        height: 1.1,
                        fontWeight: FontWeight.w700,
                        color: colors.primary,
                      ),
                    ),
                    Text(
                      'minutes of focus',
                      style: theme.textTheme.titleMedium,
                    ),
                    const SizedBox(height: 20),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final minutes in kPresetMinutes)
                          ChoiceChip(
                            label: Text('$minutes min'),
                            selected: _selected == minutes,
                            onSelected: (_) =>
                                setState(() => _selected = minutes),
                          ),
                        ActionChip(
                          avatar: const Icon(Icons.edit_outlined, size: 16),
                          label: const Text('Custom'),
                          onPressed: _pickCustom,
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: ListTile(
                contentPadding: const EdgeInsets.all(14),
                leading: Icon(
                  strong ? Icons.verified_user_outlined : Icons.info_outline,
                  color: colors.secondary,
                ),
                title: Text(status),
                subtitle: Text(availability),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _open(4, const SetupScreen()),
              ),
            ),
            const SectionLabel('Today, at a glance'),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Wrap(
                  spacing: 28,
                  runSpacing: 18,
                  children: [
                    _Fact(
                      icon: Icons.auto_awesome_outlined,
                      value: '$_today min',
                      label: 'Focus time today',
                    ),
                    _Fact(
                      icon: Icons.event_outlined,
                      value: _next == null
                          ? 'No session planned'
                          : MaterialLocalizations.of(
                              context,
                            ).formatShortDate(_next!),
                      label: _next == null
                          ? 'Build your rhythm in Schedule'
                          : 'Next session · ${TimeOfDay.fromDateTime(_next!).format(context)}',
                    ),
                  ],
                ),
              ),
            ),
            const SectionLabel('Make it your day'),
            _Shortcut(
              icon: Icons.apps_rounded,
              title: 'App limits',
              subtitle: 'Daily time, repeating breaks and individual locks.',
              onTap: () => _open(1, const AppLimitsScreen()),
            ),
            const SizedBox(height: 10),
            _Shortcut(
              icon: Icons.calendar_month_outlined,
              title: 'Your timetable',
              subtitle: 'Plan your focus and see night blocking.',
              onTap: () => _open(2, const RoutinesScreen()),
            ),
            const SizedBox(height: 10),
            _Shortcut(
              icon: Icons.insights_outlined,
              title: 'Your progress',
              subtitle: 'See the time you made for yourself.',
              onTap: () => _open(3, const StatsScreen()),
            ),
          ],
        ),
      ),
    );
  }
}

class _Fact extends StatelessWidget {
  const _Fact({required this.icon, required this.value, required this.label});
  final IconData icon;
  final String value;
  final String label;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Icon(icon, color: Theme.of(context).colorScheme.secondary),
      const SizedBox(height: 8),
      Text(value, style: Theme.of(context).textTheme.titleMedium),
      Text(label, style: Theme.of(context).textTheme.bodySmall),
    ],
  );
}

class _Shortcut extends StatelessWidget {
  const _Shortcut({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Card(
    child: ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
      leading: Icon(icon, color: Theme.of(context).colorScheme.primary),
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: const Icon(Icons.chevron_right),
      onTap: onTap,
    ),
  );
}

class _CustomDurationDialog extends StatefulWidget {
  const _CustomDurationDialog({required this.initial});
  final int initial;
  @override
  State<_CustomDurationDialog> createState() => _CustomDurationDialogState();
}

class _CustomDurationDialogState extends State<_CustomDurationDialog> {
  late double _minutes = clampDuration(widget.initial).toDouble();
  @override
  Widget build(BuildContext context) => CompanionDialog(
    title: const Text('How long?'),
    content: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          formatDuration(_minutes.round()),
          style: Theme.of(context).textTheme.displaySmall,
        ),
        Slider(
          value: _minutes,
          min: kMinSessionMinutes.toDouble(),
          max: kMaxSessionMinutes.toDouble(),
          divisions: kMaxSessionMinutes - kMinSessionMinutes,
          label: formatDuration(_minutes.round()),
          onChanged: (v) => setState(() => _minutes = v),
        ),
        const Text(
          'Sessions can last up to three hours. Your phone unlocks automatically when the time ends.',
        ),
      ],
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancel'),
      ),
      FilledButton(
        onPressed: () => Navigator.pop(context, _minutes.round()),
        child: const Text('Use this'),
      ),
    ],
  );
}
