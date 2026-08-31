package com.example.core.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedToolContinuationTest {
  private data class Message(val text: String = "", val calls: List<String> = emptyList(), val error: String? = null)

  @Test fun `inspect result resumes into final explanation`() = runTest {
    val outcome = BoundedToolContinuation.run(
      initial = Message(calls = listOf("inspect")),
      calls = { it.calls }, finalText = { it.text }, errorText = { it.error },
      execute = { "runtime=android" },
      resume = { _, results, _ -> Message(text = "I verified ${results.single().second}") }
    )
    assertEquals(ToolLoopTermination.FINAL_RESPONSE, outcome.termination)
    assertEquals(1, outcome.steps)
  }

  @Test fun `three tool chain cannot finish prematurely`() = runTest {
    var next = 1
    val outcome = BoundedToolContinuation.run(
      initial = Message(calls = listOf("one")),
      calls = { it.calls }, finalText = { it.text }, errorText = { it.error },
      execute = { "ok:$it" },
      resume = { _, _, _ ->
        next++
        if (next <= 3) Message(calls = listOf(listOf("one", "two", "three")[next - 1]))
        else Message(text = "done")
      }
    )
    assertEquals(3, outcome.steps)
    assertEquals(listOf("ok:one", "ok:two", "ok:three"), outcome.results)
  }

  @Test fun `tool failure is reinjected before evidenced block`() = runTest {
    var resumed = false
    val outcome = BoundedToolContinuation.run(
      initial = Message(calls = listOf("missing")),
      calls = { it.calls }, finalText = { it.text }, errorText = { it.error },
      execute = { "tool.not_found" },
      resume = { _, results, _ -> resumed = true; Message(error = results.single().second) }
    )
    assertEquals(true, resumed)
    assertEquals(ToolLoopTermination.BLOCKED, outcome.termination)
  }

  @Test fun `cancel and max step are explicit terminal states`() = runTest {
    val cancelled = BoundedToolContinuation.run(
      initial = Message(calls = listOf("one")), calls = { it.calls },
      finalText = { it.text }, errorText = { it.error }, isCancelled = { true },
      execute = { "never" }, resume = { m, _, _ -> m }
    )
    assertEquals(ToolLoopTermination.CANCELLED, cancelled.termination)

    val bounded = BoundedToolContinuation.run(
      initial = Message(calls = listOf("loop")), maxSteps = 2, calls = { it.calls },
      finalText = { it.text }, errorText = { it.error }, execute = { "ok" },
      resume = { m, _, _ -> m }
    )
    assertEquals(ToolLoopTermination.MAX_STEPS, bounded.termination)
    assertEquals(2, bounded.steps)
  }
}
