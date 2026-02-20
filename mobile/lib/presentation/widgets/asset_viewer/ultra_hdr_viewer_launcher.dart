import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/services/api.service.dart';
import 'package:immich_mobile/utils/image_url_builder.dart';

const MethodChannel _ultraHdrViewerChannel = MethodChannel('immich/ultra_hdr_viewer');

bool canUseNativeUltraHdrViewer(BaseAsset asset) {
  return Platform.isAndroid && asset.isImage && (asset.localId != null || asset.remoteId != null);
}

Future<bool> launchNativeUltraHdrViewer({
  required BuildContext context,
  required BaseAsset asset,
}) async {
  if (!canUseNativeUltraHdrViewer(asset)) {
    return false;
  }

  final size = MediaQuery.sizeOf(context);
  final pixelRatio = MediaQuery.devicePixelRatioOf(context);
  final useLocalAsset = asset.localId != null && !asset.isEdited;
  final localId = useLocalAsset ? asset.localId : null;
  final remoteId = localId == null ? asset.remoteId : null;
  final useEditedRemote = asset.isEdited;

  try {
    final result = await _ultraHdrViewerChannel.invokeMethod<Map<Object?, Object?>>('open', {
      "localId": localId,
      "remoteUrl": remoteId == null ? null : getOriginalUrlForRemoteId(remoteId, edited: useEditedRemote),
      "headers": remoteId == null ? <String, String>{} : ApiService.getRequestHeaders(),
      "width": (size.width * pixelRatio).toInt(),
      "height": (size.height * pixelRatio).toInt(),
      "enableHdr": true,
      "enableGesture": true,
      "minScale": 1.0,
      "maxScale": 6.0,
      "doubleTapScale": 2.0,
    });

    return result?['shouldPopParent'] as bool? ?? false;
  } catch (error) {
    if (kDebugMode) {
      debugPrint('[UltraHDR] failed to open native viewer: $error');
    }

    if (context.mounted) {
      final messenger = ScaffoldMessenger.maybeOf(context);
      messenger?.showSnackBar(const SnackBar(content: Text('Unable to open native HDR viewer')));
    }

    return false;
  }
}
