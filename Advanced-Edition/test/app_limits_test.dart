import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:focusbro/ui/app_limits/app_limits_screen.dart';
import 'package:focusbro/ui/setup/lock_readiness_card.dart';

void main() {
  const limits = MethodChannel('focusbro/appLimits');
  const owner = MethodChannel('focusbro/owner');
  final calls = <MethodCall>[];
  late Map<String, Object> row;
  setUp(() {
    calls.clear();
    row = {
      'package': 'com.example.social',
      'label': 'Social app',
      'dailyMinutes': 0,
      'usedMs': 0,
      'lockedUntil': 0,
      'exhaustedUntil': 0,
      'blocked': false,
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(limits, (call) async {
          calls.add(call);
          if (call.method == 'setDaily') {
            row['dailyMinutes'] = (call.arguments as Map)['minutes'] as int;
          }
          return {
            'apps': [row],
            'deviceOwner': true,
            'usageGranted': true,
            'error': '',
          };
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(limits, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(owner, null);
  });
  Future<void> open(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: AppLimitsScreen()));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('Social app'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Social app'));
    await tester.pumpAndSettle();
  }

  testWidgets('daily limit validates input before submitting selected app', (
    tester,
  ) async {
    await open(tester);
    await tester.ensureVisible(find.text('Daily limit'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Daily limit'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), '0');
    await tester.tap(find.text('Save limit'));
    await tester.pumpAndSettle();
    expect(find.text('Enter 1–1440 minutes'), findsOneWidget);
    expect(calls.where((e) => e.method == 'setDaily'), isEmpty);
    await tester.enterText(find.byType(TextFormField), '45');
    await tester.tap(find.text('Save limit'));
    await tester.pumpAndSettle();
    final saved = calls.singleWhere((e) => e.method == 'setDaily');
    expect(saved.arguments, {'package': 'com.example.social', 'minutes': 45});
    expect(find.text('0 of 45 min used today'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets(
    'exhausted daily limit and active timed lock cannot be changed early',
    (tester) async {
      row['dailyMinutes'] = 30;
      row['usedMs'] = 1800000;
      row['lockedUntil'] = DateTime.now()
          .add(const Duration(hours: 1))
          .millisecondsSinceEpoch;
      row['exhaustedUntil'] = DateTime.now()
          .add(const Duration(hours: 2))
          .millisecondsSinceEpoch;
      await open(tester);
      expect(
        tester
            .widget<OutlinedButton>(
              find.widgetWithText(OutlinedButton, 'Edit daily limit'),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, 'Lock for a while'),
            )
            .onPressed,
        isNull,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets('timed lock submits duration only after confirmation', (
    tester,
  ) async {
    await open(tester);
    await tester.ensureVisible(find.text('Lock for a while'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Lock for a while'));
    await tester.pumpAndSettle();
    expect(
      find.text('The lock starts immediately and cannot be ended early.'),
      findsOneWidget,
    );
    expect(calls.where((e) => e.method == 'lockFor'), isEmpty);
    await tester.tap(find.text('Lock app'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((e) => e.method == 'lockFor').arguments, {
      'package': 'com.example.social',
      'minutes': 30,
    });
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets(
    'lock audit defers settings without releasing or redirecting the lock',
    (tester) async {
      final ownerCalls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(owner, (call) async {
            ownerCalls.add(call.method);
            return {
              'missing': ['usageStats'],
              'settingsAvailable': false,
            };
          });
      await tester.pumpWidget(
        const MaterialApp(home: Scaffold(body: LockReadinessCard())),
      );
      await tester.pumpAndSettle();
      expect(find.text('Usage access'), findsOneWidget);
      expect(find.text('Open setting'), findsNothing);
      expect(ownerCalls, ['lockReadiness']);
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets('app limits fit a small screen with large text', (tester) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(1.6)),
          child: child!,
        ),
        home: const AppLimitsScreen(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('Social app'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    // Scroll has to finish before tapping after a large-text relayout.
    await tester.pumpAndSettle();
    await tester.tap(find.text('Social app'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('Lock for a while'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });
}
