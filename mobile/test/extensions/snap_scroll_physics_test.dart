import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:immich_mobile/extensions/scroll_extensions.dart';

void main() {
  testWidgets('stops at the resting position but still snaps displaced content', (tester) async {
    final controller = SnapScrollController();
    await tester.pumpWidget(
      MaterialApp(
        home: SingleChildScrollView(
          controller: controller,
          physics: const SnapScrollPhysics(),
          child: const SizedBox(height: 2000),
        ),
      ),
    );
    final position = controller.snapPosition..snapOffset = 200;
    const physics = SnapScrollPhysics();
    expect(physics.createBallisticSimulation(position, 0), isNull);
    expect(physics.createBallisticSimulation(position, 800), isNotNull);
    controller.jumpTo(15);
    final closing = physics.createBallisticSimulation(position, 0);
    expect(closing, isNotNull);
    expect(closing!.x(10), closeTo(0, 0.01));
    controller.jumpTo(200);
    expect(physics.createBallisticSimulation(position, 0), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
}
