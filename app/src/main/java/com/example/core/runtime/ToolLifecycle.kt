package com.example.core.runtime

import com.example.core.model.ExecutionLease
import com.example.core.model.InteractionMode
import com.example.core.model.ToolAffinity
import com.example.core.model.ToolDescriptor

/**
 * TOOL LIFECYCLE — five-state model answering "is this tool callable now?"
 *
 * REGISTERED → in the engine's master list
 * AVAILABLE  → home-online is satisfied OR affinity is local
 * PERMITTED  → ExecutionPolicy.decide() allows it for the current mode
 * CALLABLE   → PERMITTED AND (no lease required OR active lease)
 * EXECUTED   → CALLABLE AND receipt emitted for this invocation
 *
 * The denial reason returned alongside the state is the reason the FIRST
 * failed gate produced — never an aggregate. This powers the snapshot's
 * `denialReasons` map and the parity test's NO_EXECUTION_LEASE assertion.
 */

enum class ToolLifecycleState {
  REGISTERED, AVAILABLE, PERMITTED, CALLABLE, EXECUTED
}

/** Pair of (state, denialReason). reason == null means state passed every gate. */
data class LifecycleVerdict(
  val state: ToolLifecycleState,
  val reason: String? = null
)

/**
 * Extension on ToolRuntimeEngine: classify one tool name against the current
 * (mode, lease) tuple, with home status as a side input.
 *
 * Caller pattern:
 * ```
 * candidates.filter { name ->
 *   engine.lifecycleFor(name, mode, lease).state == ToolLifecycleState.CALLABLE
 * }
 * ```
 */
fun ToolRuntimeEngine.lifecycleFor(
  toolName: String,
  mode: InteractionMode,
  lease: ExecutionLease?,
  isHomeOnline: Boolean = homeOnlineResolver?.invoke() ?: false
): LifecycleVerdict {
  val tools = getAvailableToolsList(isHomeOnline)
  val spec = tools.firstOrNull { it.name == toolName }
    ?: return LifecycleVerdict(ToolLifecycleState.REGISTERED, "UNKNOWN_TOOL")

  // Not REGISTERED in the snapshot's sense if disabled.
  if (!spec.isEnabled) return LifecycleVerdict(ToolLifecycleState.REGISTERED, "DISABLED")

  // AVAILABLE: requires home-online for REMOTE_BRIDGE affinity.
  if (spec.affinity == ToolAffinity.REMOTE_BRIDGE && !isHomeOnline) {
    return LifecycleVerdict(ToolLifecycleState.REGISTERED, "HOME_OFFLINE")
  }

  // PERMITTED: ExecutionPolicy.decide (CHAT/WORK gate).
  val policyMode = when (mode) {
    InteractionMode.CHAT -> ExecutionPolicy.Mode.CHAT
    InteractionMode.WORK -> ExecutionPolicy.Mode.WORK
  }
  val decision = ExecutionPolicy.decide(policyMode, toolName)
  if (!decision.allowed) {
    return LifecycleVerdict(ToolLifecycleState.AVAILABLE, decision.reason)
  }

  // CALLABLE: lease check (only if requiresLease=true).
  val requiresLease = isMutatingTool(toolName)
  if (requiresLease) {
    val active = lease?.isActive == true && lease.expiresAtMs > System.currentTimeMillis()
    if (!active) {
      return LifecycleVerdict(ToolLifecycleState.PERMITTED, "LEASE_REQUIRED")
    }
  }

  return LifecycleVerdict(ToolLifecycleState.CALLABLE, null)
}

/**
 * Mutating-tool allowlist. Tools here require an active ExecutionLease to
 * advance from PERMITTED → CALLABLE. Pure read tools (system.*, status
 * queries) are intentionally excluded — they stay CALLABLE without a lease.
 *
 * Keep this conservative. If in doubt, exclude.
 */
private fun isMutatingTool(name: String): Boolean {
  val mutatingPrefixes = listOf(
    "android.notification.send",
    "android.camera.capture",
    "android.tts.speak",
    "android.flashlight",
    "android.app.open",
    "android.settings.panel",
    "android.vibrate",
    "android.clipboard.write",
    "android.file.write",
    "android.artifact.preview",
    "android.browser.embed",
    "system.agent.delegate"
  )
  return name in mutatingPrefixes
}

/** Convenience: ToolDescriptor from ToolSpec + the mutating annotation. */
fun ToolSpec.toDescriptor(): ToolDescriptor = ToolDescriptor(
  name = name,
  displayName = displayName,
  description = description,
  affinity = affinity,
  isEnabled = isEnabled,
  requiresLease = isMutatingTool(name),
  category = when {
    name.startsWith("system.") -> "introspection"
    name.startsWith("desktop.") -> "remote"
    name.startsWith("android.") -> "mutate"
    name.startsWith("ui.") -> "read"
    else -> "read"
  },
  parametersSchema = parametersSchemaFor(name)
)

/**
 * Hand-written JSON Schemas for the 12 introspection tools. No-arg tools
 * emit an empty `properties` object; tools with args declare them.
 */
private fun parametersSchemaFor(toolName: String): Map<String, Any> = when (toolName) {
  "android.file.write" -> mapOf(
    "type" to "object",
    "properties" to mapOf(
      "filename" to mapOf("type" to "string"),
      "content" to mapOf("type" to "string")
    ),
    "required" to listOf("filename", "content")
  )
  "android.file.read", "android.artifact.preview" -> mapOf(
    "type" to "object",
    "properties" to mapOf("filename" to mapOf("type" to "string")),
    "required" to listOf("filename")
  )
  "android.file.search" -> mapOf(
    "type" to "object",
    "properties" to mapOf("query" to mapOf("type" to "string")),
    "required" to listOf("query")
  )
  "android.browser.embed" -> mapOf(
    "type" to "object",
    "properties" to mapOf("url" to mapOf("type" to "string")),
    "required" to listOf("url")
  )
  "android.browser.open" -> mapOf(
    "type" to "object",
    "properties" to mapOf(
      "target" to mapOf("type" to "string"),
      "browser" to mapOf("type" to "string"),
      "mode" to mapOf("type" to "string"),
      "reason" to mapOf("type" to "string")
    ),
    "required" to listOf("target")
  )
  "system.tools.describe" -> mapOf(
    "type" to "object",
    "properties" to mapOf("name" to mapOf("type" to "string")),
    "required" to listOf("name")
  )
  "system.router.trace" -> mapOf(
    "type" to "object",
    "properties" to mapOf("sessionId" to mapOf("type" to "string")),
    "required" to listOf("sessionId")
  )
  "system.events.query" -> mapOf(
    "type" to "object",
    "properties" to mapOf(
      "since" to mapOf("type" to "integer"),
      "kind" to mapOf("type" to "string"),
      "sessionId" to mapOf("type" to "string")
    )
  )
  "system.agent.inspect" -> mapOf(
    "type" to "object",
    "properties" to mapOf("agentId" to mapOf("type" to "string")),
    "required" to listOf("agentId")
  )
  "system.agent.delegate" -> mapOf(
    "type" to "object",
    "properties" to mapOf(
      "agentId" to mapOf("type" to "string"),
      "task" to mapOf("type" to "string"),
      "turnId" to mapOf("type" to "string")
    ),
    "required" to listOf("agentId", "task")
  )
  else -> mapOf("type" to "object", "properties" to emptyMap<String, Any>())
}
