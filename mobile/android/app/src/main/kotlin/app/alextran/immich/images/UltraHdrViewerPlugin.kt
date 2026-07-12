package app.alextran.immich.images

import android.app.Activity
import android.content.Intent
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

class UltraHdrViewerPlugin :
  FlutterPlugin,
  ActivityAware,
  MethodChannel.MethodCallHandler,
  PluginRegistry.ActivityResultListener {
  private var methodChannel: MethodChannel? = null
  private var activityBinding: ActivityPluginBinding? = null
  private var pendingResult: MethodChannel.Result? = null

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel = MethodChannel(binding.binaryMessenger, UltraHdrViewerContract.CHANNEL)
    methodChannel?.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel?.setMethodCallHandler(null)
    methodChannel = null
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      UltraHdrViewerContract.METHOD_OPEN -> openViewer(call.arguments, result)
      else -> result.notImplemented()
    }
  }

  private fun openViewer(arguments: Any?, result: MethodChannel.Result) {
    val activity = activityBinding?.activity
    if (activity == null) {
      result.error("NO_ACTIVITY", "No foreground activity available", null)
      return
    }

    if (pendingResult != null) {
      result.error("BUSY", "Ultra HDR viewer is already open", null)
      return
    }

    val request = parseRequest(arguments)
    if (request.localId == null && request.remoteUrl == null) {
      result.error("INVALID_ARGS", "Either localId or remoteUrl must be provided", null)
      return
    }

    pendingResult = result
    val intent = Intent(activity, UltraHdrViewerActivity::class.java).apply {
      UltraHdrViewerContract.writeRequest(this, request)
      addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    activity.startActivityForResult(intent, UltraHdrViewerContract.REQUEST_CODE)
    activity.overridePendingTransition(0, 0)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activityBinding = binding
    binding.addActivityResultListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    detachFromActivity()
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  override fun onDetachedFromActivity() {
    detachFromActivity()
  }

  private fun detachFromActivity() {
    activityBinding?.removeActivityResultListener(this)
    activityBinding = null

    pendingResult?.error("CANCELLED", "Ultra HDR viewer was cancelled", null)
    pendingResult = null
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode != UltraHdrViewerContract.REQUEST_CODE) {
      return false
    }

    val result = pendingResult ?: return false
    pendingResult = null

    val errorCode = data?.getStringExtra(UltraHdrViewerContract.EXTRA_ERROR_CODE)
    if (errorCode != null) {
      result.error(errorCode, "Unable to decode image in the native HDR viewer", null)
      return true
    }

    val shouldPopParent =
      if (resultCode == Activity.RESULT_OK) {
        data?.getBooleanExtra(UltraHdrViewerContract.EXTRA_SHOULD_POP_PARENT, false) ?: false
      } else {
        false
      }

    result.success(
      mapOf(
        UltraHdrViewerContract.EXTRA_SHOULD_POP_PARENT to shouldPopParent,
      ),
    )

    return true
  }
}
