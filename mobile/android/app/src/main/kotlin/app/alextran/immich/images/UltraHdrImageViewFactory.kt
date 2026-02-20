package app.alextran.immich.images

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore.Images
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import androidx.annotation.RequiresApi
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class UltraHdrRequest(
  val localId: String?,
  val remoteUrl: String?,
  val headers: Map<String, String>,
  val width: Int,
  val height: Int,
  val enableHdr: Boolean,
  val enableGesture: Boolean,
  val minScale: Float,
  val maxScale: Float,
  val doubleTapScale: Float,
)

data class DecodedImage(
  val bitmap: Bitmap,
  val hasGainMap: Boolean,
)

fun parseRequest(args: Any?): UltraHdrRequest {
  val map = args as? Map<*, *> ?: emptyMap<String, Any?>()
  val headersAny = map["headers"] as? Map<*, *> ?: emptyMap<String, String>()
  val headers = headersAny.entries.mapNotNull { (key, value) ->
    if (key is String && value is String) {
      key to value
    } else {
      null
    }
  }.toMap()

  return UltraHdrRequest(
    localId = map["localId"] as? String,
    remoteUrl = map["remoteUrl"] as? String,
    headers = headers,
    width = (map["width"] as? Number)?.toInt() ?: 0,
    height = (map["height"] as? Number)?.toInt() ?: 0,
    enableHdr = map["enableHdr"] as? Boolean ?: false,
    enableGesture = map["enableGesture"] as? Boolean ?: true,
    minScale = (map["minScale"] as? Number)?.toFloat() ?: 1.0f,
    maxScale = (map["maxScale"] as? Number)?.toFloat() ?: 6.0f,
    doubleTapScale = (map["doubleTapScale"] as? Number)?.toFloat() ?: 2.0f,
  )
}

fun decodeWithGlide(context: Context, request: UltraHdrRequest): Bitmap? {
  val requestManager = Glide.with(context)
  val builder = requestManager
    .asBitmap()
    .priority(Priority.IMMEDIATE)
    .disallowHardwareConfig()
    .format(DecodeFormat.PREFER_ARGB_8888)

  val localId = request.localId?.toLongOrNull() ?: return null
  val uri = ContentUris.withAppendedId(Images.Media.EXTERNAL_CONTENT_URI, localId)
  val futureTarget = builder.load(uri).submit(
    if (request.width > 0) request.width else SIZE_ORIGINAL,
    if (request.height > 0) request.height else SIZE_ORIGINAL,
  )

  return try {
    futureTarget.get()
  } finally {
    requestManager.clear(futureTarget)
  }
}

@RequiresApi(Build.VERSION_CODES.P)
fun decodeWithImageDecoder(
  context: Context,
  request: UltraHdrRequest,
  signal: CancellationSignal,
  preferHdrQuality: Boolean,
): Bitmap? {
  val source = when {
    request.localId != null -> {
      val id = request.localId.toLongOrNull() ?: return null
      val uri = ContentUris.withAppendedId(Images.Media.EXTERNAL_CONTENT_URI, id)
      ImageDecoder.createSource(context.contentResolver, uri)
    }

    request.remoteUrl != null -> {
      val bytes = ImageFetcherManager.fetchBytesBlocking(
        request.remoteUrl,
        request.headers,
        signal,
      )
      if (bytes.isEmpty()) {
        return null
      }
      ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    }

    else -> return null
  }

  val target = Size(request.width, request.height)
  return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE

    if (preferHdrQuality) {
      runCatching {
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
      }
      return@decodeBitmap
    }

    if (target.width > 0 && target.height > 0) {
      val sample = max(1, min(info.size.width / target.width, info.size.height / target.height))
      decoder.setTargetSampleSize(sample)
    }
  }
}

fun decodeWithBitmapFactory(
  context: Context,
  request: UltraHdrRequest,
  signal: CancellationSignal,
): Bitmap? {
  return if (request.localId != null) {
    decodeWithGlide(context, request)
  } else if (request.remoteUrl != null) {
    val bytes = ImageFetcherManager.fetchBytesBlocking(
      request.remoteUrl,
      request.headers,
      signal,
    )
    if (bytes.isEmpty()) {
      null
    } else {
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
  } else {
    null
  }
}

fun decodeImage(
  context: Context,
  request: UltraHdrRequest,
  signal: CancellationSignal,
  preferHdrQuality: Boolean,
): DecodedImage? {
  val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    decodeWithImageDecoder(context, request, signal, preferHdrQuality)
  } else {
    decodeWithBitmapFactory(context, request, signal)
  } ?: return null

  val hasGainMap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()
  return DecodedImage(bitmap = bitmap, hasGainMap = hasGainMap)
}

