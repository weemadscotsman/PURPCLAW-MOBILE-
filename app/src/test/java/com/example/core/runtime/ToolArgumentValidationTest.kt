package com.example.core.runtime

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolArgumentValidationTest {

  @Test
  fun `empty object is rejected before argument requiring Android tools execute`() = runBlocking {
    val engine = ToolRuntimeEngine(
      context = ApplicationProvider.getApplicationContext(),
      signer = KeystoreReceiptSigner()
    ).apply { currentExecutionMode = ExecutionPolicy.Mode.WORK }

    listOf(
      "android.browser.open",
      "android.app.open",
      "android.settings.panel",
      "android.clipboard.write",
      "vision.analyze",
      "system.agent.inspect",
      "system.agent.delegate"
    ).forEach { tool ->
      val result = engine.executeTool(tool, "{}")
      val body = JSONObject(result.record.output)
      assertFalse("$tool must not execute", result.record.isSuccess)
      assertEquals("INVALID_TOOL_ARGUMENTS", body.getString("error"))
      assertEquals(tool, body.getString("tool"))
      assertNull(result.proofReceipt)
    }
  }
}
