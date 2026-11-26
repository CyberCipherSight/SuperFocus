import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Appearance preferences are deliberately separate from enforcement settings.
class AppearanceController extends ChangeNotifier {
  static const channel = MethodChannel('focusbro/appearance');
  ThemeMode mode = ThemeMode.dark;
  bool reduceMotion = false;

  Future<void> load() async {
    try {
      final saved = await channel.invokeMapMethod<String, dynamic>('load');
      mode = ThemeMode.values.firstWhere(
        (m) => m.name == saved?['mode'],
        orElse: () => ThemeMode.dark,
      );
      reduceMotion = saved?['reduceMotion'] == true;
      notifyListeners();
    } on MissingPluginException {
      // Desktop previews use the same defaults without a native store.
    } on PlatformException {
      // An unavailable preference store must never prevent the lock UI opening.
    }
  }

  Future<void> update({ThemeMode? themeMode, bool? reducedMotion}) async {
    mode = themeMode ?? mode;
    reduceMotion = reducedMotion ?? reduceMotion;
    notifyListeners();
    try {
      await channel.invokeMethod('save', {
        'mode': mode.name,
        'reduceMotion': reduceMotion,
      });
    } on MissingPluginException {
      // In-memory appearance is enough for desktop previews.
    } on PlatformException {
      // Keep the current appearance usable even if persistence fails.
    }
  }
}

class AppearanceScope extends InheritedNotifier<AppearanceController> {
  const AppearanceScope({
    super.key,
    required AppearanceController controller,
    required super.child,
  }) : super(notifier: controller);

  static AppearanceController? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AppearanceScope>()?.notifier;
}

ThemeData dojoTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final scheme =
      ColorScheme.fromSeed(
        seedColor: const Color(0xFF9276F5),
        brightness: brightness,
      ).copyWith(
        primary: dark ? const Color(0xFFBEABFF) : const Color(0xFF6742C5),
        secondary: dark ? const Color(0xFF9CE4CA) : const Color(0xFF216B57),
        surface: dark ? const Color(0xFF101322) : const Color(0xFFFAF8FF),
        surfaceContainerLow: dark
            ? const Color(0xFF191D31)
            : const Color(0xFFF0ECF8),
        surfaceContainerHighest: dark
            ? const Color(0xFF292D45)
            : const Color(0xFFE6E0F1),
      );
  final base = ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    fontFamily: 'Dojo',
  );
  return base.copyWith(
    scaffoldBackgroundColor: scheme.surface,
    textTheme: base.textTheme,
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      centerTitle: false,
      titleTextStyle: base.textTheme.titleLarge?.copyWith(
        color: scheme.onSurface,
        fontWeight: FontWeight.w700,
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      margin: EdgeInsets.zero,
      color: scheme.surfaceContainerLow,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: scheme.surfaceContainerLow,
      indicatorColor: scheme.primary.withValues(alpha: .18),
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surfaceContainerLow,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: BorderSide.none,
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 52),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
        textStyle: const TextStyle(
          fontFamily: 'Dojo',
          fontSize: 15,
          fontWeight: FontWeight.w700,
        ),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: scheme.surfaceContainerLow,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
    ),
    expansionTileTheme: const ExpansionTileThemeData(
      shape: Border(),
      collapsedShape: Border(),
    ),
  );
}
