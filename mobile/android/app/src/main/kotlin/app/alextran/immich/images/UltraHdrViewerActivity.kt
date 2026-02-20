package app.alextran.immich.images

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class UltraHdrViewerActivity : Activity() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val cancellationSignal = CancellationSignal()

  private lateinit var request: UltraHdrRequest
  private lateinit var rootView: FrameLayout
  private lateinit var imageView: ZoomableImageView

  @Volatile
  private var destroyed = false

  private var decodedHasGainMap = false
  private var decodedForHdrQuality = false
  private var decodingForHdrQuality = false
  private var hdrRequested = true

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    ImageFetcherManager.initialize(applicationContext)
    request = UltraHdrViewerContract.readRequest(intent)
    if (request.localId == null && request.remoteUrl == null) {
      finishViewer(shouldPopParent = false)
      return
    }

    hdrRequested = request.enableHdr

    rootView = FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      )
    }

    imageView = ZoomableImageView(
      context = this,
      request = request,
      onSingleTap = {},
      onZoomStateChanged = { _, _ -> },
      onDismissDragUpdate = { dyDp, opacity ->
        updateDismissState(dyDp, opacity)
      },
      onDismissDragEnd = { shouldPop ->
        if (shouldPop) {
          finishViewer(shouldPopParent = false)
        } else {
          resetDismissState()
        }
      },
      onDismissDragCancel = {
        resetDismissState()
      },
    ).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      )
    }

    rootView.addView(imageView)
    setContentView(rootView)

    loadImage(preferHdrQuality = true)
  }

  @Deprecated("Deprecated in Android 13")
  override fun onBackPressed() {
    finishViewer(shouldPopParent = false)
  }

  override fun onDestroy() {
    destroyed = true
    cancellationSignal.cancel()
    executor.shutdownNow()
    WindowHdrColorModeCoordinator.clearView(this, HDR_VIEW_ID)
    if (::imageView.isInitialized) {
      imageView.setImageDrawable(null)
    }
    super.onDestroy()
  }

  private fun loadImage(preferHdrQuality: Boolean) {
    if (preferHdrQuality) {
      decodingForHdrQuality = true
    }

    executor.execute {
      val decoded = runCatching {
        decodeImage(applicationContext, request, cancellationSignal, preferHdrQuality)
      }.getOrNull()

      mainHandler.post {
        if (preferHdrQuality) {
          decodingForHdrQuality = false
        }

        if (destroyed) {
          decoded?.bitmap?.let { bitmap ->
            runCatching { bitmap.recycle() }
          }
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
    if (destroyed) {
      return
    }

    if (hdrRequested && !decodedForHdrQuality && !decodingForHdrQuality) {
      loadImage(preferHdrQuality = true)
      return
    }

    val enableHdr = hdrRequested && decodedHasGainMap
    val hdrApplied = WindowHdrColorModeCoordinator.setViewHdrState(this, HDR_VIEW_ID, enableHdr)

    val sourceType = when {
      request.localId != null -> "local"
      request.remoteUrl != null -> "remote"
      else -> "unknown"
    }

    Log.i(
      TAG,
      "[UltraHDRViewer] requested=$hdrRequested hasGainMap=$decodedHasGainMap applied=$hdrApplied sdk=${Build.VERSION.SDK_INT} source=$sourceType",
    )
  }

  private fun updateDismissState(dyDp: Float, opacity: Float) {
    if (!::imageView.isInitialized || !::rootView.isInitialized) {
      return
    }

    val clampedOpacity = opacity.coerceIn(0f, 1f)
    val dyPx = dyDp * resources.displayMetrics.density
    val scale = 0.8f + (0.2f * clampedOpacity)

    imageView.translationY = dyPx
    imageView.scaleX = scale
    imageView.scaleY = scale

    val alpha = (255f * clampedOpacity).roundToInt().coerceIn(0, 255)
    rootView.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
  }

  private fun resetDismissState() {
    if (!::imageView.isInitialized || !::rootView.isInitialized) {
      return
    }

    rootView.setBackgroundColor(Color.BLACK)
    imageView.animate()
      .translationY(0f)
      .scaleX(1f)
      .scaleY(1f)
      .setDuration(160)
      .start()
  }

  private fun finishViewer(shouldPopParent: Boolean) {
    if (destroyed) {
      return
    }

    setResult(
      RESULT_OK,
      Intent().putExtra(UltraHdrViewerContract.EXTRA_SHOULD_POP_PARENT, shouldPopParent),
    )
    finish()
    overridePendingTransition(0, 0)
  }

  companion object {
    private const val TAG = "UltraHdrViewer"
    private const val HDR_VIEW_ID = -1
  }
}
