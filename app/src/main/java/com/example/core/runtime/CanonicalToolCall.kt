package com.example.core.runtime

import android.util.Log
import com.example.core.model.ProofReceipt
import com.example.core.model.ToolCallRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * CANONICAL TOOL CALL — the ONE shape every provider's tool_calls array
 * normalizes into before any dispatch logic runs.
 *
 * Why a normalizer?
 * - OpenRouter / NIM / OpenAI all emit the same envelope today, but the
 *   inner `function.arguments` is a string-encoded JSON whose parsing rules
 *   vary (some providers escape incorrectly, some omit `id`).
 * - The phone has ONE dispatcher regardless of which provider produced the
 *   call. Centralizing the parse means the rest of the system can treat
 *   OpenRouter/NIM tool calls as first-class.
 *
 * Pipeline (one direction):
 *
 *   ProviderToolCall  (JsonObject)
 *      │
 *      ▼  CanonicalToolCall.fromProvider(toolCall, source)
 *   CanonicalToolCall
 *      │
 *      ▼  ToolRegistry → PermissionGate → ExecutionLease → Executor → Receipt
 *   ProofReceipt + ToolCallRecord
 */

data class CanonicalToolCall(
  val callId: String,
  val toolName: String,
  val args: Map<String, Any>,
  val sourceProvider: String,    // "openai" | "openrouter" | "nim" | "anthropic_via_adapter" | "unknown"
  val rawJson: String,
  val toolChoice: String = "auto" // "auto" | "required" | "none"
) {
  companion object {
    private const val TAG = "CanonicalToolCall"
    private val HEX = "0123456789abcdef"

    /**
     * Parse one provider tool_call envelope into the canonical shape.
     *
     * Expected (OpenAI / OpenRouter / NIM):
     * ```
     * {
     *   "id": "call_abc",
     *   "type": "function",
     *   "function": {
     *     "name": "system.router.inspect",
     *     "arguments": "{}"
     *   }
     * }
     * ```
     *
     * Defensive behavior:
     * - missing `id` → mint `call_<8hex>` synthetic id
     * - missing `function.name` → use "unknown" and let dispatcher reject
     * - unparsable `function.arguments` → emit empty args map, never throw
     */
    fun fromProvider(toolCall: JSONObject, source: String): CanonicalToolCall {
      val rawJson = toolCall.toString()
      val callId = toolCall.optString("id").ifBlank {
        "call_" + HEX.random(8)
      }
      val functionObj = toolCall.optJSONObject("function")
      val toolName = functionObj?.optString("name").orEmpty()
      val argsJson = functionObj?.optString("arguments").orEmpty()
      val args = parseArgs(argsJson)
      return CanonicalToolCall(
        callId = callId,
        toolName = toolName,
        args = args,
        sourceProvider = source,
        rawJson = rawJson,
        toolChoice = toolCall.optString("tool_choice", "auto")
      )
    }

    /**
     * Parse a JSON Array of tool_call envelopes into canonical list.
     * Returns empty list on null/missing — never throws, never crashes the
     * chat loop. A provider that didn't support function-calling produces
     * an empty list and the caller falls back to plain-text reply handling.
     */
    fun parseArray(toolCallsArray: JSONArray?, source: String): List<CanonicalToolCall> {
      if (toolCallsArray == null || toolCallsArray.length() == 0) return emptyList()
      val out = ArrayList<CanonicalToolCall>(toolCallsArray.length())
      for (i in 0 until toolCallsArray.length()) {
        val obj = toolCallsArray.optJSONObject(i) ?: continue
        try {
          out.add(fromProvider(obj, source))
        } catch (e: Exception) {
          Log.w(TAG, "Dropped malformed tool_call[$i] from $source: ${e.message}")
        }
      }
      return out
    }

    private fun parseArgs(raw: String): Map<String, Any> {
      if (raw.isBlank()) return emptyMap()
      return try {
        val o = JSONObject(raw)
        val map = HashMap<String, Any>(o.length())
        for (k in o.keys()) {
          map[k] = o.get(k)
        }
        map
      } catch (e: Exception) {
        Log.w(TAG, "Unparsable function arguments: ${raw.take(120)} → empty map")
        emptyMap()
      }
    }

    private fun String.random(n: Int): String {
      val sb = StringBuilder(n)
      repeat(n) { sb.append(HEX.random()) }
      return sb.toString()
    }
  }
}

/**
 * Dispatch result — the union of the executor's ToolCallRecord and the
 * cryptographic ProofReceipt. The AssistantTurn is built from this on the
 * caller side.
 */
data class DispatchResult(
  val record: ToolCallRecord?,
  val receipt: ProofReceipt?,
  val error: String? = null,
  val errorCode: String? = null  // "UNKNOWN_TOOL" | "LEASE_REQUIRED" | "POLICY_DENIAL" | "EXECUTOR_ERROR"
) {
  val isSuccess: Boolean get() = errorCode == null && record?.isSuccess == true
}

/**
 * Keeps artifact-creation goals inside WORK/Forge. A provider is never allowed
 * to turn "build a webpage/game" into a browser search merely by emitting a
 * syntactically valid android.browser.open call.
 */
internal object ToolIntentBoundary {
  private val createVerb = Regex("\\b(build|create|make|write|code|implement|develop|generate|design)\\b")
  private val artifact = Regex(
    "\\b(game|app|application|website|webpage|web\\s+page|site|page|html|css|javascript|tool|project|prototype|artifact)\\b"
  )

