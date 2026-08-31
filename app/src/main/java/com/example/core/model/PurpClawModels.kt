package com.example.core.model

enum class InteractionMode(val label: String, val iconName: String, val description: String) {
  CHAT("CHAT", "chat", "Talk only. Zero device effects."),
  WORK("WORK", "bolt", "Full authority. Tool calling, function calling, complete device control. Operator's word is the permission.")
}

enum class MeshStatus(val label: String, val colorCode: Long, val badge: String) {
  HOME_ONLINE("HOME PC", 0xFF10B981, "🟢"),
  SOVEREIGN_LOCAL("PHONE SOVEREIGN", 0xFFA855F7, "🟣"),
  HYBRID_DEGRADED("HYBRID / MESH", 0xFFF59E0B, "🟡"),
  DISCOVERING("DISCOVERING", 0xFF6B7280, "⚪")
}

enum class CompanionPetState(val label: String, val animationEmoji: String) {
  IDLE("Standing By", "🟣"),
  LISTENING("Listening...", "👂"),
  THINKING("Deep Reasoning...", "🧠"),
  PLANNING("Building Graph...", "📐"),
  TOOL_CALL("Invoking Tool...", "⚡"),
  RUNNING("Executing Action...", "⚙️"),
  FIXING("Self-Healing...", "🔧"),
  REPLYING("Synthesizing...", "💬"),
  SUCCESS("Verified Complete", "✅"),
  ERROR("Interrupted / Error", "⚠️"),
  RETRY("Retrying...", "🔄")
}

enum class MemoryLayer(val displayName: String, val number: Int, val description: String) {
  EPISODIC("Episodic Memory", 1, "Session history, conversation turns, timeline interactions"),
  SEMANTIC("Semantic Memory", 2, "Factual knowledge, entity relations, concepts"),
  PROCEDURAL("Procedural Memory", 3, "Execution recipes, tool runbooks, workflows"),
  SYMBOLIC("Symbolic Memory", 4, "System rules, identity bounds, security schemas"),
  TEMPORAL("Temporal Memory", 5, "Schedules, time-bound context, cron triggers"),
  COUNTERFACTUAL("Counterfactual Memory", 6, "What-if scenarios, simulated outcomes, fallbacks"),
  AFFECTIVE("Affective Memory", 7, "Operator rapport, tone preference, urgency calibration")
}

enum class ToolAffinity {
  ANDROID_NATIVE,
  PORTABLE,
  REMOTE_BRIDGE,
  UNAVAILABLE_ANDROID
}

data class MeshNode(
  val nodeId: String,
  val name: String,
  val platform: String,
  val role: String,
  val online: Boolean,
  val address: String,
  val capabilities: List<String>,
  val activeModels: List<String>,
  val memoryVersion: Long,
  val lastHeartbeatMs: Long,
  val isHomeNode: Boolean = false
)

data class ToolCallRecord(
  val id: String,
  val toolName: String,
  val affinity: ToolAffinity,
  val arguments: String,
  val output: String,
  val isSuccess: Boolean,
  val durationMs: Long,
  val evidenceHash: String,
  val error: String? = null
)

data class ExecutionLease(
  val leaseId: String,
  val operatorGranted: Boolean,
  val allowedCapabilities: List<String>,
  val grantedAtMs: Long,
  val expiresAtMs: Long,
  val isActive: Boolean
)

enum class MediaKind { IMAGE, VIDEO, FILE }

data class MediaAttachment(
  val kind: MediaKind,
  val uri: String,
  val mime: String? = null,
  val label: String? = null,
  /** Stable phone-local handle supplied to tools/models; never replaced silently. */
  val resourceUri: String? = null,
  val sha256: String? = null,
  val sizeBytes: Long? = null,
  val width: Int? = null,
  val height: Int? = null,
  val source: String? = null
)

data class TurnRecord(
  val id: String,
  val sessionId: String,
  val parentEventId: String?,
  val nodeId: String,
  val timestamp: Long,
  val sequence: Int,
  val role: String, // "user", "assistant", "system"
  val mode: InteractionMode,
  val content: String,
  val reasoning: String? = null,
  val toolCalls: List<ToolCallRecord> = emptyList(),
  val proofReceipt: ProofReceipt? = null,
  val leaseId: String? = null,
  val fullSystemScope: Boolean = false,
  val tokenCount: Int = 0,
  val latencyMs: Long = 0,
  val providerModel: String = "AUTO",
  val routingReceipt: RoutingReceipt? = null,
  // Live activity — populated while the turn is in flight so the chat card
  // can show thinking/tool state instead of a static box.
  val isStreaming: Boolean = false,
  val activityStatus: String? = null,
  // Inline media rendered natively inside the bubble (images, video, file chips).
  val mediaAttachments: List<MediaAttachment> = emptyList()
)

