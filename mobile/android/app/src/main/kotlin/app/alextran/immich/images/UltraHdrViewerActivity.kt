package app.alextran.immich.images

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class UltraHdrViewerActivity : Activity() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val cancellationSignal = CancellationSignal()

  private lateinit var request: UltraHdrRequest
  private lateinit var rootView: FrameLayout
  private lateinit var imageView: ZoomableImageView
  private var progressBar: ProgressBar? = null
  private var bitmap: Bitmap? = null

  @Volatile
  private var destroyed = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    ImageFetcherManager.initialize(applicationContext)
    request = UltraHdrViewerContract.readRequest(intent)
    if (request.localId == null && request.remoteUrl == null) {
      finishWithoutAnimation()
      return
    }

    rootView = FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
    }
    imageView = ZoomableImageView(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      )
    }
    rootView.addView(imageView)

    if (request.localId == null) {
      progressBar = ProgressBar(this).apply {
        layoutParams = FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
          Gravity.CENTER,
        )
      }
      rootView.addView(progressBar)
    }

    setContentView(rootView)
    loadImage()
  }

  override fun onDestroy() {
    destroyed = true
    cancellationSignal.cancel()
    executor.shutdownNow()
    WindowHdrColorModeCoordinator.clearView(this, HDR_VIEW_ID)
    if (::imageView.isInitialized) {
      imageView.setImageDrawable(null)
    }
    recycle(bitmap)
    bitmap = null
    super.onDestroy()
  }

  private fun loadImage() {
    executor.execute {
      val decodeResult = runCatching {
        decodeImage(applicationContext, request, cancellationSignal, preferHdrQuality = true)
      }

      mainHandler.post {
        val decoded = decodeResult.getOrNull()
        if (destroyed) {
          recycle(decoded?.bitmap)
          return@post
        }

        if (decoded == null) {
          Log.e(TAG, "[UltraHDRViewer] Failed to decode image", decodeResult.exceptionOrNull())
          finishWithoutAnimation()
          return@post
        }

        progressBar?.let {
          rootView.removeView(it)
          progressBar = null
        }
        recycle(bitmap)
        bitmap = decoded.bitmap
        imageView.setBitmap(decoded.bitmap)
        val hdrApplied = WindowHdrColorModeCoordinator.setViewHdrState(
          this,
          HDR_VIEW_ID,
          shouldEnableHdr = decoded.hasGainMap,
        )
        Log.i(
          TAG,
          "[UltraHDRViewer] hasGainMap=${decoded.hasGainMap} applied=$hdrApplied sdk=${Build.VERSION.SDK_INT} source=${sourceType()}",
        )
      }
    }
  }

  private fun sourceType(): String = when {
    request.localId != null -> "local"
    request.remoteUrl != null -> "remote"
    else -> "unknown"
  }

  private fun finishWithoutAnimation() {
    finish()
  }

  private fun recycle(value: Bitmap?) {
    value?.let { runCatching { it.recycle() } }
  }

  private companion object {
    const val TAG = "UltraHdrViewer"
    const val HDR_VIEW_ID = -1
  }
}
