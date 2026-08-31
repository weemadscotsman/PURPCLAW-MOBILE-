package com.example.core.runtime

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * DeviceControlEngine — real hardware control hands for the phone.
 *
 * WORK mode grants full authority: flashlight, app launch, vibration, settings
 * panels, media volume, screen rotation lock. No safety gates — the operator's
 * word is the permission.
 */
class DeviceControlEngine(private val context: Context) {

  companion object {
    private const val TAG = "DeviceControlEngine"
  }

  private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
  private var torchCameraId: String? = null

  init {
    torchCameraId = try {
      cameraManager?.cameraIdList?.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
          .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
      }
    } catch (e: Exception) {
      Log.w(TAG, "torch probe failed: ${e.message}")
      null
    }
  }

  private var torchOn = false

  fun setTorch(on: Boolean): String {
    return if (on) enableTorch() else disableTorch()
  }

  fun toggleTorch(): String = setTorch(!torchOn)

  private fun enableTorch(): String {
    val id = torchCameraId
        ?: return "ERROR: no flash unit found on this device"
    return try {
      cameraManager?.setTorchMode(id, true)
      torchOn = true
      "FLASHLIGHT ON (camera $id torch engaged)"
    } catch (e: Exception) {
      "ERROR enabling torch: ${e.message}"
    }
  }

  private fun disableTorch(): String {
    val id = torchCameraId ?: return "no flash unit"
    return try {
      cameraManager?.setTorchMode(id, false)
      torchOn = false
      "flashlight off"
    } catch (e: Exception) {
      "ERROR disabling torch: ${e.message}"
    }
  }

  /** Launch an app by fuzzy name match over installed launchable activities. */
  fun openApp(query: String): String {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = pm.queryIntentActivities(intent, 0)

    val q = query.trim().lowercase()
    // exact label match first, then contains
    val match = activities.firstOrNull {
      it.loadLabel(pm).toString().lowercase() == q
    } ?: activities.firstOrNull {
      it.loadLabel(pm).toString().lowercase().contains(q) ||
        it.activityInfo.packageName.lowercase().contains(q)
    }

    return if (match != null) {
      val label = match.loadLabel(pm).toString()
      val pkg = match.activityInfo.packageName
      val launch = pm.getLaunchIntentForPackage(pkg)
      launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(launch)
      "OPENED APP: $label ($pkg)"
    } else {
      val installed = activities.map { it.loadLabel(pm).toString() }.distinct().sorted().take(25)
      "No app matching '$query'. Installed apps include:\n" + installed.joinToString("\n") { "· $it" }
    }
  }

  /** Live installed launchable-app inventory — never a static registry list. */
  fun listApps(): String {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(launcher, 0)
      .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
      .distinctBy { it.second }
      .sortedBy { it.first.lowercase() }
    return JSONObject().apply {
      put("count", apps.size)
      put("apps", JSONArray().apply {
        apps.forEach { (label, pkg) ->
          put(JSONObject().put("label", label).put("package", pkg))
        }
      })
    }.toString(2)
  }

  /** Live browser inventory resolved from Android's current VIEW handlers. */
  fun listBrowsers(): List<Pair<String, String>> {
    val pm = context.packageManager
    // CATEGORY_APP_BROWSER excludes deep-link owners such as YouTube that
    // can VIEW https URLs but are not browsers.
    val probe = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
    return pm.queryIntentActivities(probe, 0)
      .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
      .distinctBy { it.second }
      .sortedBy { it.first.lowercase() }
  }

  /**
   * Open a URL/search in a real installed browser. An explicit browser name
   * is a hard constraint; we never let Android silently redirect YouTube to
   * the native app when the operator said "in Chrome/the browser".
   */
  fun openBrowser(rawTarget: String, browserQuery: String? = null): String {
    val target = rawTarget.trim().ifBlank { "https://www.google.com" }
    val url = when {
      target.startsWith("http://", true) || target.startsWith("https://", true) -> target
      Regex("^[a-z0-9.-]+\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE).matches(target) -> "https://$target"
      else -> "https://www.google.com/search?q=" + URLEncoder.encode(target, "UTF-8")
    }
    val browsers = listBrowsers()
    val explicit = browserQuery?.trim()?.takeIf { it.isNotBlank() }
    val explicitSelection = explicit?.let { q ->
      browsers.firstOrNull { (label, pkg) ->
        label.contains(q, true) || pkg.contains(q, true)
      }
    }
    if (explicit != null && explicitSelection == null) {
      return JSONObject().apply {
        put("ok", false)
        put("error", "BROWSER_NOT_INSTALLED")
        put("requested_browser", explicit)
        put("installed_browsers", JSONArray(browsers.map { it.first }))
      }.toString(2)
    }
    val defaultPackage = context.packageManager.resolveActivity(
      Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
      android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName
    val selected = explicitSelection
      ?: browsers.firstOrNull { it.second == defaultPackage }
      ?: browsers.firstOrNull()
    if (selected == null) {
      return JSONObject().put("ok", false).put("error", "NO_INSTALLED_BROWSER").toString(2)
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
      .addCategory(Intent.CATEGORY_BROWSABLE)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.setPackage(selected.second)
    return try {
      context.startActivity(intent)
      JSONObject().apply {
        put("ok", true)
        put("dispatch", "ACTIVITY_START_ACCEPTED")
        put("url", url)
        put("browser", selected.first)
        put("package", selected.second)
      }.toString(2)
    } catch (e: Exception) {
      JSONObject().put("ok", false).put("error", e.message ?: "NO_BROWSER_HANDLER").toString(2)
    }
  }

  /** Open a system settings panel (wifi, bluetooth, etc.). Toggles need user tap on most Androids — the panel IS the control. */
  fun openSettingsPanel(which: String): String {
    val actions = mapOf(
      "wifi" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
      "internet" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
      "bluetooth" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
      "bt" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
      "nfc" to Settings.Panel.ACTION_NFC,
      "volume" to Settings.Panel.ACTION_VOLUME,
      "rotate" to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
      "display" to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
      "battery" to Intent.ACTION_BATTERY_CHANGED.let { android.provider.Settings.ACTION_SETTINGS },
      "all" to android.provider.Settings.ACTION_SETTINGS,
      "main" to android.provider.Settings.ACTION_SETTINGS
    )
    val action = actions[which.lowercase().trim()]
        ?: return "Unknown settings panel '$which'. Available: ${actions.keys.joinToString(", ")}"
    return try {
      val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(i)
      "OPENED SETTINGS PANEL: $which"
    } catch (e: Exception) {
      "ERROR opening $which settings: ${e.message}"
    }
  }

  fun vibrate(patternMs: List<Long> = listOf(0, 200)): String {
    return try {
      val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
        vm?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
      if (patternMs.size == 1) {
        vibrator?.vibrate(VibrationEffect.createOneShot(patternMs[0], VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        vibrator?.vibrate(VibrationEffect.createWaveform(patternMs.toLongArray(), -1))
      }
      "VIBRATED pattern=$patternMs"
    } catch (e: Exception) {
      "ERROR vibrating: ${e.message}"
    }
  }

  fun screenshotHint(): String =
    "Screen capture requires the media-projection consent dialog (Android security model, not PurpClaw policy). Use android.camera.capture for optical evidence instead."
}