  fun isArtifactCreationGoal(originalOperatorRequest: String?): Boolean {
    val request = originalOperatorRequest?.lowercase()?.trim().orEmpty()
    return request.isNotBlank() && createVerb.containsMatchIn(request) && artifact.containsMatchIn(request)
  }

  /** The smallest truthful tool contract for a phone-local web artifact job. */
  fun artifactCreationToolNames(): Set<String> = setOf(
    "android.file.write",
    "android.file.read",
    "android.file.search",
    "android.artifact.preview"
  )

  fun denialReason(originalOperatorRequest: String?, call: CanonicalToolCall): String? {
    if (call.toolName != "android.browser.open" && call.toolName != "android.browser.embed") return null
    if (!isArtifactCreationGoal(originalOperatorRequest)) return null

    val reason = call.args["reason"]?.toString()?.uppercase().orEmpty()
    val mode = call.args["mode"]?.toString()?.uppercase().orEmpty()
    if (call.toolName == "android.browser.open" && reason == "DEPENDENCY_RESEARCH" && mode == "SEARCH") {
      return null
    }
    return "Artifact creation must stay in the WORK/Forge execution path. Browser access is allowed during creation only for an explicit SEARCH with reason=DEPENDENCY_RESEARCH; preview requires a verified artifact in a later step."
  }
}

/**
 * Dispatcher: takes a CanonicalToolCall, runs it through the canonical
 * pipeline, returns DispatchResult.
 *
 * The actual execution is delegated to ToolRuntimeEngine.executeTool — we
 * do not reimplement the executor, only the perimeter.
 *
 * @param engine  the runtime engine (required)
 * @param mode    current InteractionMode (drives ExecutionPolicy.decide)
 * @param lease   current ExecutionLease (null is OK for read-only tools)
 * @param actorAgent  Soul identifier for receipt provenance
 * @param isHomeOnline  home status for REMOTE_BRIDGE gate
 */
suspend fun ToolRuntimeEngine.dispatchCanonical(
  call: CanonicalToolCall,
  mode: com.example.core.model.InteractionMode,
  lease: com.example.core.model.ExecutionLease?,
  actorAgent: String = "PurpClawCore",
  isHomeOnline: Boolean = true,
  originalOperatorRequest: String? = null
): DispatchResult {
  // 1. ToolRegistry lookup — UNKNOWN_TOOL gate.
  val spec = getAvailableToolsList(isHomeOnline).firstOrNull { it.name == call.toolName }
  if (spec == null) {
    return DispatchResult(null, null, "Unknown tool: ${call.toolName}", "UNKNOWN_TOOL")
  }
  if (!spec.isEnabled) {
    return DispatchResult(null, null, "Tool ${call.toolName} is disabled", "TOOL_DISABLED")
  }

  ToolIntentBoundary.denialReason(originalOperatorRequest, call)?.let { reason ->
    return DispatchResult(null, null, reason, "INTENT_BOUNDARY_DENIAL")
  }

  // 2. PermissionGate — CHAT/WORK authority.
  val policyMode = when (mode) {
    com.example.core.model.InteractionMode.CHAT -> ExecutionPolicy.Mode.CHAT
    com.example.core.model.InteractionMode.WORK -> ExecutionPolicy.Mode.WORK
  }
  val decision = ExecutionPolicy.decide(policyMode, call.toolName)
  if (!decision.allowed) {
    return DispatchResult(null, null, decision.reason, "POLICY_DENIAL")
  }

  // 3. ExecutionLease gate — only mutating tools need a lease.
  val verdict = lifecycleFor(call.toolName, mode, lease, isHomeOnline)
  if (verdict.state != ToolLifecycleState.CALLABLE && verdict.state != ToolLifecycleState.EXECUTED) {
    return DispatchResult(null, null, verdict.reason ?: "NOT_CALLABLE", "LEASE_REQUIRED")
  }

  // 4. Executor — delegate to the engine's canonical executeTool.
  val argsJson = JSONObject(call.args as Map<*, *>).toString()
  val prevMode = currentExecutionMode
  currentExecutionMode = policyMode
  val result = try {
    executeTool(call.toolName, argsJson, actorAgent, lease, isHomeOnline)
  } finally {
    currentExecutionMode = prevMode
  }

  return DispatchResult(
    record = result.record,
    receipt = result.proofReceipt,
    error = if (result.record.isSuccess) null else result.record.error,
    errorCode = if (result.record.isSuccess) null else "EXECUTOR_ERROR"
  )
}

/**
 * Serialize a list of CanonicalToolCall into the OpenAI tools wire format
 * (what we put into the `tools:` array of the request body).
 */
fun canonicalToolsToWire(descriptors: List<com.example.core.model.ToolDescriptor>): JSONArray {
  val out = JSONArray()
  descriptors.forEach { d ->
    if (!d.isEnabled) return@forEach
    val fn = JSONObject().apply {
          put("name", canonicalToolWireName(d.name))
      put("description", d.description)
      // parametersSchema is already a Map<String, Any> → JSONObject
      put("parameters", JSONObject(d.parametersSchema as Map<*, *>))
    }
    out.put(JSONObject().apply {
      put("type", "function")
      put("function", fn)
    })
  }
  return out
}

/** Provider APIs reject dots in function names; the runtime registry does not. */
internal fun canonicalToolWireName(canonicalName: String): String =
  canonicalName.replace(".", "__")
