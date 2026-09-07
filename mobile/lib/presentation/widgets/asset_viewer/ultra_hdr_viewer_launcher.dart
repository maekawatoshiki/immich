import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/services/api.service.dart';
import 'package:immich_mobile/utils/debug_print.dart';
import 'package:immich_mobile/utils/image_url_builder.dart';

const _ultraHdrViewerChannel = MethodChannel('immich/ultra_hdr_viewer');

Map<String, Object?> nativeImageSource(BaseAsset asset) {
  final useLocal = asset.localId != null && (!asset.isEdited || asset.remoteId == null);
  return {
    'localId': useLocal ? asset.localId : null,
    'remoteUrl': useLocal || asset.remoteId == null
        ? null
        : getOriginalUrlForRemoteId(asset.remoteId!, edited: asset.isEdited),
    'headers': useLocal ? <String, String>{} : ApiService.getRequestHeaders(),
  };
}

bool canUseNativeUltraHdrViewer(BaseAsset asset) {
  return Platform.isAndroid &&
      asset.isImage &&
      !asset.isAnimatedImage &&
      (asset.localId != null || asset.remoteId != null);
}

Future<void> launchNativeUltraHdrViewer({required BuildContext context, required BaseAsset asset}) async {
  if (!canUseNativeUltraHdrViewer(asset)) {
    return;
  }

  final size = MediaQuery.sizeOf(context);
  final pixelRatio = MediaQuery.devicePixelRatioOf(context);

  try {
    await _ultraHdrViewerChannel.invokeMethod<void>('open', {
      ...nativeImageSource(asset),
      'width': (size.width * pixelRatio).round(),
      'height': (size.height * pixelRatio).round(),
    });
  } catch (error) {
    dPrint(() => '[UltraHDR] failed to open native viewer: $error');
    if (context.mounted) {
      ScaffoldMessenger.maybeOf(
        context,
      )?.showSnackBar(const SnackBar(content: Text('Unable to open native HDR viewer')));
    }
  }
}
