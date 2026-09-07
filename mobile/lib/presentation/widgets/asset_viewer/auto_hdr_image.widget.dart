import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/ultra_hdr_viewer_launcher.dart';
import 'package:immich_mobile/presentation/widgets/images/thumbnail.widget.dart';
import 'package:immich_mobile/widgets/photo_view/photo_view.dart';

const autoHdrEnabled = bool.fromEnvironment('IMMICH_AUTO_HDR');

Future<bool> checkAutoHdrSupport({bool enabled = autoHdrEnabled}) async {
  if (!enabled || kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
    return false;
  }
  try {
    const channel = MethodChannel('immich/ultra_hdr_viewer');
    return await channel.invokeMethod<bool>('supportsInline') == true &&
        await HybridAndroidViewController.checkIfSupported();
  } on PlatformException {
    return false;
  } on MissingPluginException {
    return false;
  }
}

bool canUseAutoHdrImage(BaseAsset asset) =>
    asset.isImage &&
    !asset.isAnimatedImage &&
    (asset.width ?? 0) > 0 &&
    (asset.height ?? 0) > 0 &&
    (asset.localId != null || asset.remoteId != null);

Object autoHdrImageIdentity(BaseAsset asset) => (asset.localId, asset.remoteId, asset.isEdited, asset.updatedAt);

/// Decode only the resolution needed on screen, never larger than the original.
Size autoHdrDecodeSize(Size original, Size viewport, double pixelRatio, double zoom) {
  final fitted = applyBoxFit(BoxFit.contain, original, viewport).destination;
  final ratio = math.min(1.0, fitted.width * pixelRatio * math.max(1.0, zoom) / original.width);
  return Size(
    math.max(1, (original.width * ratio).ceil()).toDouble(),
    math.max(1, (original.height * ratio).ceil()).toDouble(),
  );
}

typedef AutoHdrPhotoBuilder = Widget Function(PhotoViewController controller, Widget image, Size size);

/// Owns a fixed native view and a PhotoView controller; gestures remain in PhotoView.
class AutoHdrImage extends StatefulWidget {
  final BaseAsset asset;
  final Size viewport;
  final bool isCurrent;
  final AutoHdrPhotoBuilder builder;
  final ValueChanged<PhotoViewControllerBase> onControllerCreated;
  final VoidCallback onError;

  const AutoHdrImage({
    super.key,
    required this.asset,
    required this.viewport,
    required this.isCurrent,
    required this.builder,
    required this.onControllerCreated,
    required this.onError,
  });

  @override
  State<AutoHdrImage> createState() => _AutoHdrImageState();
}

class _AutoHdrImageState extends State<AutoHdrImage> {
  final _controller = PhotoViewController();
  StreamSubscription<PhotoViewControllerValue>? _transforms;
  Animation<double>? _routeAnimation;
  MethodChannel? _channel;
  Timer? _resolutionTimer;
  Size? _lastResolution;
  bool? _lastCurrent;
  double _pixelRatio = 1;
  double _zoom = 1;
  int _pointers = 0;
  bool _created = false;
  bool _ready = false;
  bool _failed = false;

  Size get _originalSize => Size(widget.asset.width!.toDouble(), widget.asset.height!.toDouble());

  @override
  void initState() {
    super.initState();
    widget.onControllerCreated(_controller);
    _transforms = _controller.outputStateStream.listen(_onTransform);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final animation = ModalRoute.of(context)?.animation;
    if (animation != _routeAnimation) {
      _routeAnimation?.removeStatusListener(_onRouteStatus);
      _routeAnimation = animation;
      animation?.addStatusListener(_onRouteStatus);
    }
    _pixelRatio = MediaQuery.devicePixelRatioOf(context);
    _scheduleResolution();
  }

  void _onRouteStatus(AnimationStatus status) {
    if (status == AnimationStatus.completed) {
      unawaited(_finishTransition());
    }
  }

  Future<void> _finishTransition() async {
    final channel = _channel;
    if (!_created || !mounted || (_routeAnimation != null && _routeAnimation!.status != AnimationStatus.completed)) {
      return;
    }
    try {
      await channel?.invokeMethod<void>('finishTransition');
    } on PlatformException {
      if (_channel == channel) {
        _fail();
      }
    } on MissingPluginException {
      if (_channel == channel) {
        _fail();
      }
    }
  }

