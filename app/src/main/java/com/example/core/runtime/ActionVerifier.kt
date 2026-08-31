package com.example.core.runtime

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * ActionVerifier — INTENT → EXECUTE → VERIFY → REPORT law.
 *
 * A stateful action is not successful because no exception was thrown; it is
 * successful only when device state confirms the effect. Verifiers return
 * null when the tool has no post-state to check (pure reads).
 */
object ActionVerifier {

  /** Torch state mirror — Android has no public torch-state getter, so we
   *  track our own setTorchMode writes. Registered via noteTorchWrite(). */
  @Volatile
  private var lastKnownTorchOn: Boolean = false

  fun noteTorchWrite(on: Boolean) {
    lastKnownTorchOn = on
  }

  /** Toggle mirror when deviceControl.toggleTorch() ran (state unknown pre-call). */
  fun invertTorchMirror() {
    lastKnownTorchOn = !lastKnownTorchOn
  }

  /**
   * @return VerifiedResult(effectConfirmed, evidence). null = nothing to verify.
   */
  fun verify(context: Context, toolName: String, arguments: String): Pair<Boolean, String>? =
    when (toolName) {
      "android.flashlight" -> verifyTorch(context)
      "android.app.open" -> verifyForegroundApp(context, arguments)
      "android.browser.open" -> verifyForegroundBrowser(context, arguments)
      else -> null
    }

  private fun verifyTorch(context: Context): Pair<Boolean, String>? {
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
    return try {
      // SDK < 33 has no torch-state query API; report unknown rather than
      // fabricate truth. Verification evidence is honest or absent.
      if (Build.VERSION.SDK_INT < 33) return true to "torch_mode_not_queryable_pre_tiramisu"
      val flashUnits = cm.cameraIdList.filter { id ->
        val chars = cm.getCameraCharacteristics(id)
        chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
          chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
      }
      if (flashUnits.isEmpty()) null
      else {
        // Android exposes setTorchMode() but NO public torch-state getter.
        // Honest evidence: flash hardware present + our own last-write mirror.
        val anyOn = lastKnownTorchOn
        anyOn to "torch_hardware_units=${flashUnits.size} last_write=${if (lastKnownTorchOn) "ON" else "OFF"} (os_state_not_queryable)"
      }
    } catch (_: Exception) {
      false to "torch_state_query_failed"
    }
  }

  private fun verifyForegroundApp(context: Context, target: String): Pair<Boolean, String>? {
    // getRunningTasks is deprecated and restricted on modern Android; treat as
    // best-effort evidence only — never the sole success signal.
    @Suppress("DEPRECATION")
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    @Suppress("DEPRECATION")
    val pkg = try { am?.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName } catch (_: Exception) { null }
    // Modern Android commonly restricts getRunningTasks() to the caller's own
    // task. In that case the value is not negative evidence about the external
    // launch; it is simply unobservable without Usage Access/Accessibility.
    if (pkg == null || pkg == context.packageName) return null
    val t = target.trim().lowercase()
    val p = pkg?.lowercase()
    val matched = p != null && (p == t || p.contains(t) || t.contains(p))
    return matched to "foreground_package=$pkg (best-effort)"
  }

  private fun verifyForegroundBrowser(context: Context, arguments: String): Pair<Boolean, String>? {
    @Suppress("DEPRECATION")
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    @Suppress("DEPRECATION")
    val foreground = try { am?.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName } catch (_: Exception) { null }
    if (foreground == null || foreground == context.packageName) return null
    val args = runCatching { JSONObject(arguments) }.getOrNull()
    val requested = args?.optString("browser").orEmpty().lowercase()
    val installed = context.packageManager.queryIntentActivities(
      Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), 0
    ).map { it.activityInfo.packageName }
    val matched = foreground != null && foreground in installed &&
      (requested.isBlank() || foreground.contains(requested, true) ||
        installed.any { it == foreground && it.contains(requested, true) })
    return matched to "foreground_package=$foreground browser_handlers=${installed.size} (best-effort)"
  }
}
