package com.example.core.runtime

import com.example.core.model.ToolAffinity
import com.example.core.model.ToolDescriptor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CanonicalToolWireNameTest {
  @Test
  fun `canonical dotted tool name is provider safe on wire`() {
    val descriptor = ToolDescriptor(
      name = "android.file.write",
      displayName = "Writer",
      description = "Writes a file",
      affinity = ToolAffinity.ANDROID_NATIVE,
      isEnabled = true,
      requiresLease = true,
      category = "mutate",
      parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    )
    val wire = canonicalToolsToWire(listOf(descriptor)).getJSONObject(0)
      .getJSONObject("function").getString("name")
    assertEquals("android__file__write", wire)
    assertFalse(wire.contains('.'))
  }
}
