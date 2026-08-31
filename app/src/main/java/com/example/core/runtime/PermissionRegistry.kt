package com.example.core.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * PermissionRegistry — live snapshot of what Android currently permits.
 * Declared = present in this app's manifest. Granted = OS-level grant right now.
 * A permission can be declared but denied; that distinction drives ExecutionPolicy.
 */
object PermissionRegistry {

  data class PermissionState(
    val name: String,
    val declared: Boolean,
    val granted: Boolean
  )

  private val TRACKED: List<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.ACCESS_NETWORK_STATE)
    if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.FOREGROUND_SERVICE)
  }

  fun snapshot(context: Context): List<PermissionState> {
    val pm = context.packageManager
    val pkgInfo = try {
      pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    } catch (_: Exception) { null }
    val declared = pkgInfo?.requestedPermissions?.toSet() ?: emptySet()

    return TRACKED.map { name ->
      PermissionState(
        name = name,
        declared = name in declared,
        granted = ContextCompat.checkSelfPermission(context, name) ==
          PackageManager.PERMISSION_GRANTED
      )
    }
  }

  fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  /** Permissions required by a capability but not currently granted. */
  fun missingFor(context: Context, permissions: List<String>): List<String> =
    permissions.filter { !isGranted(context, it) }

  fun toPromptBlock(states: List<PermissionState>): String = buildString {
    appendLine("Permissions (live):")
    states.forEach {
      appendLine("  ${it.name.substringAfterLast('.')}: ${if (it.granted) "granted" else if (it.declared) "declared-denied" else "not-declared"}")
    }
  }
}
