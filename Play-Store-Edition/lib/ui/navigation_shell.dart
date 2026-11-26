import 'package:flutter/material.dart';
import '../session.dart';
import 'home.dart';
import 'app_limits/app_limits_screen.dart';
import 'routines/routines_screen.dart';
import 'stats/stats_screen.dart';
import 'settings/settings_screen.dart';

class NavigationShell extends StatefulWidget {
  const NavigationShell({super.key, required this.controller});
  final SessionController controller;
  @override
  State<NavigationShell> createState() => _NavigationShellState();
}

class _NavigationShellState extends State<NavigationShell> {
  int _selected = 0;
  final Set<int> _visited = {0};
  void _select(int index) => setState(() {
    _selected = index;
    _visited.add(index);
  });
  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _selected == 0,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) _select(0);
    },
    child: Scaffold(
      body: IndexedStack(
        index: _selected,
        children: [
          HomeScreen(controller: widget.controller, onNavigate: _select),
          for (var i = 1; i < 5; i++)
            _visited.contains(i)
                ? TickerMode(
                    enabled: _selected == i,
                    child: switch (i) {
                      1 => AppLimitsScreen(
                        allowedApps: [
                          ...widget.controller.dock,
                          ...widget.controller.allowedApps,
                        ],
                      ),
                      2 => const RoutinesScreen(),
                      3 => const StatsScreen(),
                      _ => const SettingsScreen(),
                    },
                  )
                : const SizedBox.shrink(),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selected,
        onDestinationSelected: _select,
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.adjust_outlined),
            selectedIcon: Icon(Icons.adjust),
            label: 'Focus',
          ),
          NavigationDestination(
            icon: Icon(Icons.apps_outlined),
            selectedIcon: Icon(Icons.apps),
            label: 'Apps',
          ),
          NavigationDestination(
            icon: Icon(Icons.calendar_month_outlined),
            selectedIcon: Icon(Icons.calendar_month),
            label: 'Schedule',
          ),
          NavigationDestination(
            icon: Icon(Icons.bar_chart_outlined),
            selectedIcon: Icon(Icons.bar_chart),
            label: 'Progress',
          ),
          NavigationDestination(
            icon: Icon(Icons.tune_outlined),
            selectedIcon: Icon(Icons.tune),
            label: 'Settings',
          ),
        ],
      ),
    ),
  );
}