class ZoomableImageView(
  context: Context,
  request: UltraHdrRequest,
  private val onSingleTap: () -> Unit,
  private val onZoomStateChanged: (Float, Boolean) -> Unit,
  private val onDismissDragUpdate: (Float, Float) -> Unit,
  private val onDismissDragEnd: (Boolean) -> Unit,
  private val onDismissDragCancel: () -> Unit,
) : ImageView(context) {
  private var imageWidthPx = 0f
  private var imageHeightPx = 0f
  private var zoomScale = max(1f, request.minScale)
  private var panX = 0f
  private var panY = 0f
  private var gestureEnabled = request.enableGesture
  private var dismissEnabled = request.enableGesture

  private val minScale = max(1f, request.minScale)
  private val maxScale = max(minScale, request.maxScale)
  private val doubleTapScale = request.doubleTapScale.coerceIn(minScale, maxScale)

  private val density = resources.displayMetrics.density
  private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
  private val dismissThresholdPx = 75f * density
  private val dismissDragRatio = 0.2f
  private val scaleEpsilon = 0.001f

  private var lastTouchDownX = 0f
  private var lastTouchDownY = 0f
  private var dismissTracking = false
  private var dismissOffsetY = 0f

  private var lastReportedScale = Float.NaN
  private var lastReportedAtBaseScale: Boolean? = null

  private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
      if (!gestureEnabled) {
        return false
      }
      cancelDismissGesture(sendCancelEvent = false)
      return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
      if (!gestureEnabled) {
        return false
      }
      val target = (zoomScale * detector.scaleFactor).coerceIn(minScale, maxScale)
      zoomAt(detector.focusX, detector.focusY, target)
      parent?.requestDisallowInterceptTouchEvent(!isAtBaseScale())
      return true
    }
  })

  private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent): Boolean = gestureEnabled

    override fun onScroll(
      e1: MotionEvent?,
      e2: MotionEvent,
      distanceX: Float,
      distanceY: Float,
    ): Boolean {
      if (!gestureEnabled || !canPanImage()) {
        return false
      }
      panX -= distanceX
      panY -= distanceY
      applyMatrix()
      parent?.requestDisallowInterceptTouchEvent(true)
      return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
      if (!gestureEnabled) {
        return false
      }
      val target = if (abs(zoomScale - minScale) < 0.01f) {
        doubleTapScale
      } else {
        minScale
      }
      zoomAt(e.x, e.y, target)
      parent?.requestDisallowInterceptTouchEvent(!isAtBaseScale())
      return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
      performClick()
      onSingleTap()
      return true
    }
  })

  init {
    scaleType = ScaleType.FIT_CENTER
    isClickable = true
    isFocusable = true
    setOnTouchListener { _, event ->
      if (!gestureEnabled) {
        return@setOnTouchListener false
      }

      val dismissHandled = handleDismissGesture(event)
      if (dismissHandled) {
        return@setOnTouchListener true
      }

      val scaleHandled = scaleDetector.onTouchEvent(event)
      val gestureHandled = gestureDetector.onTouchEvent(event)

      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_POINTER_DOWN,
        MotionEvent.ACTION_MOVE -> {
          parent?.requestDisallowInterceptTouchEvent(dismissTracking || !isAtBaseScale())
        }

        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL -> {
          if (!scaleDetector.isInProgress && !dismissTracking && isAtBaseScale()) {
            parent?.requestDisallowInterceptTouchEvent(false)
          }
        }
      }

      scaleHandled || gestureHandled || true
    }
  }

  override fun performClick(): Boolean {
    return super.performClick()
  }

  fun setGestureEnabled(enabled: Boolean) {
    gestureEnabled = enabled
    if (!enabled) {
      cancelDismissGesture()
      parent?.requestDisallowInterceptTouchEvent(false)
    }
  }

  fun setDismissEnabled(enabled: Boolean) {
    dismissEnabled = enabled
    if (!enabled) {
      cancelDismissGesture()
      parent?.requestDisallowInterceptTouchEvent(false)
    }
  }

  fun setBitmap(bitmap: Bitmap) {
    setImageBitmap(bitmap)
    imageWidthPx = bitmap.width.toFloat()
    imageHeightPx = bitmap.height.toFloat()
    resetTransform()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    applyMatrix()
  }

  fun resetTransform() {
    zoomScale = minScale
    panX = 0f
    panY = 0f
    applyMatrix()
  }

  private fun zoomAt(focusX: Float, focusY: Float, targetScale: Float) {
    cancelDismissGesture(sendCancelEvent = false)

    if (width <= 0 || height <= 0 || imageWidthPx <= 0f || imageHeightPx <= 0f) {
      zoomScale = targetScale.coerceIn(minScale, maxScale)
      reportZoomState()
      return
    }

    val viewW = width.toFloat()
    val viewH = height.toFloat()
    val fitScale = min(viewW / imageWidthPx, viewH / imageHeightPx)

    val oldScale = zoomScale
    val newScale = targetScale.coerceIn(minScale, maxScale)
    if (abs(newScale - oldScale) < 0.0001f) {
      return
    }

    val absScaleOld = fitScale * oldScale
    val absScaleNew = fitScale * newScale

    val oldLeft = (viewW - imageWidthPx * absScaleOld) / 2f + panX
    val oldTop = (viewH - imageHeightPx * absScaleOld) / 2f + panY

    val imageX = (focusX - oldLeft) / absScaleOld
    val imageY = (focusY - oldTop) / absScaleOld

    val centeredLeftNew = (viewW - imageWidthPx * absScaleNew) / 2f
    val centeredTopNew = (viewH - imageHeightPx * absScaleNew) / 2f

    panX = focusX - centeredLeftNew - imageX * absScaleNew
    panY = focusY - centeredTopNew - imageY * absScaleNew

    zoomScale = newScale
    applyMatrix()
  }

  private fun applyMatrix() {
    if (width <= 0 || height <= 0 || imageWidthPx <= 0f || imageHeightPx <= 0f) {
      return
    }

    if (isAtBaseScale()) {
      panX = 0f
      panY = 0f
      if (scaleType != ScaleType.FIT_CENTER) {
        scaleType = ScaleType.FIT_CENTER
        imageMatrix = Matrix()
      }
      reportZoomState()
      return
    }

    if (scaleType != ScaleType.MATRIX) {
      scaleType = ScaleType.MATRIX
    }

    val viewW = width.toFloat()
    val viewH = height.toFloat()
    val fitScale = min(viewW / imageWidthPx, viewH / imageHeightPx)
    val absScale = fitScale * zoomScale

    val displayW = imageWidthPx * absScale
    val displayH = imageHeightPx * absScale

    val maxPanX = max(0f, (displayW - viewW) / 2f)
    val maxPanY = max(0f, (displayH - viewH) / 2f)
    panX = panX.coerceIn(-maxPanX, maxPanX)
    panY = panY.coerceIn(-maxPanY, maxPanY)

    val left = (viewW - displayW) / 2f + panX
    val top = (viewH - displayH) / 2f + panY

    val matrix = Matrix().apply {
      postScale(absScale, absScale)
      postTranslate(left, top)
    }

    imageMatrix = matrix
    reportZoomState()
  }

  private fun isAtBaseScale(): Boolean {
    return zoomScale <= minScale + scaleEpsilon
  }

  private fun canPanImage(): Boolean {
    return zoomScale > minScale + scaleEpsilon
  }

  private fun reportZoomState() {
    val atBaseScale = isAtBaseScale()
    val shouldReport =
      abs(zoomScale - lastReportedScale) > 0.001f || lastReportedAtBaseScale != atBaseScale

    if (!shouldReport) {
      return
    }

    lastReportedScale = zoomScale
    lastReportedAtBaseScale = atBaseScale
    onZoomStateChanged(zoomScale, atBaseScale)
  }

  private fun handleDismissGesture(event: MotionEvent): Boolean {
    if (!dismissEnabled || !isAtBaseScale() || scaleDetector.isInProgress) {
      if (event.actionMasked == MotionEvent.ACTION_CANCEL && dismissTracking) {
        cancelDismissGesture()
        return true
      }
      return false
    }

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastTouchDownX = event.x
        lastTouchDownY = event.y
        dismissTracking = false
        dismissOffsetY = 0f
      }

      MotionEvent.ACTION_POINTER_DOWN -> {
        if (dismissTracking) {
          cancelDismissGesture()
          return true
        }
      }

      MotionEvent.ACTION_MOVE -> {
        if (event.pointerCount > 1) {
          if (dismissTracking) {
            cancelDismissGesture()
            return true
          }
          return false
        }

        val dx = event.x - lastTouchDownX
        val dy = event.y - lastTouchDownY

        if (!dismissTracking) {
          val started = dy > touchSlopPx && abs(dy) > abs(dx)
          if (!started) {
            return false
          }
          dismissTracking = true
        }

        dismissOffsetY = max(0f, dy)
        val maxScaleDistance = height.toFloat() * 0.5f
        val scaleReduction = if (maxScaleDistance > 0f) {
          (dismissOffsetY / maxScaleDistance).coerceIn(0f, dismissDragRatio)
        } else {
          0f
        }
        val opacity = 1f - (scaleReduction / dismissDragRatio)

        parent?.requestDisallowInterceptTouchEvent(true)
        onDismissDragUpdate(dismissOffsetY / density, opacity)
        return true
      }

      MotionEvent.ACTION_UP -> {
        if (!dismissTracking) {
          return false
        }

        val shouldPop = dismissOffsetY > dismissThresholdPx
        clearDismissState()
        parent?.requestDisallowInterceptTouchEvent(false)
        onDismissDragEnd(shouldPop)
        return true
      }

      MotionEvent.ACTION_CANCEL -> {
        if (!dismissTracking) {
          return false
        }

        cancelDismissGesture()
        parent?.requestDisallowInterceptTouchEvent(false)
        return true
      }
    }

    return false
  }

  private fun cancelDismissGesture(sendCancelEvent: Boolean = true) {
    if (!dismissTracking) {
      return
    }

    clearDismissState()
    if (sendCancelEvent) {
      onDismissDragCancel()
    }
  }

  private fun clearDismissState() {
    dismissTracking = false
    dismissOffsetY = 0f
  }
}

