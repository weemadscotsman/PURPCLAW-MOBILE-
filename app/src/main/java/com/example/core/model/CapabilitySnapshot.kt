package com.example.core.model

/**
 * CAPABILITY SNAPSHOT — single immutable, self-describing view of "what is
 * callable right now" for one (mode, lease, homeStatus) triple.
 *
 * PURPOSE
 * - Eliminate the four-truth architectural smell: global registry, session
 *   toolCount=0, mode-dependent callable set, and Council-blind introspection.
 * - Provide one shape that Chat, Work, Council, Main, Child Agent, Mobile
 *   Chat, Mobile Work, Desktop Chat and Desktop Work all emit identically.
 * - Make parity a property: snapshot == f(mode, lease, homeStatus,
 *   registeredTools) regardless of caller identity.
 *
 * OWNERSHIP LAW (2026-08-26): the canonical runtime at :7780 is the
 * authority; the phone mirrors and invalidates on lease/mode/home-status
 * change. Core first, phone second.
 */

data class CapabilitySnapshot(
  val mode: InteractionMode,
  val registeredTools: List<ToolDescriptor>,
  val availableTools: List<String>,
  val permittedTools: List<String>,
  val callableTools: List<String>,
  val executedTools: List<ToolCallRecord>,
  val functions: List<NativeFunctionDescriptor>,
  val plugins: List<PluginDescriptor>,
  val agents: List<AgentDescriptor>,
  val runtimeServices: List<RuntimeServiceDescriptor>,
  val deviceCapabilities: Map<String, Any>,
  val executionLease: ExecutionLease?,
  val denialReasons: Map<String, String>,
  val homeStatus: HomeStatusSnapshot,
  val snapshotId: String,
  val capturedAtMs: Long,
  /** Bump when the snapshot schema changes; parity harness skips strict equality across versions. */
  val schemaVersion: Int = SCHEMA_VERSION
) {
  companion object {
    const val SCHEMA_VERSION = 1
  }
}

/**
 * Tool descriptor emitted to wire `tools: [...]` arrays for OpenAI /
 * OpenRouter / NIM function-calling. Schema is OpenAI-compatible JSON Schema.
 */
data class ToolDescriptor(
  val name: String,
  val displayName: String,
  val description: String,
  val affinity: ToolAffinity,
  val isEnabled: Boolean,
  val requiresLease: Boolean,
  val category: String,         // "read" | "mutate" | "system" | "introspection" | "remote"
  val parametersSchema: Map<String, Any>   // OpenAI function parameter JSON Schema
)

/** Local JVM-side function callable by the model (TTS, vibrate, flashlight, ...). */
data class NativeFunctionDescriptor(
  val name: String,
  val signature: String,
  val returnsType: String,
  val requiresLease: Boolean,
  val sideEffectful: Boolean
)

/** Plugin descriptor (currently empty in this build, wired for future extension). */
data class PluginDescriptor(
  val id: String,
  val name: String,
  val version: String,
  val capabilities: List<String>,
  val source: String
)

/** Agent descriptor — populated from local agentTower or home roster mirror. */
data class AgentDescriptor(
  val id: String,
  val name: String,
  val division: String,
  val model: String,
  val tools: List<String>,
  val lastSeenMs: Long
)

/** Runtime service descriptor (HomeRuntimeBridge, ProviderRouter, CognitiveSpine, ...). */
data class RuntimeServiceDescriptor(
  val id: String,
  val port: Int?,
  val status: String,    // "ONLINE" | "DEGRADED" | "OFFLINE"
  val lastHeartbeatMs: Long
)

/**
 * Slim home-status snapshot. Mirrored from HomeRuntimeBridge.probeHealth().
 * NEVER fabricated on-device — if the home runtime did not answer, this is
 * the "offline" shape with raw error attached.
 */
data class HomeStatusSnapshot(
  val online: Boolean,
  val latencyMs: Long,
  val runtimeId: String?,
  val status: String?,
  val agentCount: Int,
  val error: String? = null
)