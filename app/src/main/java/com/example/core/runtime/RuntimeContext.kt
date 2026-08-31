package com.example.core.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * RuntimeContext — the ONE truth spine for what this device actually is and
 * what it can actually do. Built from live hardware/permission probes, never
 * from conversation text or provider routing.
 *
 * THREE IDENTITIES THAT MUST NEVER BE COLLAPSED:
 *   SURFACE    = where PurpClaw lives (Android native app)
 *   INFERENCE  = where the BRAIN runs (MiniMax API / OpenRouter free pool / local)
 *   EXECUTION  = where the HANDS run (ANDROID_LOCAL always here)
 *
 * Using MiniMax remotely does NOT mean PurpClaw runs on the Home PC.
 */
data class RuntimeContext(
  val surface: String,              // "android"
  val deviceClass: String = "phone",
  val nativeApp: Boolean = true,
  val deviceModel: String,
  val androidVersion: String,
  val executionMode: String,        // CHAT | WORK
  val canExecute: Boolean,
  val executionAuthority: String,   // ANDROID_LOCAL | HOME_DELEGATED
  val inferenceProvider: String,
  val inferenceModel: String,
  val inferenceLocation: String,    // where reasoning runs — NOT where PurpClaw lives
  val homeConnected: Boolean,
  val homeAuthority: Boolean,
  val sdkAvailable: Boolean,
  val nativeToolBridge: Boolean,
  val capabilities: Map<String, Boolean>,
  val capabilityDetails: Map<String, Capability>,
  val permissions: Map<String, String>
) {
  fun toPromptBlock(): String = buildString {
    appendLine("PURPCLAW LIVE RUNTIME STATE (authoritative — computed from live probes, overrides any assumption)")
    appendLine("Surface: ANDROID NATIVE APPLICATION ($deviceModel, $androidVersion) [class=$deviceClass nativeApp=$nativeApp sdk=$sdkAvailable toolBridge=$nativeToolBridge]")
    appendLine("Inference: provider=$inferenceProvider model=$inferenceModel location=$inferenceLocation (where THINKING happens — not where you live)")
    appendLine("Execution location: ${if (executionAuthority == "HOME_DELEGATED") "HOME PC DELEGATED" else "ANDROID LOCAL — your body is THIS PHONE, always"}")
    appendLine("Execution mode: $executionMode (${if (canExecute) "tool execution ENABLED" else "conversation only"})")
    appendLine("Execution authority: $executionAuthority")
    appendLine("Home PC bridge: ${if (homeConnected) "ONLINE (optional compute node)" else "OFFLINE"}")
    if (!homeConnected) {
      appendLine("NOTE: Home offline changes nothing about your Android hands.")
    }
    appendLine()
    appendLine("Capabilities (live registry):")
    capabilityDetails.forEach { (_, c) ->
      if (c.available) {
        appendLine("  ${c.id} [${c.executor}]${if (c.permissions.isNotEmpty()) " needs:${c.permissions.joinToString(",")}" else ""}")
      } else {
        appendLine("  ${c.id}: UNAVAILABLE (${c.note ?: "unavailable"})")
      }
    }
    appendLine("Permissions:")
    permissions.forEach { (k, v) -> appendLine("  $k: $v") }
    appendLine()
    appendLine("RULES:")
    if (executionMode == "WORK") {
      appendLine("- Device actions (flashlight, apps, camera, settings, notifications) run LOCALLY via your native android.* tools. Execute them directly; do not narrate limitations that do not exist.")
    } else {
      appendLine("- CHAT mode: conversation only. Propose actions; do not execute. The operator can flip the composer switch to WORK to grant execution.")
    }
    appendLine("- Never claim a capability is unavailable without checking this block or attempting the appropriate tool.")
    appendLine("- The ONLY machine states are CHAT and WORK. There is no review mode, no analysis-only mode, no sovereign-fallback state, no execution lease requirement for device actions. Do not invent states.")
    appendLine("- Never mention ADB, USB cables, Tailscale, remote desktops, or Windows drives for actions on this phone.")
    appendLine("- Camera/photo results are phone-local resources addressed by purpclaw:// URI. They will never be on C:, D:, E:, L: or any PC filesystem. Use vision.analyze(resourceUri).")
    appendLine("- Provider/model choice says where THINKING happens. It NEVER changes where you LIVE or what you can TOUCH.")
    appendLine("- For self-knowledge questions call system.runtime.get / system.capabilities.list / system.permissions.list instead of improvising.")
  }

  companion object {
    fun resolve(
      context: Context,
      executionMode: String,
      homeConnected: Boolean,
      inferenceProvider: String = "on-device provider pool",
      inferenceModel: String = "auto",
      inferenceNote: String = "remote_api / on-device"
    ): RuntimeContext {
      val caps = CapabilityRegistry.resolve(context)
      val permSnapshot = PermissionRegistry.snapshot(context)
      val permissionMap = permSnapshot.associate {
        it.name.substringAfterLast('.') to
          if (it.granted) "granted" else if (it.declared) "declared-denied" else "not-declared"
      }

      return RuntimeContext(
        surface = "android",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".uppercase(),
        androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        executionMode = executionMode,
        canExecute = executionMode == "WORK",
        executionAuthority = if (homeConnected) "HOME_DELEGATED" else "ANDROID_LOCAL",
        inferenceProvider = inferenceProvider,
        inferenceModel = inferenceModel,
        inferenceLocation = inferenceNote,
        homeConnected = homeConnected,
        homeAuthority = homeConnected,
        sdkAvailable = true,
        nativeToolBridge = true,
        capabilities = caps.associate { it.id to it.available },
        capabilityDetails = caps.associateBy { it.id },
        permissions = permissionMap
      )
    }
  }
}
