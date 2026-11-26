import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:focusbro/session.dart';
import 'package:focusbro/platform/build_variant.dart';
import 'package:focusbro/ui/design/appearance.dart';
import 'package:focusbro/ui/navigation_shell.dart';
import 'package:focusbro/ui/session_screen.dart';
import 'package:focusbro/ui/settings/appearance_screen.dart';

void main() {
  const limits = MethodChannel('focusbro/appLimits');
  const owner = MethodChannel('focusbro/owner');
  const preferences = MethodChannel('focusbro/appearance');
  late SessionController controller;
  late AppearanceController appearance;
  final boundary = GlobalKey();
  final prefCalls = <MethodCall>[];
  setUpAll(() async {
    if (!const bool.fromEnvironment('UI_PREVIEWS')) return;
    final font = FontLoader('Dojo')
      ..addFont(rootBundle.load('assets/fonts/Roboto-Regular.ttf'))
      ..addFont(rootBundle.load('assets/fonts/Roboto-Bold.ttf'));
    await font.load();
    await (FontLoader(
      'MaterialIcons',
    )..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'))).load();
  });
  setUp(() async {
    controller = SessionController(MockLocker());
    await controller.init();
    appearance = AppearanceController();
    prefCalls.clear();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      limits,
      (call) async => {
        'apps': [
          {
            'package': 'com.instagram.android',
            'label': 'Instagram',
            'dailyMinutes': 45,
            'usedMs': 720000,
            'lockedUntil': 0,
            'exhaustedUntil': 0,
            'blocked': kPlayEdition,
            'nightBlocked': !kPlayEdition,
            'breakUseMinutes': 15,
            'breakMinutes': 15,
          },
          {
            'package': 'com.google.android.youtube',
            'label': 'YouTube',
            'dailyMinutes': 0,
            'usedMs': 0,
            'lockedUntil': 0,
            'exhaustedUntil': 0,
            'blocked': false,
            'breakUseMinutes': 30,
            'breakMinutes': 2,
          },
        ],
        'deviceOwner': true,
        'usageGranted': true,
        'exactAlarms': true,
        'error': '',
      },
    );
    messenger.setMockMethodCallHandler(
      owner,
      (call) async => {'missing': <String>[], 'settingsAvailable': false},
    );
    messenger.setMockMethodCallHandler(preferences, (call) async {
      prefCalls.add(call);
      return call.method == 'load'
          ? {'mode': 'light', 'reduceMotion': true}
          : null;
    });
  });
  tearDown(() {
    controller.dispose();
    appearance.dispose();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    for (final channel in [limits, owner, preferences]) {
      messenger.setMockMethodCallHandler(channel, null);
    }
  });
  Future<void> open(
    WidgetTester tester, {
    double scale = 1,
    Size size = const Size(390, 844),
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      AppearanceScope(
        controller: appearance,
        child: MaterialApp(
          theme: dojoTheme(Brightness.dark),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
          home: RepaintBoundary(
            key: boundary,
            child: NavigationShell(controller: controller),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> tab(WidgetTester tester, String label) async {
    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text(label),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  }

  Future<void> screenshot(WidgetTester tester, String name) async {
    if (!const bool.fromEnvironment('UI_PREVIEWS')) return;
    await tester.runAsync(() async {
      await precacheImage(
        const AssetImage('assets/illustrations/companion-atlas.png'),
        tester.element(find.byType(NavigationShell)),
      );
    });
    await tester.pumpAndSettle();
    await tester.runAsync(() async {
      final render =
          boundary.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final picture = await render.toImage(pixelRatio: 2);
      final bytes = await picture.toByteData(format: ui.ImageByteFormat.png);
      final folder = Directory('build/ui-preview');
      await folder.create(recursive: true);
      await File(
        '${folder.path}/$name.png',
      ).writeAsBytes(bytes!.buffer.asUint8List());
      picture.dispose();
    });
  }

  testWidgets(
    'navigation preserves app filters and separates night schedules',
    (tester) async {
      await open(tester);
      await screenshot(tester, '01-focus');
      await tab(tester, 'Apps');
      await screenshot(tester, '02-apps');
      await tester.tap(find.text('Blocked now'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Instagram'),
        150,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('Instagram'), findsOneWidget);
      expect(find.text('YouTube'), findsNothing);
      await tab(tester, 'Schedule');
      await screenshot(tester, '03-schedule');
      if (kPlayEdition) {
        expect(find.text('Night blocking'), findsNothing);
        expect(find.text('Add focus time'), findsOneWidget);
      } else {
        await tester.tap(find.text('Night blocking'));
        await tester.pumpAndSettle();
        expect(find.text('Let the day settle.'), findsOneWidget);
        expect(find.text('Add focus time'), findsNothing);
      }
      await screenshot(tester, '04-night');
      await tab(tester, 'Progress');
      await screenshot(tester, '05-progress');
      await tab(tester, 'Settings');
      await screenshot(tester, '06-settings');
      await tab(tester, 'Apps');
      expect(
        tester
            .widget<ChoiceChip>(find.widgetWithText(ChoiceChip, 'Blocked now'))
            .selected,
        isTrue,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('all tabs fit small phones with enlarged text', (tester) async {
    await open(tester, scale: 1.6, size: const Size(320, 640));
    expect(find.text('Begin 16 min of focus'), findsOneWidget);
    for (final label in ['Apps', 'Schedule', 'Progress', 'Settings', 'Focus']) {
      await tab(tester, label);
    }
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('appearance choices save independently of blocking', (
    tester,
  ) async {
    await appearance.load();
    expect(appearance.mode, ThemeMode.light);
    expect(appearance.reduceMotion, isTrue);
    await appearance.update(themeMode: ThemeMode.dark, reducedMotion: false);
    expect(prefCalls.last.method, 'save');
    expect(prefCalls.last.arguments, {'mode': 'dark', 'reduceMotion': false});
    expect(controller.phase, SessionPhase.idle);
  });

  testWidgets(
    'experimental import states its availability and cannot accept a file',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: dojoTheme(Brightness.dark),
          home: const ThemeImportScreen(),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.textContaining('File import is not available'),
        findsOneWidget,
      );
      await tester.scrollUntilVisible(
        find.text('Import pack · Coming later'),
        200,
      );
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, 'Import pack · Coming later'),
            )
            .onPressed,
        isNull,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'arming can be cancelled and locked UI retains calling controls',
    (tester) async {
      await controller.init();
      controller.start(16);
      await tester.pumpWidget(
        MaterialApp(
          theme: dojoTheme(Brightness.dark),
          home: AnimatedBuilder(
            animation: controller,
            builder: (context, _) => SessionScreen(controller: controller),
          ),
        ),
      );
      await tester.pump();
      expect(find.text('Cancel'), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      expect(controller.phase, SessionPhase.idle);
      final base = DateTime.now();
      controller.now = () => base;
      controller.start(16);
      await tester.pump();
      controller.now = () => base.add(const Duration(seconds: 4));
      await tester.pump(const Duration(seconds: 4));
      await tester.pump();
      await tester.scrollUntilVisible(find.text('Phone'), 100);
      expect(find.text('Phone'), findsOneWidget);
      expect(find.text('Emergency'), findsOneWidget);
      expect(find.text('Cancel'), findsNothing);
      expect(tester.takeException(), isNull);
      controller.now = () => base.add(const Duration(minutes: 20));
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(controller.phase, SessionPhase.idle);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