  @override
  void didUpdateWidget(AutoHdrImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isCurrent != oldWidget.isCurrent || widget.viewport != oldWidget.viewport) {
      unawaited(_load());
    }
  }

  void _onTransform(PhotoViewControllerValue value) {
    final initial = _controller.initialScale;
    if (initial == null || initial <= 0 || value.scale == null) {
      return;
    }
    final zoom = math.max(1.0, value.scale! / initial);
    if (zoom != _zoom) {
      _zoom = zoom;
      _scheduleResolution();
    }
  }

  void _scheduleResolution() {
    _resolutionTimer?.cancel();
    if (_pointers == 0) {
      _resolutionTimer = Timer(const Duration(milliseconds: 200), () => unawaited(_load()));
    }
  }

  void _endPointer(PointerEvent _) {
    _pointers = math.max(0, _pointers - 1);
    _scheduleResolution();
  }

  Future<void> _load() async {
    final channel = _channel;
    if (!_created || channel == null || _failed || !mounted) {
      return;
    }
    final size = autoHdrDecodeSize(_originalSize, widget.viewport, _pixelRatio, widget.isCurrent ? _zoom : 1);
    if (size == _lastResolution && widget.isCurrent == _lastCurrent) {
      return;
    }
    _lastResolution = size;
    _lastCurrent = widget.isCurrent;
    try {
      await channel.invokeMethod<void>('load', {
        'width': size.width.toInt(),
        'height': size.height.toInt(),
        'current': widget.isCurrent,
      });
    } on PlatformException {
      if (_channel == channel && !_ready) {
        _fail();
      }
    } on MissingPluginException {
      if (_channel == channel && !_ready) {
        _fail();
      }
    }
  }

  void _fail() {
    if (!mounted || _failed) {
      return;
    }
    _failed = true;
    widget.onError();
  }

  Future<Object?> _onNativeCall(MethodCall call) async {
    if (!mounted || _failed) {
      return null;
    }
    switch (call.method) {
      case 'ready':
        setState(() => _ready = true);
        unawaited(_finishTransition());
      case 'error':
        _fail();
    }
    return null;
  }

  AndroidViewController _create(PlatformViewCreationParams params) {
    // A Hero flight can replace this native view while keeping AutoHdrImage alive.
    // Decode state belongs to the new view, not to the previous channel.
    _channel?.setMethodCallHandler(null);
    final channel = MethodChannel('immich/inline_hdr_image/${params.id}');
    _channel = channel;
    _created = false;
    _ready = false;
    _lastResolution = null;
    _lastCurrent = null;
    channel.setMethodCallHandler((call) async {
      if (_channel != channel) {
        return null;
      }
      return _onNativeCall(call);
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _channel == channel) {
        setState(() {});
      }
    });
    final controller = PlatformViewsService.initHybridAndroidView(
      id: params.id,
      viewType: 'immich/inline_hdr_image',
      layoutDirection: TextDirection.ltr,
      creationParams: {...nativeImageSource(widget.asset), 'current': widget.isCurrent},
      creationParamsCodec: const StandardMessageCodec(),
    );
    controller.addOnPlatformViewCreatedListener((id) {
      params.onPlatformViewCreated(id);
      if (mounted && _channel == channel) {
        _created = true;
        unawaited(_load());
      }
    });
    unawaited(controller.create().catchError((Object _) => _fail()));
    return controller;
  }

  @override
  Widget build(BuildContext context) {
    final size = applyBoxFit(BoxFit.contain, _originalSize, widget.viewport).destination;
    final image = IgnorePointer(
      child: Stack(
        fit: StackFit.expand,
        children: [
          PlatformViewLink(
            viewType: 'immich/inline_hdr_image',
            onCreatePlatformView: _create,
            surfaceFactory: (context, controller) => AndroidViewSurface(
              controller: controller as AndroidViewController,
              gestureRecognizers: const {},
              hitTestBehavior: PlatformViewHitTestBehavior.transparent,
            ),
          ),
          if (!_ready) Thumbnail.fromAsset(asset: widget.asset, fit: BoxFit.contain),
        ],
      ),
    );
    return Listener(
      onPointerDown: (_) {
        _pointers++;
        _resolutionTimer?.cancel();
      },
      onPointerUp: _endPointer,
      onPointerCancel: _endPointer,
      child: widget.builder(_controller, image, size),
    );
  }

  @override
  void dispose() {
    _resolutionTimer?.cancel();
    _routeAnimation?.removeStatusListener(_onRouteStatus);
    unawaited(_transforms?.cancel());
    _channel?.setMethodCallHandler(null);
    _controller.dispose();
    super.dispose();
  }
}
