package app.alextran.immich.images

import android.content.Intent
import android.os.Build
import java.util.HashMap

internal object UltraHdrViewerContract {
  const val CHANNEL = "immich/ultra_hdr_viewer"
  const val METHOD_OPEN = "open"

  private const val EXTRA_LOCAL_ID = "localId"
  private const val EXTRA_REMOTE_URL = "remoteUrl"
  private const val EXTRA_HEADERS = "headers"
  private const val EXTRA_WIDTH = "width"
  private const val EXTRA_HEIGHT = "height"

  fun writeRequest(intent: Intent, request: UltraHdrRequest) {
    intent.putExtra(EXTRA_LOCAL_ID, request.localId)
    intent.putExtra(EXTRA_REMOTE_URL, request.remoteUrl)
    intent.putExtra(EXTRA_HEADERS, HashMap(request.headers))
    intent.putExtra(EXTRA_WIDTH, request.width)
    intent.putExtra(EXTRA_HEIGHT, request.height)
  }

  fun readRequest(intent: Intent): UltraHdrRequest {
    return UltraHdrRequest(
      localId = intent.getStringExtra(EXTRA_LOCAL_ID),
      remoteUrl = intent.getStringExtra(EXTRA_REMOTE_URL),
      headers = readHeaders(intent),
      width = intent.getIntExtra(EXTRA_WIDTH, 0),
      height = intent.getIntExtra(EXTRA_HEIGHT, 0),
    )
  }

  private fun readHeaders(intent: Intent): Map<String, String> {
    val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getSerializableExtra(EXTRA_HEADERS, HashMap::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getSerializableExtra(EXTRA_HEADERS)
    }
    val map = raw as? Map<*, *> ?: return emptyMap()
    return map.entries.mapNotNull { (key, value) ->
      if (key is String && value is String) key to value else null
    }.toMap()
  }
}
