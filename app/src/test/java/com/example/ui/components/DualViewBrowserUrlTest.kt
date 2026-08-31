package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DualViewBrowserUrlTest {
  @Test
  fun `phone local file uri is never rewritten as https host`() {
    val uri = "file:/data/user/0/com.aistudio.purpclaw.osv7/files/workspace/fake_netflix.html"
    assertEquals(uri, normalizeUrl(uri))
  }

  @Test
  fun `ordinary host still receives https scheme`() {
    assertEquals("https://example.com", normalizeUrl("example.com"))
  }
}
