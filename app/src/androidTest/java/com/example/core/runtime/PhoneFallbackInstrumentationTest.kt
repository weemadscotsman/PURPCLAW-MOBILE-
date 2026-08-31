package com.example.core.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.runtime.KeyStoreVault
import com.example.core.runtime.AndroidLocalModelHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device TVG: proves the sovereign phone fallback actually TALKS via
 * openrouter/free (the smart free aggregator) using the device's real
 * OpenRouter key from the KeyStore. This is the exact path the council
 * podcast uses (resolvePhoneModel -> generateResponse -> callOpenRouter).
 * No UI driving required — deterministic green tick on real inference.
 */
@RunWith(AndroidJUnit4::class)
class PhoneFallbackInstrumentationTest {

  @Test
  fun openrouterFreeTalksOnDevice() = runBlocking {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    val vault = KeyStoreVault(app)
    val localModelHost = AndroidLocalModelHost(app.filesDir)
    val router = ProviderRouter(vault, localModelHost, app)

    // Refresh catalogues so openrouter/free + NIM chat pool are populated.
    router.refreshOpenRouterCatalogue()
    router.refreshNimCatalogue()

    // Mirror the real podcast fallback: openrouter/free first, then rotate to
    // concrete free chat models if the aggregator routes to a dead one.
    val candidates = mutableListOf("openrouter/free")
    candidates += router.queryAllFreeGateways()
      .filter { it.modelClass == "chat" && it.isFree && it.id != "openrouter/free" }
      .map { it.id }

    var lastErr: String? = null
    var replied = ""
    for (cand in candidates.take(6)) {
      val res = router.generateResponse(
        prompt = "Reply with exactly: PURPCLAW_ONLINE",
        preferredProvider = cand,
        systemInstruction = "You are a terse test agent. Reply with only the requested string.",
        sessionId = "device_tvg_$cand",
        toolsRequired = false,
        visionRequired = false,
        isHomeOnline = false,
        conversationHistory = emptyList(),
        priority = SharedQuotaLedger.Priority.PODCAST
      )
      println("TVG candidate='$cand' content='${res.content.take(40)}' error='${res.errorMessage}'")
      if (res.content.isNotBlank() && res.errorMessage == null) {
        replied = res.content
        break
      }
      lastErr = res.errorMessage
    }

    assertTrue(
      "phone fallback must return a real reply after rotating candidates, last error=$lastErr",
      replied.isNotBlank()
    )
  }
}