object WindowHdrColorModeCoordinator {
  private const val HDR_HEADROOM_RATIO = 4.0f

  private data class ActivityState(
    var previousColorMode: Int? = null,
    var previousDesiredHdrHeadroom: Float? = null,
    val activeHdrViewIds: MutableSet<Int> = mutableSetOf(),
  )

  private val stateByActivity = WeakHashMap<Activity, ActivityState>()

  @Synchronized
  fun setViewHdrState(activity: Activity, viewId: Int, shouldEnableHdr: Boolean): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return false
    }

    val window = activity.window
    val state = stateByActivity.getOrPut(activity) { ActivityState() }

    if (shouldEnableHdr) {
      state.activeHdrViewIds.add(viewId)
    } else {
      state.activeHdrViewIds.remove(viewId)
    }

    if (state.activeHdrViewIds.isNotEmpty()) {
      if (state.previousColorMode == null) {
        state.previousColorMode = window.colorMode
      }
      window.colorMode = ActivityInfo.COLOR_MODE_HDR

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        if (state.previousDesiredHdrHeadroom == null) {
          state.previousDesiredHdrHeadroom = window.desiredHdrHeadroom
        }
        window.setDesiredHdrHeadroom(HDR_HEADROOM_RATIO)
      }
    } else {
      state.previousColorMode?.let { previous ->
        window.colorMode = previous
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        state.previousDesiredHdrHeadroom?.let { previousHeadroom ->
          window.setDesiredHdrHeadroom(previousHeadroom)
        }
      }

      state.previousColorMode = null
      state.previousDesiredHdrHeadroom = null
      stateByActivity.remove(activity)
    }

    return window.colorMode == ActivityInfo.COLOR_MODE_HDR
  }

  @Synchronized
  fun clearView(activity: Activity, viewId: Int): Boolean {
    return setViewHdrState(activity, viewId, shouldEnableHdr = false)
  }
}

