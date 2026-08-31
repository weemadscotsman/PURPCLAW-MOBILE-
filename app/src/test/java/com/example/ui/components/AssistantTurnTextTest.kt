package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantTurnTextTest {
  @Test
  fun `active blank turn renders progress rather than terminal retry`() {
    assertEquals("ARMING TOOLS", assistantVisibleText("", true, "ARMING TOOLS"))
    assertEquals("Working…", assistantVisibleText("", true, ""))
  }

  @Test
  fun `ended blank turn renders truthful retry`() {
    assertEquals("No response received · Retry", assistantVisibleText("", false, ""))
  }
}
