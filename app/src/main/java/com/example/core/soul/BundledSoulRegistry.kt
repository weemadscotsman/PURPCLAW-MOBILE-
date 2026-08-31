package com.example.core.soul

import android.content.Context
import com.example.core.network.HomeRuntimeBridge
import com.example.core.runtime.TextToSpeechEngine
import com.example.core.runtime.VoiceProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only Android adapter over the build-synchronised canonical Soul file.
 * It preserves identity discovery when Home is offline; it does not claim a
 * Soul is currently running or remotely executable.
 */
object BundledSoulRegistry {
  private const val ASSET = "registry/souls.json"

  fun load(context: Context): List<HomeRuntimeBridge.RosterAgent> = runCatching {
    val root = JSONObject(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    val rows = root.optJSONArray("souls") ?: JSONArray()
    (0 until rows.length()).mapNotNull { index ->
      val soul = rows.optJSONObject(index) ?: return@mapNotNull null
      val id = soul.optString("id")
      if (id.isBlank()) return@mapNotNull null
      HomeRuntimeBridge.RosterAgent(
        id = id,
        name = soul.optString("name", id),
        desc = soul.optString("role"),
        icon = soul.optString("emoji"),
        model = "AUTO",
        division = soul.optString("division", "UNASSIGNED"),
        role = soul.optString("title").ifBlank { soul.optString("role") },
        wants = text(soul.opt("wants")),
        needs = text(soul.opt("needs")),
        goals = text(soul.opt("goals")),
        wishes = text(soul.opt("wishes")),
        soulDescription = soul.optString("soulDescription"),
        voiceProfile = voice(soul.optJSONObject("voiceProfile"), soul.optString("name", id))
      )
    }
  }.getOrDefault(emptyList())

  private fun text(value: Any?): String = when (value) {
    is JSONArray -> (0 until value.length()).joinToString("; ") { value.optString(it) }
    null, JSONObject.NULL -> ""
    else -> value.toString()
  }

  private fun voice(value: JSONObject?, soulName: String): VoiceProfile {
    if (value == null) return TextToSpeechEngine.voiceProfileFor(soulName)
    return VoiceProfile(
      soulId = soulName,
      kokoroVoiceId = value.optString("voice_id", "af_heart"),
      platformVoiceSlot = value.optInt("platform_voice_slot", 0),
      rate = value.optDouble("speaking_rate", 0.95).toFloat(),
      pitch = value.optDouble("pitch", 1.0).toFloat(),
      pauseStyle = value.optString("pause_style", "natural"),
      energy = value.optDouble("energy", 0.64).toFloat(),
      fallbackVoiceId = value.optString("fallback_voice", "af_heart")
    )
  }
}
