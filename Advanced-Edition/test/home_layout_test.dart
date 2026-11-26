import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:focusbro/session.dart';
import 'package:focusbro/ui/home.dart';

void main() {
  testWidgets(
    'focus controls remain usable on a small screen with large text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final controller = SessionController(MockLocker());
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        MaterialApp(
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: TextScaler.linear(1.6)),
            child: child!,
          ),
          home: HomeScreen(controller: controller),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Begin 16 min of focus'), findsOneWidget);
      await tester.scrollUntilVisible(find.text('Your timetable'), 250);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Your timetable'));
      await tester.pumpAndSettle();
      expect(find.text('Add focus time'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
