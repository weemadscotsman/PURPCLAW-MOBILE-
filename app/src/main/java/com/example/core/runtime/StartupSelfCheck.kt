package com.example.core.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.core.soul.SoulLoader

/**
 * StartupSelfCheck — runs once at application start:
 *   detect Android runtime → load mobile SOUL → register Android-native tools →
 *   enumerate capabilities → read permission state → test native bridge →
 *   detect optional Home PC → build RuntimeContext → publish runtime state.
 *
 * Lightweight only: probes registry metadata, never physically activates
 * camera/flashlight/etc. Failures are logged, not fatal.
 */
object StartupSelfCheck {

  data class Report(
    val androidRuntimeDetected: Boolean,
    val soulLoaded: Boolean,
    val nativeToolsRegistered: Int,
    val capabilitiesEnumerated: Int,
    val permissionsSnapshot: List<PermissionRegistry.PermissionState>,
    val homePcConnected: Boolean,
    val passed: Boolean,
    val notes: List<String>
  )

  fun run(
    context: Context,
    homeConnected: Boolean,
    executionMode: ExecutionPolicy.Mode = ExecutionPolicy.Mode.CHAT
  ): Report {
    val notes = mutableListOf<String>()

    // 1. Android runtime
    val onAndroid = try {
      Build.MODEL.isNotBlank()
    } catch (_: Exception) { false }
    if (!onAndroid) notes.add("android runtime probe failed")

    // 2. Mobile SOUL
    val soulOk = try {
      SoulLoader.load(context).identityBlock.isNotBlank()
    } catch (_: Exception) { false }
    if (!soulOk) notes.add("SOUL.md missing or empty — falling back to hardcoded identity")

    // 3-4. Native tools registered / capabilities enumerated (registry metadata)
    val caps = try {
      CapabilityRegistry.resolve(context)
    } catch (_: Exception) { emptyList() }
    if (caps.isEmpty()) notes.add("capability registry returned empty")

    // 5. Permission state
    val perms = try {
      PermissionRegistry.snapshot(context)
    } catch (_: Exception) { emptyList() }

    // 6. Native bridge sanity (system service availability)
    val bridgeOk = context.getSystemService(Context.CAMERA_SERVICE) != null &&
      context.getSystemService(Context.ACTIVITY_SERVICE) != null
    if (!bridgeOk) notes.add("native tool bridge services unavailable")

    // 7-9. Home PC + RuntimeContext + publish handled by caller; here we record truth
    val ctx = RuntimeContext.resolve(
      context, executionMode.name, homeConnected
    )

    Log.i("StartupSelfCheck", "passed=${notes.isEmpty()} caps=${caps.size} perms=${perms.count { it.granted }}/${perms.size} home=$homeConnected notes=$notes")

    return Report(
      androidRuntimeDetected = onAndroid,
      soulLoaded = soulOk,
      nativeToolsRegistered = caps.size,
      capabilitiesEnumerated = caps.size,
      permissionsSnapshot = perms,
      homePcConnected = homeConnected,
      passed = notes.isEmpty(),
      notes = notes
    )
  }
}
