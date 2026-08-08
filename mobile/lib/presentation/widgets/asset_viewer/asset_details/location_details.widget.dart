import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/domain/models/exif.model.dart';
import 'package:immich_mobile/extensions/build_context_extensions.dart';
import 'package:immich_mobile/extensions/theme_extensions.dart';
import 'package:immich_mobile/extensions/translate_extensions.dart';
import 'package:immich_mobile/presentation/actions/edit_location.action.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/sheet_tile.widget.dart';
import 'package:immich_mobile/utils/debug_print.dart';
import 'package:url_launcher/url_launcher.dart';

class LocationDetails extends ConsumerWidget {
  final BaseAsset asset;
  final ExifInfo? exifInfo;

  const LocationDetails({super.key, required this.asset, this.exifInfo});

  Future<void> _openMap(double latitude, double longitude) async {
    const zoomLevel = 16;
    Uri? uri;

    if (Platform.isAndroid) {
      final androidUri = Uri(
        scheme: 'geo',
        host: '$latitude,$longitude',
        queryParameters: {'z': '$zoomLevel', 'q': '$latitude,$longitude'},
      );
      if (await canLaunchUrl(androidUri)) {
        uri = androidUri;
      }
    } else if (Platform.isIOS) {
      final appleMapsUri = Uri.https('maps.apple.com', '/', {
        'll': '$latitude,$longitude',
        'q': '$latitude,$longitude',
        'z': '$zoomLevel',
      });
      if (await canLaunchUrl(appleMapsUri)) {
        uri = appleMapsUri;
      }
    }

    uri ??= Uri(
      scheme: 'https',
      host: 'openstreetmap.org',
      queryParameters: {'mlat': '$latitude', 'mlon': '$longitude'},
      fragment: 'map=$zoomLevel/$latitude/$longitude',
    );
    dPrint(() => 'Opening Map Uri: $uri');
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hasCoordinates = exifInfo?.hasCoordinates ?? false;

    // Guard local assets
    if (asset is! RemoteAsset) {
      return const SizedBox.shrink();
    }

    final editLocation = const EditLocationAction(source: .viewer).create(context, ref);
    final latitude = exifInfo?.latitude;
    final longitude = exifInfo?.longitude;
    final coordinates = "${exifInfo?.latitude?.toStringAsFixed(4)}, ${exifInfo?.longitude?.toStringAsFixed(4)}";

    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SheetTile(
            title: 'location'.t(context: context),
            titleStyle: context.textTheme.labelLarge?.copyWith(color: context.colorScheme.onSurfaceSecondary),
            trailing: hasCoordinates && editLocation != null ? const Icon(Icons.edit_location_alt, size: 20) : null,
            onTap: editLocation?.onAction,
          ),
          if (hasCoordinates)
            Padding(
              padding: EdgeInsets.symmetric(horizontal: context.isMobile ? 16.0 : 56.0),
              child: InkWell(
                onTap: latitude != null && longitude != null ? () => unawaited(_openMap(latitude, longitude)) : null,
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    coordinates,
                    style: context.textTheme.bodySmall?.copyWith(
                      color: context.primaryColor,
                      decoration: TextDecoration.underline,
                    ),
                  ),
                ),
              ),
            ),
          if (!hasCoordinates)
            SheetTile(
              title: "add_a_location".t(context: context),
              titleStyle: context.textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.w600,
                color: context.primaryColor,
              ),
              leading: const Icon(Icons.location_off),
              onTap: editLocation?.onAction,
            ),
        ],
      ),
    );
  }
}
