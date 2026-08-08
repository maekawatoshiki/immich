package app.alextran.immich.images

import android.app.Activity
import android.content.Intent
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class UltraHdrViewerPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
  private var channel: MethodChannel? = null
  private var activity: Activity? = null

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(binding.binaryMessenger, UltraHdrViewerContract.CHANNEL).also {
      it.setMethodCallHandler(this)
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel?.setMethodCallHandler(null)
    channel = null
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
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