class UltraHdrImageViewFactory(
  private val activity: Activity,
  private val messenger: BinaryMessenger,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
  override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
    ImageFetcherManager.initialize(context.applicationContext)
    return UltraHdrImageView(activity, context, parseRequest(args), viewId, messenger)
  }
}

private class UltraHdrImageView(
  private val activity: Activity,
  context: Context,
  private val request: UltraHdrRequest,
  private val viewId: Int,
  messenger: BinaryMessenger,
) : PlatformView {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val cancellationSignal = CancellationSignal()
  private val channel = MethodChannel(messenger, "immich/ultra_hdr_image/$viewId")

  private val imageView = ZoomableImageView(
    context = context,
    request = request,
    onSingleTap = {
      emit("onSingleTap")
    },
    onZoomStateChanged = { scale, isAtBaseScale ->
      emit(
        "onZoomStateChanged",
        mapOf(
          "scale" to scale.toDouble(),
          "isAtBaseScale" to isAtBaseScale,
        ),
      )
    },
    onDismissDragUpdate = { dy, opacity ->
      emit(
        "onDismissDragUpdate",
        mapOf(
          "dy" to dy.toDouble(),
          "opacity" to opacity.toDouble(),
        ),
      )
    },
    onDismissDragEnd = { shouldPop ->
      emit(
        "onDismissDragEnd",
        mapOf("shouldPop" to shouldPop),
      )
    },
    onDismissDragCancel = {
      emit("onDismissDragCancel")
    },
  )

  @Volatile
  private var disposed = false
  private var decodedHasGainMap = false
  private var decodedForHdrQuality = false
  private var decodingForHdrQuality = false
  private var hdrRequested = request.enableHdr

  init {
    imageView.setGestureEnabled(request.enableGesture)
    imageView.setDismissEnabled(request.enableGesture)
    setUpChannelHandler()
    loadImage(preferHdrQuality = request.enableHdr)
  }

  override fun getView(): View = imageView

  override fun dispose() {
    disposed = true
    channel.setMethodCallHandler(null)
    cancellationSignal.cancel()
    executor.shutdownNow()
    WindowHdrColorModeCoordinator.clearView(activity, viewId)
    imageView.setImageDrawable(null)
  }

  private fun setUpChannelHandler() {
    channel.setMethodCallHandler { call, result ->
      when (call.method) {
        "setGestureEnabled" -> {
          imageView.setGestureEnabled(readBooleanArgument(call, defaultValue = true))
          result.success(null)
        }

        "setDismissEnabled" -> {
          imageView.setDismissEnabled(readBooleanArgument(call, defaultValue = true))
          result.success(null)
        }

        "setHdrEnabled" -> {
          hdrRequested = readBooleanArgument(call, defaultValue = hdrRequested)
          if (hdrRequested && !decodedForHdrQuality && !decodingForHdrQuality) {
            loadImage(preferHdrQuality = true)
          } else {
            applyHdrState()
          }
          result.success(null)
        }

        "resetTransform" -> {
          imageView.resetTransform()
          result.success(null)
        }

        else -> result.notImplemented()
      }
    }
  }

  private fun readBooleanArgument(call: MethodCall, defaultValue: Boolean): Boolean {
    return when (val argument = call.arguments) {
      is Boolean -> argument
      is Map<*, *> -> argument["enabled"] as? Boolean ?: defaultValue
      else -> defaultValue
    }
  }

  private fun emit(method: String, arguments: Any? = null) {
    if (disposed) {
      return
    }

    mainHandler.post {
      if (disposed) {
        return@post
      }
      channel.invokeMethod(method, arguments)
    }
  }

  private fun loadImage(preferHdrQuality: Boolean) {
    if (preferHdrQuality) {
      decodingForHdrQuality = true
    }

    executor.execute {
      val decoded = runCatching {
        decodeImage(appContext, request, cancellationSignal, preferHdrQuality)
      }.getOrNull()

      mainHandler.post {
        if (preferHdrQuality) {
          decodingForHdrQuality = false
        }

        if (disposed) {
          decoded?.bitmap?.let { runCatching { it.recycle() } }
          return@post
        }

        if (decoded == null) {
          applyHdrState()
          return@post
        }

        imageView.setBitmap(decoded.bitmap)
        decodedHasGainMap = decoded.hasGainMap
        decodedForHdrQuality = preferHdrQuality
        applyHdrState()
      }
    }
  }

  private fun applyHdrState() {
    val enableHdr = hdrRequested && decodedHasGainMap
    val hdrApplied = WindowHdrColorModeCoordinator.setViewHdrState(activity, viewId, enableHdr)

    val sourceType = when {
      request.localId != null -> "local"
      request.remoteUrl != null -> "remote"
      else -> "unknown"
    }

    emit(
      "onHdrStateChanged",
      mapOf(
        "hdrRequested" to hdrRequested,
        "hasGainMap" to decodedHasGainMap,
        "colorModeHdrApplied" to hdrApplied,
        "sdkInt" to Build.VERSION.SDK_INT,
        "sourceType" to sourceType,
      ),
    )
  }
}
