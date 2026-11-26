import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import '../../platform/diagnostics.dart';
import '../../platform/stats.dart';
import '../design/companion.dart';

/// Local-only analytics (ARCHITECTURE.md §8).
///
/// Ethical engagement (§12): counts, never judgments. A bad week renders in the
/// same palette as a good one, there is no streak to break, and nothing here
/// nags the user to come back.
class StatsScreen extends StatefulWidget {
  const StatsScreen({super.key});

  @override
  State<StatsScreen> createState() => _StatsScreenState();
}

class _StatsScreenState extends State<StatsScreen> {
  final _stats = Stats();
  final _diagnostics = Diagnostics();

  int _range = 14;
  bool _usageGranted = false;
  List<AppUsage> _byApp = const [];
  List<DayFocus> _days = const [];
  List<PastSession> _sessions = const [];
  ({int blocked, int tampered}) _attempts = (blocked: 0, tampered: 0);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final granted = await _stats.usageGranted();
    final byApp = granted ? await _stats.todayByApp() : <AppUsage>[];
    final days = await _stats.dailyMinutes(_range);
    final sessions = await _stats.recentSessions();
    final attempts = await _stats.blockAttempts();
    if (!mounted) return;
    setState(() {
      _usageGranted = granted;
      _byApp = byApp;
      _days = days;
      _sessions = sessions;
      _attempts = attempts;
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Progress'),
        actions: [
          PopupMenuButton<String>(
            onSelected: (v) async {
              if (v == 'clear') {
                await _stats.clearHistory();
                await _load();
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'clear', child: Text('Clear history')),
            ],
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const CompanionBanner(
            title: 'Look how far you’ve come.',
            message: 'A little time for what matters, one session at a time.',
            mood: CompanionMood.celebrate,
          ),
          const SectionLabel('Your focus time'),
          _TrendCard(
            days: _days,
            range: _range,
            onRangeChanged: (r) {
              setState(() => _range = r);
              _load();
            },
          ),
          const SizedBox(height: 16),
          if (_usageGranted)
            _TodayCard(usage: _byApp)
          else
            _UsageLockedCard(
              onGrant: () async {
                await _diagnostics.fix('usageStats');
                await _load();
              },
            ),
          const SizedBox(height: 16),
          _SessionsCard(sessions: _sessions, attempts: _attempts),
          const SizedBox(height: 16),
          Text(
            'Everything on this screen is stored on this device only, kept for '
            '180 days, and never sent anywhere.',
            style: theme.textTheme.labelSmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}

/// The comparison chart: this period in front, the one before it drawn behind
/// each bar, so "better or worse than before" is visible without arithmetic.
class _TrendCard extends StatelessWidget {
  const _TrendCard({
    required this.days,
    required this.range,
    required this.onRangeChanged,
  });

  final List<DayFocus> days;
  final int range;
  final ValueChanged<int> onRangeChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final (current, previous) = splitPeriods(days);
    final delta = periodDelta(days);
    final total = current.fold(0, (a, d) => a + d.minutes);
    final maxY = [
      ...current.map((d) => d.minutes),
      ...previous.map((d) => d.minutes),
      10,
    ].reduce((a, b) => a > b ? a : b).toDouble();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text('Focus time', style: theme.textTheme.titleSmall),
                ),
                SegmentedButton<int>(
                  showSelectedIcon: false,
                  segments: const [
                    ButtonSegment(value: 14, label: Text('7d')),
                    ButtonSegment(value: 60, label: Text('30d')),
                  ],
                  selected: {range},
                  onSelectionChanged: (s) => onRangeChanged(s.first),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              '$total min this period · '
              '${delta == 0 ? "same as before" : "${delta > 0 ? "+" : ""}$delta min vs before"}',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 20),
            SizedBox(
              height: 160,
              child: current.isEmpty
                  ? Center(
                      child: Text(
                        'No sessions yet.',
                        style: theme.textTheme.bodySmall,
                      ),
                    )
                  : BarChart(
                      BarChartData(
                        maxY: maxY * 1.15,
                        gridData: const FlGridData(show: false),
                        borderData: FlBorderData(show: false),
                        titlesData: const FlTitlesData(show: false),
                        barTouchData: BarTouchData(
                          touchTooltipData: BarTouchTooltipData(
                            getTooltipItem: (group, _, rod, _) =>
                                BarTooltipItem(
                                  '${rod.toY.round()} min',
                                  theme.textTheme.labelSmall ??
                                      const TextStyle(),
                                ),
                          ),
                        ),
                        barGroups: [
                          for (var i = 0; i < current.length; i++)
                            BarChartGroupData(
                              x: i,
                              barRods: [
                                BarChartRodData(
                                  toY: current[i].minutes.toDouble(),
                                  width: 10,
                                  borderRadius: BorderRadius.circular(3),
                                  color: theme.colorScheme.primary,
                                  // The previous period, drawn behind.
                                  backDrawRodData: BackgroundBarChartRodData(
                                    show: i < previous.length,
                                    toY: i < previous.length
                                        ? previous[i].minutes.toDouble()
                                        : 0,
                                    color: theme.colorScheme.primary.withValues(
                                      alpha: 0.22,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                        ],
                      ),
                    ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                _Swatch(
                  colour: theme.colorScheme.primary,
                  label: 'This period',
                ),
                const SizedBox(width: 16),
                _Swatch(
                  colour: theme.colorScheme.primary.withValues(alpha: 0.22),
                  label: 'Previous',
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _Swatch extends StatelessWidget {
  const _Swatch({required this.colour, required this.label});
  final Color colour;
  final String label;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 10,
        height: 10,
        decoration: BoxDecoration(
          color: colour,
          borderRadius: BorderRadius.circular(2),
        ),
      ),
      const SizedBox(width: 6),
      Text(label, style: Theme.of(context).textTheme.labelSmall),
    ],
  );
}

class _TodayCard extends StatelessWidget {
  const _TodayCard({required this.usage});
  final List<AppUsage> usage;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final total = usage.fold(Duration.zero, (a, u) => a + u.duration);
    final palette = _palette(theme.colorScheme);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Where today went', style: theme.textTheme.titleSmall),
            const SizedBox(height: 16),
            if (usage.isEmpty)
              Text(
                'Nothing recorded yet today.',
                style: theme.textTheme.bodySmall,
              )
            else ...[
              SizedBox(
                height: 150,
                child: PieChart(
                  PieChartData(
                    sectionsSpace: 2,
                    centerSpaceRadius: 42,
                    sections: [
                      for (var i = 0; i < usage.length; i++)
                        PieChartSectionData(
                          value: usage[i].duration.inMinutes.toDouble(),
                          color: palette[i % palette.length],
                          radius: 22,
                          showTitle: false,
                        ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              for (var i = 0; i < usage.length; i++)
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Row(
                    children: [
                      _Swatch(
                        colour: palette[i % palette.length],
                        label: usage[i].label,
                      ),
                      const Spacer(),
                      Text(
                        _short(usage[i].duration),
                        style: theme.textTheme.labelSmall,
                      ),
                    ],
                  ),
                ),
              const Divider(height: 24),
              Text(
                '${_short(total)} on screen today',
                style: theme.textTheme.bodySmall,
              ),
            ],
          ],
        ),
      ),
    );
  }

  static List<Color> _palette(ColorScheme s) => [
    s.primary,
    s.tertiary,
    s.secondary,
    s.primaryContainer,
    s.tertiaryContainer,
    s.secondaryContainer,
    s.surfaceContainerHighest,
  ];

  static String _short(Duration d) =>
      d.inHours > 0 ? '${d.inHours}h ${d.inMinutes % 60}m' : '${d.inMinutes}m';
}

class _UsageLockedCard extends StatelessWidget {
  const _UsageLockedCard({required this.onGrant});
  final VoidCallback onGrant;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Where today went', style: theme.textTheme.titleSmall),
            const SizedBox(height: 8),
            Text(
              'Needs usage access to show a per-app breakdown. Durations only — '
              'FocusBro cannot see what is on your screen.',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(onPressed: onGrant, child: const Text('Grant')),
            ),
          ],
        ),
      ),
    );
  }
}

class _SessionsCard extends StatelessWidget {
  const _SessionsCard({required this.sessions, required this.attempts});
  final List<PastSession> sessions;
  final ({int blocked, int tampered}) attempts;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Recent sessions', style: theme.textTheme.titleSmall),
            if (attempts.blocked > 0) ...[
              const SizedBox(height: 4),
              Text(
                '${attempts.blocked} app opens turned away'
                '${attempts.tampered > 0 ? ' · ${attempts.tampered} times blocking was switched off' : ''}',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
            const SizedBox(height: 12),
            if (sessions.isEmpty)
              Text('None yet.', style: theme.textTheme.bodySmall)
            else
              for (final s in sessions.take(10))
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Row(
                    children: [
                      Text(
                        _when(s.startedAt),
                        style: theme.textTheme.bodySmall,
                      ),
                      const Spacer(),
                      Text(
                        '${s.duration.inMinutes} min',
                        style: theme.textTheme.labelSmall,
                      ),
                    ],
                  ),
                ),
          ],
        ),
      ),
    );
  }

  static String _when(DateTime at) {
    final d =
        '${at.day.toString().padLeft(2, '0')}/${at.month.toString().padLeft(2, '0')}';
    final t =
        '${at.hour.toString().padLeft(2, '0')}:${at.minute.toString().padLeft(2, '0')}';
    return '$d  $t';
  }
}