data class AgentRecord(
  val id: String,
  val name: String,
  val soul: String,
  val division: String,
  val role: String,
  val declaredCaps: List<String>,
  val avatarColor: Long,
  val status: String, // "idle", "busy", "paused"
  val currentTask: String? = null,
  val memoryScope: String,
  val reputationScore: Float = 0.98f,
  val totalMissions: Int = 12,
  /** STEP 13 (2026-08-27) — full soul identity for agent-as-seat. Defaults keep old constructors working. */
  val wants: String = "",
  val needs: String = "",
  val goals: String = "",
  val wishes: String = "",
  val soulDescription: String = ""
)

data class MissionStep(
  val id: String,
  val title: String,
  val assignedAgent: String,
  val status: String, // "pending", "in_progress", "completed", "failed"
  val outputEvidence: String? = null
)

data class MissionRecord(
  val id: String,
  val title: String,
  val goal: String,
  val mode: InteractionMode,
  val status: String, // "active", "completed", "paused", "draft"
  val steps: List<MissionStep>,
  val progress: Float,
  val activeAgents: List<String>,
  val artifacts: List<String>,
  val createdAt: Long
)

data class CouncilRecord(
  val id: String,
  val issue: String,
  val domain: String,
  val chairAgent: String,
  val members: List<String>,
  val votesFor: Int,
  val votesAgainst: Int,
  val dissentSummary: String,
  val consensusAction: String,
  val timestamp: Long
)

data class IntakeCapsule(
  val id: String,
  val sourceName: String,
  val mimeType: String,
  val sizeBytes: Long,
  val filesCount: Int,
  val safeTreeManifest: List<String>,
  val checksumSha256: String,
  val coverageScore: Float,
  val inspectedAt: Long
)

data class PurpSnapshot(
  val id: String,
  val label: String,
  val reason: String,
  val beforeStateHash: String,
  val afterStateHash: String,
  val timestamp: Long,
  val isRestorable: Boolean = true
)

data class VaultItem(
  val id: String,
  val keyName: String,
  val maskedValue: String,
  val category: String,
  val lastAccessedMs: Long,
  val isEncryptedInKeystore: Boolean = true
)

// ==========================================
// PURPCLAW SHARED MISSION CANVAS MODELS
// ==========================================

data class CanvasRecord(
  val canvasId: String,
  val workspaceId: String,
  val title: String,
  val description: String,
  val status: String, // "ACTIVE", "ARCHIVED"
  val linkedSessionIds: List<String>,
  val activeMissionId: String? = null,
  val activeNodeCount: Int = 0,
  val createdAt: Long,
  val updatedAt: Long,
  val logicalClock: Long = 1L
)

data class CanvasSessionLink(
  val sessionId: String,
  val title: String,
  val domain: String, // e.g. "Android UI", "Core Runtime", "Voice/Vision", "Provider Routing", "Security Audit"
  val assignedAgentId: String,
  val activeNodeId: String,
  val turnCount: Int,
  val lastActivityMs: Long,
  val status: String = "active"
)

enum class CanvasNodeType(val label: String, val iconEmoji: String, val colorCode: Long) {
  GOAL("User Goal", "🎯", 0xFF8B5CF6),
  TASK("Task Execution", "⚙️", 0xFF38BDF8),
  SESSION("Session Context", "💬", 0xFF22D3EE),
  AGENT("Agent Operator", "🤖", 0xFFA855F7),
  SWARM("Specialist Swarm", "🐝", 0xFFEC4899),
  TOOL("Capability Tool", "⚡", 0xFFF59E0B),
  APPROVAL("Human Approval", "🛡️", 0xFFE11D48),
  CONDITION("Branch Condition", "🔀", 0xFF6366F1),
  DECISION("Decision Gate", "⚖️", 0xFF10B981),
  ARTIFACT("Artifact / Build", "📦", 0xFF14B8A6),
  FILE("Source / Spec File", "📄", 0xFF64748B),
  MODEL("Provider / LLM", "🧠", 0xFF8B5CF6),
  REMOTE_NODE("Remote Workstation", "🖥️", 0xFF10B981),
  VERIFICATION("OMNI Verification", "🔎", 0xFF059669),
  MEMORY("Memory Lineage", "🧬", 0xFF9333EA)
}

enum class CanvasNodeStatus(val label: String, val colorCode: Long) {
  TODO("TODO", 0xFF71717A),
  RUNNING("RUNNING", 0xFF8B5CF6),
  BLOCKED("BLOCKED", 0xFFEF4444),
  WAITING_FOR_APPROVAL("APPROVAL REQUIRED", 0xFFF59E0B),
  WAITING_FOR_HOME("WAITING FOR HOME PC", 0xFFE11D48),
  VERIFY("VERIFYING", 0xFF10B981),
  DONE("COMPLETE", 0xFF059669),
  FAILED("FAILED", 0xFFDC2626)
}

