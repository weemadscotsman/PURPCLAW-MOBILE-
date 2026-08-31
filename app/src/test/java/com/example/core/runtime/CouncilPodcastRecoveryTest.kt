package com.example.core.runtime

import androidx.test.core.app.ApplicationProvider
import com.example.core.network.HomeRuntimeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CouncilPodcastRecoveryTest {

  @Test
  fun `hang recovery retries once then hands authority back to AUTO router`() {
    val plan = CouncilPodcastEngine.inferenceFallbackPlan()

    assertEquals(3, plan.size)
    assertEquals("MiniMax-M2.7", plan[0].first)
    assertEquals(CouncilPodcastEngine.InferencePhase.THINKING, plan[0].second)
    assertEquals("MiniMax-M2.7", plan[1].first)
    assertEquals(CouncilPodcastEngine.InferencePhase.RETRYING, plan[1].second)
    assertNull(plan[2].first)
    assertEquals(CouncilPodcastEngine.InferencePhase.SWITCHING_MODEL, plan[2].second)
  }

  @Test
  fun `terminal failures are classified for visible retry receipts`() {
    val engine = CouncilPodcastEngine(
      ApplicationProvider.getApplicationContext(), HomeRuntimeBridge, tts = null
    )

    assertEquals("TIMEOUT", engine.classifyInferenceFailure("SocketTimeoutException: timed out", false))
    assertEquals("EMPTY_RESPONSE", engine.classifyInferenceFailure("", true))
    assertEquals("AUTH", engine.classifyInferenceFailure("HTTP 401 invalid API key", false))
    assertEquals("RATE_LIMIT", engine.classifyInferenceFailure("HTTP 429 rate limit", false))
    assertEquals(
      "PROVIDER_QUOTA_EXHAUSTED",
      engine.classifyInferenceFailure("Usage limit reached; add credits or try again Sep 4", false)
    )
    assertEquals("MALFORMED_RESPONSE", engine.classifyInferenceFailure("malformed JSON", false))
    assertEquals("MODEL_UNAVAILABLE", engine.classifyInferenceFailure("model unavailable", false))
    assertEquals("NETWORK", engine.classifyInferenceFailure("network connection refused", false))
    assertEquals("PROVIDER_EXCEPTION", engine.classifyInferenceFailure("unexpected provider failure", false))
  }

  @Test
  fun `podcast rejects repeated turns and canned persona examples`() {
    val engine = CouncilPodcastEngine(
      ApplicationProvider.getApplicationContext(), HomeRuntimeBridge, tts = null
    )
    val prior = listOf(
      "That router is a priority ladder with four fallback tiers and a hardcoded override."
    )
    val persona = "Example line: The event spine was a good call but the checkpoint schema still needs proof."

    assertEquals(
      false,
      engine.isFreshPodcastContribution(
        "That router is a priority ladder with four fallback tiers and a hardcoded override.",
        prior,
        persona
      )
    )
    assertEquals(
      false,
      engine.isFreshPodcastContribution(
        "The event spine was a good call but the checkpoint schema still needs proof.",
        prior,
        persona
      )
    )
    assertEquals(
      true,
      engine.isFreshPodcastContribution(
        "The latest Android trace changes the argument: cancellation now preserves the child receipt.",
        prior,
        persona
      )
    )
  }
}
