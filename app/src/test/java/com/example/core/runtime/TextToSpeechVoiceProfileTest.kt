package com.example.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TextToSpeechVoiceProfileTest {

  @Test
  fun `known Souls retain stable Kokoro identities`() {
    val expected = mapOf(
      "PurpAngolin" to "af_heart",
      "Lyra Voice" to "bf_emma",
      "Barnaby Prime" to "bm_george",
      "Aegis Sentinel" to "am_michael",
      "CodeForge" to "am_adam",
      "Forge Warden" to "bm_lewis",
      "Octavia Lens" to "af_bella",
      "Signal Weaver" to "af_nicole",
      "Babshaggoth" to "bf_isabella"
    )

    expected.forEach { (soul, voice) ->
      assertEquals(voice, TextToSpeechEngine.voiceProfileFor(soul).kokoroVoiceId)
      assertEquals(
        TextToSpeechEngine.voiceProfileFor(soul),
        TextToSpeechEngine.voiceProfileFor(soul.lowercase())
      )
    }
  }

  @Test
  fun `council Souls do not collapse onto one voice identity`() {
    val voices = listOf(
      "PurpAngolin", "Lyra Voice", "Barnaby Prime", "Aegis Sentinel",
      "CodeForge", "Forge Warden", "Octavia Lens", "Signal Weaver", "Babshaggoth"
    ).map { TextToSpeechEngine.voiceProfileFor(it).kokoroVoiceId }

    assertEquals(voices.size, voices.distinct().size)
    assertNotEquals(
      TextToSpeechEngine.voiceProfileFor("PurpAngolin"),
      TextToSpeechEngine.voiceProfileFor("Lyra Voice")
    )
  }

  @Test
  fun `unknown Soul fallback is deterministic and never random`() {
    val first = TextToSpeechEngine.voiceProfileFor("Guest Goblin")
    val second = TextToSpeechEngine.voiceProfileFor("Guest Goblin")

    assertEquals(first, second)
    assertEquals("af_heart", first.kokoroVoiceId)
  }
}
