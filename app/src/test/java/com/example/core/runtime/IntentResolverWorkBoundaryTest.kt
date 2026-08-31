package com.example.core.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntentResolverWorkBoundaryTest {

  @Test
  fun `create goal is not converted into browser search`() {
    val routed = IntentResolver.route(
      "Build me a small original browser game and open the finished playable result.",
      ExecutionPolicy.Mode.WORK
    )
    assertNull(routed)
    assertNull(IntentResolver.route("Make me a webpage for a game in HTML", ExecutionPolicy.Mode.WORK))
    assertNull(IntentResolver.route("Write a web page using CSS and JavaScript", ExecutionPolicy.Mode.WORK))
  }

  @Test
  fun `structured browser call cannot hijack creation goal`() {
    val creation = "Build me a browser game in HTML"
    val hijack = CanonicalToolCall(
      callId = "call-hijack",
      toolName = "android.browser.open",
      args = mapOf("target" to creation, "mode" to "SEARCH", "reason" to "operator_browser_intent"),
      sourceProvider = "test",
      rawJson = "{}"
    )
    val denied = ToolIntentBoundary.denialReason(creation, hijack)
    org.junit.Assert.assertNotNull(denied)

    val research = hijack.copy(args = mapOf(
      "target" to "HTML canvas pointer events",
      "mode" to "SEARCH",
      "reason" to "DEPENDENCY_RESEARCH"
    ))
    assertNull(ToolIntentBoundary.denialReason(creation, research))
  }

  @Test
  fun `browser actions carry structured mode reason and target`() {
    val search = IntentResolver.route("Search the web for purple pangolins", ExecutionPolicy.Mode.WORK)!!
    val searchArgs = JSONObject(search.args)
    assertEquals("android.browser.open", search.tool)
    assertEquals("SEARCH", searchArgs.getString("mode"))
    assertEquals("operator_browser_intent", searchArgs.getString("reason"))

    val open = IntentResolver.route("Open https://example.com in Chrome", ExecutionPolicy.Mode.WORK)!!
    val openArgs = JSONObject(open.args)
    assertEquals("OPEN_URL", openArgs.getString("mode"))
    assertEquals("https://example.com", openArgs.getString("target"))
    assertEquals("Chrome", openArgs.getString("browser"))
  }
}
