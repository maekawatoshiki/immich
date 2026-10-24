import 'package:easy_localization/easy_localization.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/constants/locales.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/domain/models/timeline.model.dart';
import 'package:immich_mobile/domain/services/timeline.service.dart';
import 'package:immich_mobile/generated/codegen_loader.g.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/asset_page.widget.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/auto_hdr_image.widget.dart';
import 'package:immich_mobile/presentation/widgets/images/thumbnail.widget.dart';
import 'package:immich_mobile/providers/asset_viewer/asset_viewer.provider.dart';
import 'package:immich_mobile/providers/infrastructure/timeline.provider.dart';
import 'package:immich_mobile/widgets/photo_view/photo_view.dart';
import 'package:mocktail/mocktail.dart';

import '../../../fixtures/asset.stub.dart';
import '../../../unit/presentation/presentation_context.dart';

class _HdrTimeline extends TimelineService {
  final List<BaseAsset> assets;

  _HdrTimeline(this.assets)
    : super((
        assetSource: (_, _) async => assets,
        bucketSource: () => Stream.value([Bucket(assetCount: assets.length)]),
        origin: TimelineOrigin.main,
      ));

  @override
  int get totalAssets => assets.length;

  @override
  BaseAsset? getAssetSafe(int index) => assets[index];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late PresentationContext context;
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const platformChannel = MethodChannel('flutter/platform_views_2');
  final channels = <int, MethodChannel>{};

  setUp(() async {
    context = await PresentationContext.create();
  });

  tearDown(() async {
    debugDefaultTargetPlatformOverride = null;
    messenger.setMockMethodCallHandler(platformChannel, null);
    for (final channel in channels.values) {
      messenger.setMockMethodCallHandler(channel, null);
    }
    channels.clear();
    await context.dispose();
  });

  testWidgets('keeps preloaded HDR views and placeholders hidden when a swipe settles', (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    final assets = [
      LocalAssetStub.image1.copyWith(id: '1', width: 4000, height: 3000),
      LocalAssetStub.image2.copyWith(id: '2', width: 4000, height: 3000),
    ];
    for (final asset in assets) {
      when(() => context.service.asset.service.watchAsset(asset)).thenAnswer((_) => const Stream.empty());
    }
    final created = <(String, int)>[];
    final disposed = <int>[];
    final loads = <int, List<Map<Object?, Object?>>>{};
    messenger.setMockMethodCallHandler(platformChannel, (call) async {
      final args = call.arguments as Map<Object?, Object?>;
      final id = args['id']! as int;
      if (call.method == 'create') {
        final params =
            const StandardMessageCodec().decodeMessage(ByteData.sublistView(args['params']! as Uint8List))!
                as Map<Object?, Object?>;
        created.add((params['localId']! as String, id));
        final channel = MethodChannel('immich/inline_hdr_image/$id');
        channels[id] = channel;
        loads[id] = [];
        messenger.setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'load') {
            loads[id]!.add(Map<Object?, Object?>.from(call.arguments as Map));
          }
          return null;
        });
      } else if (call.method == 'dispose') {
        disposed.add(id);
      }
      return null;
    });

    final container = ProviderContainer(
      overrides: [...context.overrides, timelineServiceProvider.overrideWithValue(_HdrTimeline(assets))],
    );
    addTearDown(container.dispose);
    final viewer = container.read(assetViewerProvider.notifier)..setAsset(assets.first);
    final pages = PageController();
    addTearDown(pages.dispose);
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
              home: Material(
                child: NotificationListener<ScrollEndNotification>(
                  onNotification: (notification) {
                    if (notification.depth == 0) {
                      viewer.setAsset(assets[pages.page!.round()]);
                    }
                    return false;
                  },
                  child: PhotoViewGestureDetectorScope(
                    axis: Axis.horizontal,
                    child: PageView.builder(
                      controller: pages,
                      allowImplicitScrolling: true,
                      itemCount: assets.length,
                      itemBuilder: (_, index) => AssetPage(index: index, heroOffset: 0, useAutoHdr: true),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 250));
    expect(created.map((view) => view.$1), ['1', '2']);
    final originalViews = List.of(created);
    for (final channel in channels.values.toList()) {
      await messenger.handlePlatformMessage(
        channel.name,
        const StandardMethodCodec().encodeMethodCall(const MethodCall('ready')),
        (_) {},
      );
    }
    await tester.pump();
    final placeholders = find.descendant(
      of: find.byType(AutoHdrImage, skipOffstage: false),
      matching: find.byType(Thumbnail, skipOffstage: false),
    );
    expect(placeholders, findsNothing);

    for (final index in [1, 0, 1, 0]) {
      await tester.drag(find.byType(PageView), Offset(index == 1 ? -400 : 400, 0));
      await tester.pumpAndSettle();
      expect(container.read(assetViewerProvider).currentAsset, assets[index]);
      expect(created, originalViews, reason: 'Selecting a preloaded page must keep its native view.');
      expect(disposed, isEmpty);
      expect(placeholders, findsNothing);
      final activeHeroes = find.byType(Hero, skipOffstage: false).evaluate().where((element) {
        return element.findAncestorWidgetOfExactType<HeroMode>()?.enabled ?? true;
      });
      expect(activeHeroes.map((element) => (element.widget as Hero).tag), ['${assets[index].heroTag}_0']);
      for (final (source, id) in originalViews) {
        expect(loads[id]!.last['current'], source == assets[index].localId);
      }
    }

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    expect(disposed, unorderedEquals(originalViews.map((view) => view.$2)));
    debugDefaultTargetPlatformOverride = null;
  });
}
