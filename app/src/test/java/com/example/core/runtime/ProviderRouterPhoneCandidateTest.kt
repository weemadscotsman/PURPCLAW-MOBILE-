package com.example.core.runtime

import com.example.core.model.CatalogueModel
import com.example.core.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRouterPhoneCandidateTest {
  private fun model(id: String, source: String, tools: Boolean) = CatalogueModel(
    id = id,
    name = id,
    provider = source,
    providerType = ProviderType.GATEWAY,
    sourceProvider = source,
    description = "live test catalogue",
    contextLength = 0,
    isFree = true,
    isToolCapable = tools,
    isVisionCapable = false,
    isReasoningCapable = false,
    modelClass = "chat",
    avgLatencyMs = 0,
    healthStatus = "HEALTHY",
    pricingPrompt = 0.0,
    pricingCompletion = 0.0,
    isQualifiedFree = true,
    configured = true,
    available = true
  )

  @Test
  fun `work auto has concrete phone routes when home is offline`() {
    val routes = ProviderRouter.buildPhoneCandidates(
      gateways = listOf(
        model("nvidia/nemotron", "nim", tools = false),
        model("openrouter/tool-model:free", "openrouter", tools = true)
      ),
      requestedModel = null,
      toolsRequired = true,
      openRouterConfigured = true,
      quarantined = { false }
    )
    assertEquals("openrouter/tool-model:free", routes.first())
    assertEquals("nvidia/nemotron", routes[1])
    assertTrue(routes.contains("nvidia/nemotron"))
    assertTrue(routes.contains("openrouter/tool-model:free"))
  }

  @Test
  fun `nim internal function uuid resolves to advertised model slug`() {
    assertEquals(
      "meta/llama-3.3-70b-instruct",
      ProviderRouter.callableNimModelId(
        "23bd454d-b225-49a3-8118-582a62fc51b8",
        "meta/llama-3.3-70b-instruct"
      )
    )
    assertEquals(
      "nvidia/nemotron-3.5-lightning",
      ProviderRouter.callableNimModelId("nvidia/nemotron-3.5-lightning", "Nemotron")
    )
  }

  @Test
  fun `auto rejects internal nim uuids and harness-only openrouter models`() {
    val routes = ProviderRouter.buildPhoneCandidates(
      gateways = listOf(
        model("23bd454d-b225-49a3-8118-582a62fc51b8", "nim", tools = true),
        model("thinkingmachines/inkling:free", "openrouter", tools = true),
        model("nvidia/nemotron-3.5-lightning-30b-a3b", "nim", tools = true)
      ),
      requestedModel = null,
      toolsRequired = true,
      openRouterConfigured = true,
      quarantined = { false }
    )

    assertEquals(
      listOf("nvidia/nemotron-3.5-lightning-30b-a3b", "openrouter/free"),
      routes
    )
  }

  @Test
  fun `quarantined and unconfigured routes cannot leak into candidate list`() {
    val dead = model("openrouter/dead:free", "openrouter", tools = true).copy(configured = false)
    val routes = ProviderRouter.buildPhoneCandidates(
      gateways = listOf(dead),
      requestedModel = dead.id,
      toolsRequired = true,
      openRouterConfigured = false,
      quarantined = { it == dead.id }
    )
    assertTrue(routes.isEmpty())
  }
}
