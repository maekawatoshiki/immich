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
import android.provider.MediaStore.Images
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.annotation.RequiresApi
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import java.nio.ByteBuffer
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

    // Use the same HDR color space for full-screen and inline rendering.
    runCatching {
      decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
    }
    if (preferHdrQuality) {
      return@decodeBitmap
    }

    if (target.width > 0 && target.height > 0) {
      // Keep the gain map, but only decode the pixels needed by this viewport.
      val ratio = min(1.0, min(target.width.toDouble() / info.size.width, target.height.toDouble() / info.size.height))
      decoder.setTargetSize(max(1, (info.size.width * ratio).toInt()), max(1, (info.size.height * ratio).toInt()))
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

class ZoomableImageView(context: Context) : ImageView(context) {
  private var imageWidthPx = 0f
  private var imageHeightPx = 0f
  private var zoomScale = MIN_SCALE
  private var panX = 0f
  private var panY = 0f

  private val scaleDetector = ScaleGestureDetector(
    context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScale(detector: ScaleGestureDetector): Boolean {
        zoomAt(detector.focusX, detector.focusY, zoomScale * detector.scaleFactor)
        return true
      }
    },
  )

  private val gestureDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(event: MotionEvent): Boolean = true

      override fun onScroll(
        firstEvent: MotionEvent?,
        currentEvent: MotionEvent,
        distanceX: Float,
        distanceY: Float,
      ): Boolean {
        if (zoomScale <= MIN_SCALE + SCALE_EPSILON) {
          return false
        }
        panX -= distanceX
        panY -= distanceY
        applyMatrix()
        return true
      }

      override fun onDoubleTap(event: MotionEvent): Boolean {
        val target = if (zoomScale <= MIN_SCALE + SCALE_EPSILON) DOUBLE_TAP_SCALE else MIN_SCALE
        zoomAt(event.x, event.y, target)
        return true
      }
    },
  )

  init {
    scaleType = ScaleType.FIT_CENTER
    isClickable = true
    setOnTouchListener { _, event ->
      scaleDetector.onTouchEvent(event)
      gestureDetector.onTouchEvent(event)
      true
    }
  }

  override fun performClick(): Boolean = super.performClick()

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

  private fun resetTransform() {
    zoomScale = MIN_SCALE
    panX = 0f
    panY = 0f
    applyMatrix()
  }

  private fun zoomAt(focusX: Float, focusY: Float, requestedScale: Float) {
    if (width <= 0 || height <= 0 || imageWidthPx <= 0f || imageHeightPx <= 0f) {
      zoomScale = requestedScale.coerceIn(MIN_SCALE, MAX_SCALE)
      return
    }

    val viewWidth = width.toFloat()
    val viewHeight = height.toFloat()
    val fitScale = min(viewWidth / imageWidthPx, viewHeight / imageHeightPx)
    val oldAbsoluteScale = fitScale * zoomScale
    val newScale = requestedScale.coerceIn(MIN_SCALE, MAX_SCALE)
    val newAbsoluteScale = fitScale * newScale
    if (abs(newScale - zoomScale) < SCALE_EPSILON) {
      return
    }

    val oldLeft = (viewWidth - imageWidthPx * oldAbsoluteScale) / 2f + panX
    val oldTop = (viewHeight - imageHeightPx * oldAbsoluteScale) / 2f + panY
    val imageX = (focusX - oldLeft) / oldAbsoluteScale
    val imageY = (focusY - oldTop) / oldAbsoluteScale
    val centeredLeft = (viewWidth - imageWidthPx * newAbsoluteScale) / 2f
    val centeredTop = (viewHeight - imageHeightPx * newAbsoluteScale) / 2f

    panX = focusX - centeredLeft - imageX * newAbsoluteScale
    panY = focusY - centeredTop - imageY * newAbsoluteScale
    zoomScale = newScale
    applyMatrix()
  }

  private fun applyMatrix() {
    if (width <= 0 || height <= 0 || imageWidthPx <= 0f || imageHeightPx <= 0f) {
      return
    }

    if (zoomScale <= MIN_SCALE + SCALE_EPSILON) {
      panX = 0f
      panY = 0f
      scaleType = ScaleType.FIT_CENTER
      imageMatrix = Matrix()
      return
    }

    scaleType = ScaleType.MATRIX
    val viewWidth = width.toFloat()
    val viewHeight = height.toFloat()
    val fitScale = min(viewWidth / imageWidthPx, viewHeight / imageHeightPx)
    val absoluteScale = fitScale * zoomScale
    val displayWidth = imageWidthPx * absoluteScale
    val displayHeight = imageHeightPx * absoluteScale
    val maxPanX = max(0f, (displayWidth - viewWidth) / 2f)
    val maxPanY = max(0f, (displayHeight - viewHeight) / 2f)
    panX = panX.coerceIn(-maxPanX, maxPanX)
    panY = panY.coerceIn(-maxPanY, maxPanY)

    val left = (viewWidth - displayWidth) / 2f + panX
    val top = (viewHeight - displayHeight) / 2f + panY
    imageMatrix = Matrix().apply {
      postScale(absoluteScale, absoluteScale)
      postTranslate(left, top)
    }
  }

  private companion object {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 6f
    const val DOUBLE_TAP_SCALE = 2f
    const val SCALE_EPSILON = 0.001f
  }
}

object WindowHdrColorModeCoordinator {

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
        // Zero lets Android choose the headroom supported by the display and
        // the current gain map instead of limiting every image to one ratio.
        window.setDesiredHdrHeadroom(0.0f)
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
