import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:immich_mobile/widgets/photo_view/photo_view.dart';

void main() {
  for (final scenario in [
    (
      name: 'portrait viewport',
      viewport: const Size(400, 800),
      child: const Size(400, 300),
      original: 10.0,
      scales: [8 / 3, 10.0, 1.0],
    ),
    (
      name: 'landscape viewport',
      viewport: const Size(800, 400),
      child: const Size(300, 400),
      original: 10.0,
      scales: [8 / 3, 10.0, 1.0],
    ),
    (
      name: 'matching aspect ratio',
      viewport: const Size(400, 300),
      child: const Size(400, 300),
      original: 10.0,
      scales: [10.0, 1.0],
    ),
    (
      name: 'small image',
      viewport: const Size(400, 300),
      child: const Size(400, 300),
      original: 0.25,
      scales: [0.25, 1.0],
    ),
  ]) {
    testWidgets('double tap cycles through original size: ${scenario.name}', (tester) async {
      tester.view.physicalSize = const Size(1000, 1000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = PhotoViewController();
      await tester.pumpWidget(
        MaterialApp(
          home: Center(
            child: SizedBox(
              width: scenario.viewport.width,
              height: scenario.viewport.height,
              child: PhotoView.customChild(
                controller: controller,
                childSize: scenario.child,
                originalScale: scenario.original,
                child: const ColoredBox(color: Colors.red),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(controller.scale, closeTo(1, 0.00001));
      for (final scale in scenario.scales) {
        await tester.tap(find.byType(PhotoView));
        await tester.pump(const Duration(milliseconds: 50));
        await tester.tap(find.byType(PhotoView));
        await tester.pumpAndSettle();
        expect(controller.scale, closeTo(scale, 0.00001));
      }
      // A manual zoom uses the existing return-to-fit double tap.
      controller.scale = 2;
      await tester.pump();
      await tester.tap(find.byType(PhotoView));
      await tester.pump(const Duration(milliseconds: 50));
      await tester.tap(find.byType(PhotoView));
      await tester.pumpAndSettle();
      expect(controller.scale, closeTo(1, 0.00001));
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    });
  }

  testWidgets('default original scale stays at one', (tester) async {
    tester.view.physicalSize = const Size(1000, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final controller = PhotoViewController();
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: SizedBox(
            width: 400,
            height: 800,
            child: PhotoView.customChild(
              controller: controller,
              childSize: const Size(4000, 3000),
              child: const ColoredBox(color: Colors.red),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    for (final scale in [8 / 30, 1.0, 0.1]) {
      await tester.tap(find.byType(PhotoView));
      await tester.pump(const Duration(milliseconds: 50));
      await tester.tap(find.byType(PhotoView));
      await tester.pumpAndSettle();
      expect(controller.scale, closeTo(scale, 0.00001));
    }
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
}
