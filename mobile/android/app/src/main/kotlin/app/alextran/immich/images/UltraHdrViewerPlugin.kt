package app.alextran.immich.images

import android.app.Activity
import android.content.Intent
import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class UltraHdrViewerPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
  private var channel: MethodChannel? = null
  private var activity: Activity? = null
  private var inlineImages: InlineHdrImageViewFactory? = null

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    inlineImages = InlineHdrImageViewFactory(binding.binaryMessenger) { activity }.also {
      binding.platformViewRegistry.registerViewFactory("immich/inline_hdr_image", it)
    }
    channel = MethodChannel(binding.binaryMessenger, UltraHdrViewerContract.CHANNEL).also {
      it.setMethodCallHandler(this)
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    inlineImages?.close()
    inlineImages = null
    channel?.setMethodCallHandler(null)
    channel = null
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    if (call.method == "supportsInline") {
      result.success(Build.VERSION.SDK_INT >= 34 && activity?.display?.isHdr == true)
      return
    }
    if (call.method != UltraHdrViewerContract.METHOD_OPEN) {
      result.notImplemented()
      return
    }

    val foregroundActivity = activity
    if (foregroundActivity == null) {
      result.error("NO_ACTIVITY", "No foreground activity available", null)
      return
    }

    val request = parseRequest(call.arguments)
    if (request.localId == null && request.remoteUrl == null) {
      result.error("INVALID_ARGS", "Either localId or remoteUrl must be provided", null)
      return
    }

    val intent = Intent(foregroundActivity, UltraHdrViewerActivity::class.java).apply {
      UltraHdrViewerContract.writeRequest(this, request)
      addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
    foregroundActivity.startActivity(intent)
    result.success(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivityForConfigChanges() {
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivity() {
    activity = null
  }
}
