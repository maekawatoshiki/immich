import 'dart:async';

import 'package:flutter/material.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/domain/services/timeline.service.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/auto_hdr_image.widget.dart';
import 'package:immich_mobile/presentation/widgets/images/image_provider.dart';

class AssetPreloader {
  static final _dummyListener = ImageStreamListener((image, _) => image.dispose());

  final TimelineService timelineService;
  final bool Function() mounted;
  bool nativeImages = false;

  Timer? _timer;
  ImageStream? _prevStream;
  ImageStream? _nextStream;

  AssetPreloader({required this.timelineService, required this.mounted});

  void preload(int index, Size size) {
    unawaited(timelineService.preloadAssets(index));
    _timer?.cancel();
    _timer = Timer(Durations.medium4, () async {
      if (!mounted()) {
        return;
      }
      final (prev, next) = await (
        timelineService.getAssetAsync(index - 1),
        timelineService.getAssetAsync(index + 1),
      ).wait;
      if (!mounted()) {
        return;
      }
      _prevStream?.removeListener(_dummyListener);
      _nextStream?.removeListener(_dummyListener);
      _prevStream = prev != null ? _resolveImage(prev, size) : null;
      _nextStream = next != null ? _resolveImage(next, size) : null;
    });
  }

  ImageStream _resolveImage(BaseAsset asset, Size size) {
    if (nativeImages && canUseAutoHdrImage(asset)) {
      final thumbnail = getThumbnailImageProvider(asset);
      if (thumbnail != null) {
        return thumbnail.resolve(ImageConfiguration.empty)..addListener(_dummyListener);
      }
    }
    return getFullImageProvider(
      asset,
      size: size,
      preferLocal: asset.hasLocal && (!asset.isEdited || !asset.hasRemote),
    ).resolve(ImageConfiguration.empty)..addListener(_dummyListener);
  }

  void dispose() {
    _timer?.cancel();
    _prevStream?.removeListener(_dummyListener);
    _nextStream?.removeListener(_dummyListener);
  }
}
