package app.alextran.immich.images

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One queue per engine; a new current-page request goes ahead of queued neighbors. */
class InlineHdrImageViewFactory(
  private val messenger: BinaryMessenger,
  private val activity: () -> Activity?,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
  private val queue = LinkedBlockingDeque<Runnable>()
  private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue)
  private val views = mutableSetOf<InlineHdrImageView>()

  override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
    val host = checkNotNull(activity()) { "No foreground activity" }
    return InlineHdrImageView(context, host, viewId, args, messenger, this).also { views.add(it) }
  }

  fun enqueue(task: Runnable, current: Boolean) {
    if (current && executor.poolSize > 0) {
      queue.offerFirst(task)
    } else {
      executor.execute(task)
    }
  }

  fun cancel(task: Runnable?) {
    if (task != null) executor.remove(task)
  }

  fun promote(task: Runnable?) {
    if (task != null && queue.remove(task)) queue.offerFirst(task)
  }

  fun remove(view: InlineHdrImageView) {
    views.remove(view)
  }

  fun close() {
    views.toList().forEach { it.dispose() }
    executor.shutdownNow()
  }
}

/** Flutter owns all gestures and transforms. This view only decodes and draws. */
class InlineHdrImageView(
  context: Context,
  private val activity: Activity,
  private val viewId: Int,
  args: Any?,
  messenger: BinaryMessenger,
  private val owner: InlineHdrImageViewFactory,
) : PlatformView {
  private val main = Handler(Looper.getMainLooper())
  private val channel = MethodChannel(messenger, "immich/inline_hdr_image/$viewId")
  private val image = ImageView(context).apply {
    scaleType = ImageView.ScaleType.FIT_CENTER
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
  }
  private val source = parseRequest(args)
  private var current = (args as? Map<*, *>)?.get("current") == true
  private var signal: CancellationSignal? = null
  private var task: Runnable? = null
  private var generation = 0
  private var disposed = false
  private var bitmap: Bitmap? = null
  private var hasGainMap = false
  private var hdrActive = false
  private var requestedWidth = 0
  private var requestedHeight = 0
  private var readySent = false
  private val visibleRect = Rect()
  private val preDraw = ViewTreeObserver.OnPreDrawListener {
    updateHdrState()
    true
  }

  private fun updateHdrState() {
    // The current image must enter HDR mode before its first bitmap is published.
    // Waiting until pre-draw can leave a display list made with the old headroom.
    val visibleHdr = hasGainMap && (current || (image.isShown && image.getGlobalVisibleRect(visibleRect)))
    if (hdrActive == visibleHdr) return
    hdrActive = visibleHdr
    WindowHdrColorModeCoordinator.setViewHdrState(activity, viewId, visibleHdr)
    image.invalidate()
  }

  init {
    ImageFetcherManager.initialize(context.applicationContext)
    image.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(view: View) {
        image.viewTreeObserver.addOnPreDrawListener(preDraw)
      }

      override fun onViewDetachedFromWindow(view: View) {
        image.viewTreeObserver.removeOnPreDrawListener(preDraw)
        hdrActive = false
        WindowHdrColorModeCoordinator.clearView(activity, viewId)
      }
    })
    channel.setMethodCallHandler { call, result ->
      when (call.method) {
        "load" -> {
          current = call.argument<Boolean>("current") ?: current
          updateHdrState()
          load(call.argument<Int>("width") ?: source.width, call.argument<Int>("height") ?: source.height)
          result.success(null)
        }
        "finishTransition" -> {
          finishTransition()
          result.success(null)
        }
        else -> result.notImplemented()
      }
    }
    // Dart installs its result handler before sending the initial load request.
  }

  private fun finishTransition() {
    if (disposed || !image.isAttachedToWindow) return
    // Flutter 3.47 keeps the opacity layer after the route fade reaches 1.0.
    // Remove it after that frame commits so HDR is not drawn through a cached layer.
    image.viewTreeObserver.registerFrameCommitCallback {
      main.post {
        if (!disposed) {
          val parent = image.parent as? FlutterMutatorView
          if (parent?.layerType == View.LAYER_TYPE_HARDWARE) {
            parent.setLayerType(View.LAYER_TYPE_NONE, null)
            parent.invalidate()
            Log.i(TAG, "cleared opacity layer view=$viewId")
          }
        }
      }
    }
    image.invalidate()
  }

  private fun load(width: Int, height: Int) {
    if (disposed || width <= 0 || height <= 0) return
    if (width == requestedWidth && height == requestedHeight) {
      if (current) owner.promote(task)
      return
    }
    cancelLoad()
    requestedWidth = width
    requestedHeight = height
    val token = generation
    val cancellation = CancellationSignal()
    signal = cancellation
    val request = source.copy(width = width, height = height)
    val work = Runnable {
      var decoded: DecodedImage? = null
      val outcome = runCatching {
        cancellation.throwIfCanceled()
        decoded = decodeImage(image.context.applicationContext, request, cancellation, preferHdrQuality = false)
        cancellation.throwIfCanceled()
      }
      main.post {
        val loaded = decoded
        if (disposed || generation != token || outcome.isFailure) {
          loaded?.bitmap?.recycle() // Never published to RenderThread.
          if (!disposed && generation == token) {
            task = null
            requestedWidth = 0
            requestedHeight = 0
            if (bitmap == null) channel.invokeMethod("error", null)
            Log.w(TAG, "decode failed", outcome.exceptionOrNull())
          }
          return@post
        }
        task = null
        if (loaded == null) {
          requestedWidth = 0
          requestedHeight = 0
          if (bitmap == null) channel.invokeMethod("error", null)
          return@post
        }
        bitmap = loaded.bitmap
        hasGainMap = loaded.hasGainMap
        updateHdrState()
        if (!readySent) {
          // Wait for the native frame to be submitted before removing Flutter's placeholder.
          image.viewTreeObserver.registerFrameCommitCallback {
            main.post {
              if (!disposed && !readySent) {
                readySent = true
                Log.i(TAG, "ready view=$viewId size=${image.width}x${image.height} shown=${image.isShown}")
                channel.invokeMethod("ready", mapOf("hasGainMap" to hasGainMap))
              }
            }
          }
        }
        // Drop the previous reference rather than recycling a bitmap still in a display list.
        image.setImageBitmap(loaded.bitmap)
        Log.i(TAG, "view=$viewId gainMap=$hasGainMap pixels=${loaded.bitmap.width}x${loaded.bitmap.height}")
      }
    }
    task = work
    owner.enqueue(work, current)
  }

  private fun cancelLoad() {
    generation++
    signal?.cancel()
    signal = null
    owner.cancel(task)
    task = null
  }

  override fun getView(): View = image

  override fun dispose() {
    if (disposed) return
    disposed = true
    cancelLoad()
    channel.setMethodCallHandler(null)
    image.viewTreeObserver.removeOnPreDrawListener(preDraw)
    image.setImageDrawable(null)
    bitmap = null
    WindowHdrColorModeCoordinator.clearView(activity, viewId)
    owner.remove(this)
    Log.i(TAG, "disposed view=$viewId")
  }

  private companion object {
    const val TAG = "InlineHDR"
  }
}
