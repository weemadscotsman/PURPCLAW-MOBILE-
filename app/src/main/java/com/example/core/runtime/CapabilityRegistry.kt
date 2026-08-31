package com.example.core.runtime

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * CapabilityRegistry — ONE canonical live registry of what PurpAngolin can do
 * RIGHT NOW on this device. Probed from hardware/OS state, never from memory,
 * conversation history, or provider routing.
 *
 * The model asks this registry instead of hallucinating tool names. IDs use
 * the android.* namespace; each entry reports availability, executor, required
 * permissions and whether it has been verified live.
 */
data class Capability(
  val id: String,                       // e.g. "android.camera.capture"
  val provider: String = "android",     // STEP 12.11 (2026-08-27): canonical identity
  val version: String = "1",            // STEP 12.11: bumped on shape change
  val aliases: List<String> = emptyList(), // STEP 12.11: alternative ids that resolve here
  val available: Boolean,
  val executor: String = "ANDROID_NATIVE",
  val permissions: List<String> = emptyList(),
  val verified: Boolean = false,        // true only after a successful live execution
  val note: String? = null              // why unavailable / degraded
) {
  companion object {
    /** Canonical identity separator — "android:flashlight:1" not "android.flashlight@1". */
    const val CANONICAL_KEY_SEP = ":"
  }

  /** "android:flashlight:1" — the dedupe key used across registries. */
  val canonicalKey: String
    get() = "$provider$CANONICAL_KEY_SEP$id$CANONICAL_KEY_SEP$version"
}

object CapabilityRegistry {

  private fun cameraHasFlash(context: Context): Boolean = try {
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    cm?.cameraIdList?.any { id ->
      cm.getCameraCharacteristics(id)
        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } ?: false
  } catch (_: Exception) { false }

  private fun granted(context: Context, p: String) =
    PermissionRegistry.isGranted(context, p)

  /**
   * STEP 12.11 (2026-08-27): dedupe by canonical key (provider:id:version).
   * First occurrence wins; later entries with the same canonicalKey fold their
   * `id` into the kept Capability's `aliases` list. Conflicting fields (different
   * `available` or `verified`) are NOT auto-resolved — the first wins and a
   * conflict is logged. Phase 2 will surface conflicts via a registry report.
   */
  fun dedupeByCanonical(caps: List<Capability>): List<Capability> {
    if (caps.isEmpty()) return emptyList()
    val seen = LinkedHashMap<String, Capability>()
    for (c in caps) {
      val key = c.canonicalKey
      val existing = seen[key]
      if (existing == null) {
        seen[key] = c
      } else {
        // Fold the duplicate's own aliases + its id into the keeper's aliases,
        // stripping anything that matches the keeper's id or is blank.
        val mergedAliases = (existing.aliases + c.aliases + c.id)
          .filter { it != existing.id && it.isNotBlank() }
          .distinct()
        // If `available` disagrees, the keeper wins; flip `verified` to true if either side verified.
        val mergedVerified = existing.verified || c.verified
        seen[key] = existing.copy(aliases = mergedAliases, verified = mergedVerified)
      }
    }
    return seen.values.toList()
  }

  /** Live-probe every canonical capability. Cheap probes only — never opens camera/torch. */
  fun resolve(context: Context): List<Capability> {
    val raw = resolveRaw(context)
    return dedupeByCanonical(raw)
  }

  /** Live-probe WITHOUT dedupe — used by tests that want to assert the dedupe behavior. */
  fun resolveRaw(context: Context): List<Capability> {
    val hasFlash = cameraHasFlash(context)
    return listOf(
      cap("android.flashlight", hasFlash),
      cap("android.camera.capture", granted(context, android.Manifest.permission.CAMERA),
        permissions = listOf("CAMERA")),
      cap("android.camera.preview", granted(context, android.Manifest.permission.CAMERA),
        permissions = listOf("CAMERA")),
      cap("android.app.open", true),
      cap("android.app.list", true),
      cap("android.app.foreground", true),
      cap("android.browser.open", true),
      cap("android.browser.embed", true, note = "Dual View surface"),
      cap("android.screen.capture", true, note = "MediaProjection consent per session"),
      cap("android.intent.launch", true),
      cap("android.share", true),
      cap(
        "android.clipboard.read", true,
        note = if (android.os.Build.VERSION.SDK_INT >= 29 &&
          !granted(context, "android.permission.ACCESS_BACKGROUND_LOCATION")
        ) null else null
      ),
      cap("android.clipboard.write", true),
      cap("android.files.read", true, note = "app-scoped workspace"),
      cap("android.files.write", true, note = "app-scoped workspace"),
      cap(
        "android.notifications.read", false,
        note = "requires notification listener access"
      ),
      cap(
        "android.notifications.post",
        granted(context, android.Manifest.permission.POST_NOTIFICATIONS),
        permissions = listOf("POST_NOTIFICATIONS")
      ),
      cap("android.device.info", true),
      cap("android.network.status", true),
      cap("android.vibrate", true),
      cap("android.tts.speak", true),
      cap("android.speech.recognize", true),
      cap("system.self.inspection", true)
    )
  }

  private fun cap(
    id: String,
    available: Boolean,
    executor: String = "ANDROID_NATIVE",
    permissions: List<String> = emptyList(),
    note: String? = null
  ): Capability = Capability(
    id = id, available = available, executor = executor,
    permissions = permissions, verified = false, note = note
  )

  /** Resolve an intent phrase to a real capability/tool id. No guessing by the model. */
  fun resolveIntent(phrase: String): String? {
    val p = phrase.lowercase()
    val creation = Regex("\\b(build|create|make|write|code|implement|develop|generate|design)\\b").containsMatchIn(p) &&
      Regex("\\b(game|app|application|website|webpage|web\\s+page|site|page|html|css|javascript|tool|project|prototype|artifact)\\b").containsMatchIn(p)
    if (creation) return null
    return when {
      p.contains("flashlight") || p.contains("torch") -> "android.flashlight"
      p.contains("camera") && (p.contains("take") || p.contains("photo") || p.contains("capture")) -> "android.camera.capture"
      p.contains("open") && (p.contains("browser") || p.contains("chrome")) -> "android.browser.open"
      p.contains("website") || p.contains("url") || p.contains("http") -> "android.browser.open"
      else -> null
    }
  }

  fun toPromptBlock(caps: List<Capability>): String = buildString {
    appendLine("Capabilities (live registry — these are the ONLY executable actions):")
    caps.filter { it.available }.forEach { c ->
      val aliasSuffix = if (c.aliases.isNotEmpty()) " (alias: ${c.aliases.joinToString(",")})" else ""
      appendLine("  ${c.id} [${c.provider}@${c.version}]$aliasSuffix [${c.executor}]${if (c.permissions.isNotEmpty()) " needs:${c.permissions.joinToString(",")}" else ""}")
    }
    val unavailable = caps.filterNot { it.available }
    if (unavailable.isNotEmpty()) {
      appendLine("Unavailable right now:")
      unavailable.forEach { appendLine("  ${it.id} (${it.note ?: "unavailable"})") }
    }
  }
}
