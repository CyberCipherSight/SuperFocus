import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:focusbro/ui/setup/play_setup.dart';

void main() {
  const channel = MethodChannel('focusbro/owner');
  final calls = <MethodCall>[];
  late Map<String, dynamic> state;
  setUp(() {
    calls.clear();
    state = {
      'configured': false,
      'selected': 'basic',
      'effective': 'basic',
      'interval': 5,
      'focusCount': 0,
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'setPlayMode') {
            state['selected'] = (call.arguments as Map)['mode'];
            state['configured'] = true;
          }
          return state;
        });
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  testWidgets('first launch allows basic mode with no permission grants', (
    tester,
  ) async {
    await tester.pumpWidget(MaterialApp(home: PlaySetupScreen(onDone: () {})));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Skip and use timer only'), 250);
    await tester.tap(find.text('Skip and use timer only'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'fix'), isEmpty);
    expect(calls.singleWhere((c) => c.method == 'setPlayMode').arguments, {
      'mode': 'basic',
    });
  });
  testWidgets('reminder mode requests usage access only on explicit tap', (
    tester,
  ) async {
    await tester.pumpWidget(MaterialApp(home: PlaySetupScreen(onDone: () {})));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Reminders'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Usage access'), 150);
    await tester.ensureVisible(find.text('Usage access'));
    await tester.pumpAndSettle();
    final tile = find.ancestor(
      of: find.text('Usage access'),
      matching: find.byType(ListTile),
    );
    await tester.tap(find.descendant(of: tile, matching: find.text('Enable')));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'fix').arguments, {
      'id': 'usageStats',
    });
    expect(calls.where((c) => c.method == 'setPlayMode'), isEmpty);
  });
  testWidgets('missing strict capability is clearly displayed as fallback', (
    tester,
  ) async {
    state.addAll({
      'configured': true,
      'selected': 'strict',
      'effective': 'reminder',
      'focusCount': 1,
    });
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: PlayModeCard())),
    );
    await tester.pumpAndSettle();
    expect(find.text('Active: Reminders'), findsOneWidget);
    expect(find.textContaining('Missing permission'), findsOneWidget);
  });
}
