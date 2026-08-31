package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Avatar3DClipsTest {

  @Test
  fun displayNamesResolveToStableAssetIds() {
    assertEquals("purpangolin", Avatar3DClips.assetId("PurpAngolin"))
    assertEquals("babshaggoth", Avatar3DClips.assetId("Babshaggoth"))
    assertEquals("lyra", Avatar3DClips.assetId("Lyra Voice"))
  }

  @Test
  fun everyBundledCharacterMapsEveryMoodToARealDeclaredClip() {
    val declared = mapOf(
      "PurpAngolin" to Avatar3DClips.PURPANGOLIN.values.toSet(),
      "Babshaggoth" to Avatar3DClips.BABSHAGGOTH.values.toSet(),
      "Lyra Voice" to Avatar3DClips.LYRA.values.toSet()
    )

    declared.forEach { (character, clips) ->
      PurpAngolinMood.entries.forEach { mood ->
        assertNotNull("$character must map ${mood.label}", Avatar3DClips.clipFor(character, mood, clips))
      }
    }
  }

  @Test
  fun missingPrimaryFallsBackToAnAvailableIdleClip() {
    assertEquals(
      "Chair_Sit_Idle_F",
      Avatar3DClips.clipFor("PurpAngolin", PurpAngolinMood.FAILED, setOf("Chair_Sit_Idle_F"))
    )
  }

  @Test
  fun safeBringUpOnlyAdmitsTheCanonicalIdle() {
    assertEquals(
      "Chair_Sit_Idle_F",
      Avatar3DClips.safeIdleClipFor("PurpAngolin", setOf("Running", "Chair_Sit_Idle_F"))
    )
    assertEquals(null, Avatar3DClips.safeIdleClipFor("PurpAngolin", setOf("Running")))
  }
}
