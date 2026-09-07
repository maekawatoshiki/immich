import 'package:drift/drift.dart' hide isNotNull, isNull;
import 'package:drift/native.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/domain/models/store.model.dart';
import 'package:immich_mobile/domain/services/store.service.dart';
import 'package:immich_mobile/infrastructure/repositories/db.repository.dart';
import 'package:immich_mobile/infrastructure/repositories/settings.repository.dart';
import 'package:immich_mobile/infrastructure/repositories/store.repository.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/auto_hdr_image.widget.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/ultra_hdr_viewer_launcher.dart';
import 'package:immich_mobile/presentation/widgets/images/thumbnail.widget.dart';
import 'package:immich_mobile/widgets/photo_view/photo_view.dart';

import '../../../fixtures/asset.stub.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const capabilityChannel = MethodChannel('immich/ultra_hdr_viewer');
  const platformChannel = MethodChannel('flutter/platform_views_2');
  final asset = LocalAssetStub.image1.copyWith(id: '1', width: 4000, height: 3000);
  late Drift db;

  setUpAll(() async {
    db = Drift(DatabaseConnection(NativeDatabase.memory(), closeStreamsSynchronously: true));
    await SettingsRepository.ensureInitialized(db);
    await StoreService.init(storeRepository: DriftStoreRepository(db), listenUpdates: false);
    await StoreService.I.put(StoreKey.serverEndpoint, 'http://localhost:3000');
  });
  tearDownAll(() => db.close());

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    messenger.setMockMethodCallHandler(capabilityChannel, null);
    messenger.setMockMethodCallHandler(platformChannel, null);
  });

  test('requires the flag, Android, an HDR display, and HCPP', () async {
    var nativeCalls = 0;
    var hdrDisplay = true;
    var hcpp = true;
    messenger.setMockMethodCallHandler(capabilityChannel, (_) async {
      nativeCalls++;
      return hdrDisplay;
    });
    messenger.setMockMethodCallHandler(platformChannel, (_) async => hcpp);
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    expect(await checkAutoHdrSupport(enabled: false), isFalse);
    expect(nativeCalls, 0);
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    expect(await checkAutoHdrSupport(enabled: true), isFalse);
    expect(nativeCalls, 0);
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    hdrDisplay = false;
    expect(await checkAutoHdrSupport(enabled: true), isFalse);
    hdrDisplay = true;
    hcpp = false;
    expect(await checkAutoHdrSupport(enabled: true), isFalse);
    hcpp = true;
    expect(await checkAutoHdrSupport(enabled: true), isTrue);
    messenger.setMockMethodCallHandler(capabilityChannel, null);
    expect(await checkAutoHdrSupport(enabled: true), isFalse);
  });

  test('excludes video, animation, and invalid image dimensions', () {
    expect(canUseAutoHdrImage(asset), isTrue);
    expect(canUseAutoHdrImage(asset.copyWith(type: AssetType.video)), isFalse);
    expect(canUseAutoHdrImage(asset.copyWith(playbackStyle: AssetPlaybackStyle.imageAnimated)), isFalse);
    expect(canUseAutoHdrImage(asset.copyWith(width: 0)), isFalse);
    expect(canUseAutoHdrImage(LocalAssetStub.image1), isFalse);
  });

  test('uses local originals except when a server edit exists', () {
    final merged = asset.copyWith(remoteId: 'remote');
    expect(nativeImageSource(merged)['localId'], '1');
    expect(nativeImageSource(merged)['remoteUrl'], isNull);
    final edited = nativeImageSource(merged.copyWith(isEdited: true));
    expect(edited['localId'], isNull);
    expect(edited['remoteUrl'], 'http://localhost:3000/assets/remote/original?edited=true');
    expect(nativeImageSource(asset.copyWith(isEdited: true))['localId'], '1');
  });

  test('sizes decodes to the viewport and caps zoom at the original resolution', () {
    expect(autoHdrDecodeSize(const Size(4000, 3000), const Size(400, 800), 3, 1), const Size(1200, 900));
    expect(autoHdrDecodeSize(const Size(3000, 4000), const Size(800, 400), 3, 1), const Size(900, 1200));
    expect(autoHdrDecodeSize(const Size(4000, 3000), const Size(400, 800), 3, 2), const Size(2400, 1800));
    expect(autoHdrDecodeSize(const Size(4000, 3000), const Size(400, 800), 3, 10), const Size(4000, 3000));
    expect(autoHdrDecodeSize(const Size(100, 100), const Size(400, 800), 3, 1), const Size(100, 100));
  });

  testWidgets('keeps the native view during zoom and loads higher resolution after release', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    final loads = <Map<Object?, Object?>>[];
    final nativeIds = <int>[];
    final disposedIds = <int>[];
    late MethodChannel imageChannel;
    late PhotoViewController controller;
    var errors = 0;
    var current = true;
    var nativeGeneration = 0;
    messenger.setMockMethodCallHandler(platformChannel, (call) async {
      final args = call.arguments as Map<Object?, Object?>;
      if (call.method == 'create') {
        final id = args['id']! as int;
        nativeIds.add(id);
        imageChannel = MethodChannel('immich/inline_hdr_image/$id');
        messenger.setMockMethodCallHandler(imageChannel, (call) async {
          if (call.method == 'load') {
            loads.add(Map<Object?, Object?>.from(call.arguments as Map));
          }
          return null;
        });
      } else if (call.method == 'dispose') {
        disposedIds.add(args['id']! as int);
      }
      return null;
    });
    Widget build() => MaterialApp(
      home: MediaQuery(
        data: const MediaQueryData(size: Size(400, 800), devicePixelRatio: 1),
        child: Center(
          child: SizedBox(
            width: 400,
            height: 800,
            child: AutoHdrImage(
              asset: asset,
              viewport: const Size(400, 800),
              isCurrent: current,
              onControllerCreated: (value) => controller = value as PhotoViewController,
              onError: () => errors++,
              builder: (controller, image, size) => PhotoView.customChild(
                controller: controller,
                childSize: size,
                filterQuality: FilterQuality.none,
                child: KeyedSubtree(key: ValueKey(nativeGeneration), child: image),
              ),
            ),
          ),
        ),
      ),
    );
    Future<void> notify(String method) async {
      await messenger.handlePlatformMessage(
        imageChannel.name,
        const StandardMethodCodec().encodeMethodCall(MethodCall(method)),
        (_) {},
      );
      await tester.pump();
    }

    await tester.pumpWidget(build());
    await tester.pump(const Duration(milliseconds: 250));
    expect(nativeIds, hasLength(1));
    expect(loads.single['width'], 400);
    final viewSize = tester.getSize(find.byType(PlatformViewLink));
    expect(find.byType(Thumbnail), findsOneWidget);
    await notify('ready');
    expect(find.byType(Thumbnail), findsNothing);
    // Hero flights can replace the native subtree without replacing AutoHdrImage.
    nativeGeneration++;
    await tester.pumpWidget(build());
    await tester.pump(const Duration(milliseconds: 250));
    expect(nativeIds, hasLength(2));
    expect(loads, hasLength(2));
    expect(loads.last['width'], 400);
    expect(find.byType(Thumbnail), findsOneWidget);
    await notify('ready');
    expect(find.byType(Thumbnail), findsNothing);
    final gesture = await tester.startGesture(tester.getCenter(find.byType(AutoHdrImage)));
    controller.scale = controller.initialScale! * 2;
    await tester.pump(const Duration(milliseconds: 300));
    expect(loads, hasLength(2));
    expect(nativeIds, hasLength(2));
    expect(tester.getSize(find.byType(PlatformViewLink)), viewSize);
    await gesture.up();
    await tester.pump(const Duration(milliseconds: 250));
    expect(loads.last['width'], 800);
    expect(nativeIds, hasLength(2));
    current = false;
    await tester.pumpWidget(build());
    await tester.pump(const Duration(milliseconds: 250));
    expect(loads.last['width'], 400);
    expect(loads.last['current'], isFalse);
    await notify('error');
    await notify('error');
    expect(errors, 1);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    expect(disposedIds, nativeIds);
    await notify('ready');
    expect(errors, 1);
    messenger.setMockMethodCallHandler(imageChannel, null);
    debugDefaultTargetPlatformOverride = null;
  });
}