data class CanvasNode(
  val nodeId: String,
  val canvasId: String,
  val title: String,
  val type: CanvasNodeType,
  val status: CanvasNodeStatus,
  val owner: String,
  val assignedAgent: String,
  val assignedNode: String, // "Home PC", "Android Phone", "Remote Worker"
  val providerModel: String = "PENDING_CORE_REPORT",
  val allowedTools: List<String> = emptyList(),
  val executionAuthority: String = "NONE", // NONE, PENDING_LEASE, ARMED_LEASE, SYSTEM_GRANTED
  val inputs: List<String> = emptyList(),
  val outputs: List<String> = emptyList(),
  val dependencies: List<String> = emptyList(), // parent node IDs
  val posX: Float = 0f,
  val posY: Float = 0f,
  val durationMs: Long = 0L,
  val evidenceDigest: String? = null,
  val resultSummary: String? = null,
  val retryCount: Int = 0,
  val isRemoteOnly: Boolean = false,
  val filesChanged: Int = 0,
  val callCount: Int = 0
)

data class CanvasEdge(
  val edgeId: String,
  val canvasId: String,
  val fromNodeId: String,
  val toNodeId: String,
  val label: String = "",
  val isDependency: Boolean = true
)

data class CanvasArtifact(
  val artifactId: String,
  val canvasId: String,
  val title: String,
  val uri: String, // e.g. "artifact://android/build/app-release.apk"
  val type: String, // "BUILD_APK", "CODE", "LOGS", "RECEIPT", "SCREENSHOT", "REPORT"
  val sizeBytes: Long,
  val checksumSha256: String,
  val originSessionId: String,
  val originNodeId: String,
  val versionLineage: String,
  val createdAt: Long
)

data class CanvasPresence(
  val presenceId: String,
  val actorId: String,
  val name: String,
  val type: String, // "OPERATOR", "HOME_PC", "AGENT", "REMOTE_NODE", "OMNI"
  val nodePlatform: String, // "Android Phone", "Windows Workstation (Alpha-9)", "Cloud Swarm"
  val state: String, // "online", "executing", "waiting", "idle", "verifying"
  val currentTask: String,
  val lastHeartbeatMs: Long,
  val latencyMs: Long,
  val capabilities: List<String>
)

enum class CanvasEventType {
  CANVAS_NODE_CREATED,
  CANVAS_NODE_MOVED,
  TASK_ASSIGNED,
  SESSION_LINKED,
  ARTIFACT_ADDED,
  AGENT_STARTED,
  EXECUTION_STARTED,
  EXECUTION_STOPPED,
  APPROVAL_REQUESTED,
  APPROVAL_GRANTED,
  APPROVAL_REJECTED,
  WORKFLOW_EDGE_CREATED,
  MISSION_COMPLETED,
  HANDOFF_COMMITTED,
  CONFLICT_RESOLVED,
  REMOTE_STEER_APPLIED
}

data class CanvasEvent(
  val eventId: String,
  val canvasId: String,
  val nodeId: String? = null,
  val actor: String,
  val sourceNode: String,
  val sessionId: String? = null,
  val parentEventId: String? = null,
  val timestamp: Long,
  val logicalClock: Long,
  val operation: CanvasEventType,
  val payloadSummary: String,
  val payloadHash: String
)

data class SessionHandoffCapsule(
  val handoffId: String,
  val canvasId: String,
  val sourceSessionId: String,
  val targetSessionId: String,
  val sourceNode: String,
  val targetNode: String,
  val objective: String,
  val currentState: String,
  val decisions: List<String>,
  val unresolvedQuestions: List<String>,
  val artifacts: List<String>,
  val relevantMemory: List<String>,
  val evidence: List<String>,
  val activeAgents: List<String>,
  val pendingApprovals: List<String>,
  val executionState: String,
  val nextRecommendedAction: String,
  val timestamp: Long
)

data class CanvasConflict(
  val conflictId: String,
  val canvasId: String,
  val nodeId: String,
  val nodeTitle: String,
  val fieldKey: String,
  val pcValue: String,
  val phoneValue: String,
  val pcActor: String,
  val phoneActor: String,
  val pcTimestamp: Long,
  val phoneTimestamp: Long,
  val status: String = "PENDING" // "PENDING", "RESOLVED_PC", "RESOLVED_PHONE", "RESOLVED_MERGE"
)

data class CrossSessionMessage(
  val messageId: String,
  val canvasId: String,
  val sourceSessionId: String,
  val targetSessionId: String,
  val senderActor: String,
  val targetType: String, // "ALL", "CURRENT", "SESSION", "AGENT", "SWARM", "MISSION", "NODE", "PC", "PHONE"
  val content: String,
  val isDecision: Boolean = false,
  val isExecutionHandoff: Boolean = false,
  val boundedContextNodeIds: List<String> = emptyList(),
  val boundedContextArtifactIds: List<String> = emptyList(),
  val timestamp: Long = System.currentTimeMillis()
)
