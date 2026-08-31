package com.example.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRouterNimClassifierTest {
  @Test
  fun `chat families remain eligible`() {
    assertTrue(ProviderRouter.isNimChatEndpoint("meta/llama-3.3-70b-instruct"))
    assertTrue(ProviderRouter.isNimChatEndpoint("mistralai/mistral-nemotron"))
    assertTrue(ProviderRouter.isNimChatEndpoint("nvidia/diffusiongemma-26b-a4b-it"))
  }

  @Test
  fun `code completion and non-chat families are rejected`() {
    listOf(
      "bigcode/starcoder2-15b",
      "vendor/super-coder-32b",
      "vendor/code-completion-model",
      "nvidia/nv-embedqa-e5-v5",
      "nvidia/llama-3_2-nv-rerankqa-1b-v2",
      "nvidia/cosmos-transfer1-7b",
      "vendor/vision-language-vl",
      "vendor/whisper-large-v3",
      "vendor/guard-model"
    ).forEach { assertFalse("must not enter NIM chat pool: $it", ProviderRouter.isNimChatEndpoint(it)) }
  }
}
