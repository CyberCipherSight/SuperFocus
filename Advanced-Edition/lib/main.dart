import 'package:flutter/material.dart';
import 'platform/android_locker.dart';
import 'platform/build_variant.dart';
import 'session.dart';
import 'ui/design/appearance.dart';
import 'ui/design/companion.dart';
import 'ui/navigation_shell.dart';
import 'ui/session_screen.dart';

void main() => runApp(const FocusBroApp());

class FocusBroApp extends StatefulWidget {
  const FocusBroApp({super.key});
  @override
  State<FocusBroApp> createState() => _FocusBroAppState();
}

class _FocusBroAppState extends State<FocusBroApp> {
  final _controller = SessionController(defaultLocker());
  final _appearance = AppearanceController();
  final _navigator = GlobalKey<NavigatorState>();
  late final AppLifecycleListener _lifecycle;
  bool _wasLocked = false;
  bool _completed = false;
  @override
  void initState() {
    super.initState();
    _appearance.load();
    _controller.addListener(_sessionChanged);
    _controller.init();
    _lifecycle = AppLifecycleListener(onResume: _controller.resync);
  }

  void _sessionChanged() {
    // Scheduled sessions can begin while a settings route or dialog is open.
    if (_controller.phase != SessionPhase.idle) {
      _navigator.currentState?.popUntil((route) => route.isFirst);
    }
    if (_wasLocked && _controller.phase == SessionPhase.idle) _completed = true;
    _wasLocked =
        _controller.phase == SessionPhase.locked ||
        _controller.phase == SessionPhase.releasing;
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    _controller.removeListener(_sessionChanged);
    _controller.dispose();
    _appearance.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppearanceScope(
    controller: _appearance,
    child: AnimatedBuilder(
      animation: _appearance,
      builder: (context, _) => MaterialApp(
        navigatorKey: _navigator,
        title: kAppTitle,
        debugShowCheckedModeBanner: false,
        theme: dojoTheme(Brightness.light),
        darkTheme: dojoTheme(Brightness.dark),
        themeMode: _appearance.mode,
        home: AnimatedBuilder(
          animation: _controller,
          builder: (context, _) {
            final active = _controller.phase != SessionPhase.idle;
            return Stack(
              children: [
                Offstage(
                  offstage: active,
                  child: TickerMode(
                    enabled: !active,
                    child: NavigationShell(controller: _controller),
                  ),
                ),
                if (active) SessionScreen(controller: _controller),
                if (!active && _completed)
                  Positioned.fill(
                    child: Material(
                      color: Theme.of(context).colorScheme.surface,
                      child: SafeArea(
                        child: Center(
                          child: SingleChildScrollView(
                            padding: const EdgeInsets.all(28),
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                const Companion(
                                  mood: CompanionMood.celebrate,
                                  size: 220,
                                ),
                                Text(
                                  'You made room.',
                                  style: Theme.of(
                                    context,
                                  ).textTheme.headlineMedium,
                                ),
                                const SizedBox(height: 12),
                                const Text(
                                  'Your focus session is complete. Take a breath before your next chapter.',
                                  textAlign: TextAlign.center,
                                ),
                                const SizedBox(height: 24),
                                FilledButton(
                                  onPressed: () =>
                                      setState(() => _completed = false),
                                  child: const Text('Back to my day'),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            );
          },
        ),
      ),
    ),
  );
}
