package app.alextran.immich.images

import android.content.Intent
import android.os.Build
import java.util.HashMap

internal object UltraHdrViewerContract {
  const val CHANNEL = "immich/ultra_hdr_viewer"
  const val METHOD_OPEN = "open"
  const val REQUEST_CODE = 9726

  const val EXTRA_LOCAL_ID = "localId"
  const val EXTRA_REMOTE_URL = "remoteUrl"
  const val EXTRA_HEADERS = "headers"
  const val EXTRA_WIDTH = "width"
  const val EXTRA_HEIGHT = "height"
  const val EXTRA_ENABLE_HDR = "enableHdr"
  const val EXTRA_ENABLE_GESTURE = "enableGesture"
  const val EXTRA_MIN_SCALE = "minScale"
  const val EXTRA_MAX_SCALE = "maxScale"
  const val EXTRA_DOUBLE_TAP_SCALE = "doubleTapScale"

  const val EXTRA_SHOULD_POP_PARENT = "shouldPopParent"
  const val EXTRA_ERROR_CODE = "errorCode"

  const val ERROR_DECODE_FAILED = "DECODE_FAILED"

  fun writeRequest(intent: Intent, request: UltraHdrRequest) {
    intent.putExtra(EXTRA_LOCAL_ID, request.localId)
    intent.putExtra(EXTRA_REMOTE_URL, request.remoteUrl)
    intent.putExtra(EXTRA_HEADERS, HashMap(request.headers))
    intent.putExtra(EXTRA_WIDTH, request.width)
    intent.putExtra(EXTRA_HEIGHT, request.height)
    intent.putExtra(EXTRA_ENABLE_HDR, request.enableHdr)
    intent.putExtra(EXTRA_ENABLE_GESTURE, request.enableGesture)
    intent.putExtra(EXTRA_MIN_SCALE, request.minScale)
    intent.putExtra(EXTRA_MAX_SCALE, request.maxScale)
    intent.putExtra(EXTRA_DOUBLE_TAP_SCALE, request.doubleTapScale)
  }

  fun readRequest(intent: Intent): UltraHdrRequest {
    val headers = readHeaders(intent)
    return UltraHdrRequest(
      localId = intent.getStringExtra(EXTRA_LOCAL_ID),
      remoteUrl = intent.getStringExtra(EXTRA_REMOTE_URL),
      headers = headers,
      width = intent.getIntExtra(EXTRA_WIDTH, 0),
      height = intent.getIntExtra(EXTRA_HEIGHT, 0),
      enableHdr = intent.getBooleanExtra(EXTRA_ENABLE_HDR, true),
      enableGesture = intent.getBooleanExtra(EXTRA_ENABLE_GESTURE, true),
      minScale = intent.getFloatExtra(EXTRA_MIN_SCALE, 1.0f),
      maxScale = intent.getFloatExtra(EXTRA_MAX_SCALE, 6.0f),
      doubleTapScale = intent.getFloatExtra(EXTRA_DOUBLE_TAP_SCALE, 2.0f),
    )
  }

  private fun readHeaders(intent: Intent): Map<String, String> {
    val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getSerializableExtra(EXTRA_HEADERS, HashMap::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getSerializableExtra(EXTRA_HEADERS)
    }

    val map = raw as? Map<*, *> ?: emptyMap<String, String>()
    return map.entries.mapNotNull { (key, value) ->
      if (key is String && value is String) {
        key to value
      } else {
        null
      }
    }.toMap()
  }
}
