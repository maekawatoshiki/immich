import 'package:drift/drift.dart' hide isNotNull, isNull;
import 'package:drift/native.dart';
import 'package:easy_localization/easy_localization.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/constants/locales.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/domain/models/timeline.model.dart';
import 'package:immich_mobile/domain/services/store.service.dart';
import 'package:immich_mobile/domain/services/timeline.service.dart';
import 'package:immich_mobile/generated/codegen_loader.g.dart';
import 'package:immich_mobile/infrastructure/repositories/db.repository.dart';
import 'package:immich_mobile/infrastructure/repositories/settings.repository.dart';
import 'package:immich_mobile/infrastructure/repositories/store.repository.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/asset_page.widget.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/auto_hdr_image.widget.dart';
import 'package:immich_mobile/providers/asset_viewer/is_motion_video_playing.provider.dart';
import 'package:immich_mobile/providers/infrastructure/timeline.provider.dart';
import 'package:immich_mobile/providers/user.provider.dart';
import 'package:immich_mobile/widgets/photo_view/photo_view.dart';
import 'package:mocktail/mocktail.dart';

import '../../../fixtures/asset.stub.dart';
import '../../../service.mocks.dart';
import '../../../unit/factories/user_factory.dart';

class _SingleAssetTimeline extends TimelineService {
  final BaseAsset asset;

  _SingleAssetTimeline(this.asset)
    : super((
        assetSource: (_, __) async => [],
        bucketSource: () => Stream.value(const [Bucket(assetCount: 1)]),
        origin: TimelineOrigin.main,
      ));

  @override
  int get totalAssets => 1;

  @override
  BaseAsset? getAssetSafe(int index) => index == 0 ? asset : null;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const platformChannel = MethodChannel('flutter/platform_views_2');
  const viewerChannel = MethodChannel('immich/ultra_hdr_viewer');
  final imageChannels = <MethodChannel>[];
  late Drift db;

  setUpAll(() async {
    db = Drift(DatabaseConnection(NativeDatabase.memory(), closeStreamsSynchronously: true));
    await SettingsRepository.ensureInitialized(db);
    await StoreService.init(storeRepository: DriftStoreRepository(db), listenUpdates: false);
  });
  tearDownAll(() => db.close());

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    messenger.setMockMethodCallHandler(platformChannel, null);
    messenger.setMockMethodCallHandler(viewerChannel, null);
    for (final channel in imageChannels) {
      messenger.setMockMethodCallHandler(channel, null);
    }
    imageChannels.clear();
  });

  for (final useAutoHdr in [false, true]) {
    for (final motionPhoto in [false, true]) {
      testWidgets('long press with auto HDR=$useAutoHdr, motion photo=$motionPhoto', (tester) async {
        debugDefaultTargetPlatformOverride = TargetPlatform.android;
        final viewerCalls = <String>[];
        messenger.setMockMethodCallHandler(viewerChannel, (call) async {
          viewerCalls.add(call.method);
          return null;
        });
        messenger.setMockMethodCallHandler(platformChannel, (call) async {
          if (call.method == 'create') {
            final args = call.arguments as Map<Object?, Object?>;
            final channel = MethodChannel('immich/inline_hdr_image/${args['id']}');
            imageChannels.add(channel);
            messenger.setMockMethodCallHandler(channel, (_) async => null);
          }
          return null;
        });
        final asset = LocalAssetStub.image1.copyWith(
          id: '1',
          width: 4000,
          height: 3000,
          playbackStyle: motionPhoto ? AssetPlaybackStyle.livePhoto : AssetPlaybackStyle.image,
        );
        final userService = MockUserService();
        when(userService.tryGetMyUser).thenReturn(UserFactory.createDto());
        when(userService.watchMyUser).thenAnswer((_) => const Stream.empty());
        final container = ProviderContainer(
          overrides: [
            timelineServiceProvider.overrideWithValue(_SingleAssetTimeline(asset)),
            currentUserProvider.overrideWith((_) => CurrentUserProvider(userService)),
          ],
        );
        addTearDown(container.dispose);
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: EasyLocalization(
              supportedLocales: locales.values.toList(),
              path: translationsPath,
              startLocale: locales.values.first,
              fallbackLocale: locales.values.first,
              saveLocale: false,
              useFallbackTranslations: true,
              assetLoader: const CodegenLoader(),
              child: Builder(
                builder: (context) => MaterialApp(
                  localizationsDelegates: context.localizationDelegates,
                  supportedLocales: context.supportedLocales,
                  locale: context.locale,
                  home: Material(child: AssetPage(index: 0, heroOffset: 0, useAutoHdr: useAutoHdr)),
                ),
              ),
            ),
          ),
        );
        await tester.pump(const Duration(milliseconds: 250));
        expect(find.byType(AutoHdrImage), useAutoHdr ? findsOneWidget : findsNothing);
        final photoView = tester.widget<PhotoView>(find.byType(PhotoView));
        expect(photoView.onLongPressStart, motionPhoto ? isNotNull : isNull);
        expect(container.read(isPlayingMotionVideoProvider), isFalse);

        // Exercise the callback wired into each PhotoView without mounting the
        // native video player, which is outside this gesture regression test.
        if (motionPhoto) {
          final controller = PhotoViewController();
          addTearDown(controller.dispose);
          photoView.onLongPressStart!(
            tester.element(find.byType(PhotoView)),
            const LongPressStartDetails(),
            controller.value,
          );
        } else {
          await tester.longPress(find.byType(PhotoView));
        }

        expect(container.read(isPlayingMotionVideoProvider), motionPhoto);
        expect(viewerCalls, isEmpty);
        await tester.pumpWidget(const SizedBox.shrink());
        debugDefaultTargetPlatformOverride = null;
      });
    }
  }
}
