package com.example.core.runtime

/** Why a same-turn tool loop stopped. Persisted/debuggable state, not UI copy. */
enum class ToolLoopTermination { FINAL_RESPONSE, BLOCKED, CANCELLED, MAX_STEPS }

data class ToolLoopOutcome<M, R>(
  val message: M,
  val results: List<R>,
  val steps: Int,
  val termination: ToolLoopTermination
)

/**
 * Provider-neutral continuation law. A tool request is an intermediate state;
 * only a final response, evidenced block, cancellation, or bound ends a turn.
 */
object BoundedToolContinuation {
  suspend fun <M, C, R> run(
    initial: M,
    maxSteps: Int = 6,
    calls: (M) -> List<C>,
    finalText: (M) -> String,
    errorText: (M) -> String?,
    isCancelled: () -> Boolean = { false },
    execute: suspend (C) -> R,
    resume: suspend (M, List<Pair<C, R>>, Int) -> M
  ): ToolLoopOutcome<M, R> {
    var message = initial
    var steps = 0
    val executed = mutableListOf<R>()
    while (true) {
      if (isCancelled()) {
        return ToolLoopOutcome(message, executed, steps, ToolLoopTermination.CANCELLED)
      }
      val requested = calls(message)
      if (requested.isEmpty()) {
        val termination = if (finalText(message).isNotBlank()) ToolLoopTermination.FINAL_RESPONSE
          else ToolLoopTermination.BLOCKED
        return ToolLoopOutcome(message, executed, steps, termination)
      }
      if (steps >= maxSteps) {
        return ToolLoopOutcome(message, executed, steps, ToolLoopTermination.MAX_STEPS)
      }
      val pairs = requested.map { call -> call to execute(call) }
      executed += pairs.map { it.second }
      steps++
      message = resume(message, pairs, steps)
      // An error is data for the resumed Soul. It does not terminate before
      // that continuation has had a chance to retry, diagnose, or block.
      errorText(message)
    }
  }
}
