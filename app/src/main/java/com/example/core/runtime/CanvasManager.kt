package com.example.core.runtime

import com.example.core.database.CanvasDao
import com.example.core.model.CanvasArtifact
import com.example.core.model.CanvasConflict
import com.example.core.model.CanvasEdge
import com.example.core.model.CanvasEvent
import com.example.core.model.CanvasEventType
import com.example.core.model.CanvasNode
import com.example.core.model.CanvasNodeStatus
import com.example.core.model.CanvasNodeType
import com.example.core.model.CanvasPresence
import com.example.core.model.CanvasRecord
import com.example.core.model.CanvasSessionLink
import com.example.core.model.CrossSessionMessage
import com.example.core.model.SessionHandoffCapsule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

enum class CanvasViewMode(val label: String, val iconEmoji: String) {
  GRAPH("Graph (DAG)", "📐"),
  BOARD("Mission Board", "📋"),
  CHAT("Cross-Session Chat", "💬"),
  TIMELINE("Event Timeline", "⏱️"),
  PRESENCE("Live Nodes", "👥"),
  ARTIFACTS("Artifacts", "📦"),
  RECONCILIATION("Handoff & Merge", "⚖️")
}

class CanvasManager(
  private val canvasDao: CanvasDao,
  private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

  private var logicalClockCounter: Long = 1042L

  private val _activeCanvasId = MutableStateFlow("canvas_ship_android_01")
  val activeCanvasId: StateFlow<String> = _activeCanvasId.asStateFlow()

  private val _activeViewMode = MutableStateFlow(CanvasViewMode.GRAPH)
  val activeViewMode: StateFlow<CanvasViewMode> = _activeViewMode.asStateFlow()

  private val _selectedNodeIds = MutableStateFlow<Set<String>>(emptySet())
  val selectedNodeIds: StateFlow<Set<String>> = _selectedNodeIds.asStateFlow()

  private val _selectedArtifactIds = MutableStateFlow<Set<String>>(emptySet())
  val selectedArtifactIds: StateFlow<Set<String>> = _selectedArtifactIds.asStateFlow()

  // In-memory reactive state synchronized with DB
  private val _canvases = MutableStateFlow<List<CanvasRecord>>(
    listOf(
      CanvasRecord(
        canvasId = "canvas_ship_android_01",
        workspaceId = "ws_purpclaw_prime",
        title = "Ship Android PurpClaw Sovereign",
        description = "Collaborative cross-node canvas for Android UI, Core Runtime, Voice/Vision, Provider Routing, and Security Audit.",
        status = "ACTIVE",
        linkedSessionIds = listOf("ses_ui_01", "ses_core_02", "ses_voice_03", "ses_routing_04", "ses_security_05"),
        activeMissionId = "mission_apk_mvp",
        activeNodeCount = 7,
        createdAt = System.currentTimeMillis() - 86400000L,
        updatedAt = System.currentTimeMillis() - 60000L,
        logicalClock = 1042L
      ),
      CanvasRecord(
        canvasId = "canvas_sovereign_failover_02",
        workspaceId = "ws_purpclaw_prime",
        title = "Home → Phone Sovereign Failover",
        description = "Automated state snapshotting and local takeover workflow test suite.",
        status = "ACTIVE",
        linkedSessionIds = listOf("ses_core_02", "ses_security_05"),
        activeMissionId = "mission_failover_drill",
        activeNodeCount = 4,
        createdAt = System.currentTimeMillis() - 172800000L,
        updatedAt = System.currentTimeMillis() - 1200000L,
        logicalClock = 890L
      )
    )
  )
  val canvases: StateFlow<List<CanvasRecord>> = _canvases.asStateFlow()

  private val _linkedSessions = MutableStateFlow<List<CanvasSessionLink>>(
    listOf(
      CanvasSessionLink("ses_ui_01", "Android UI & Theme", "Android UI", "Hermes Codex", "node_build_apk", 42, System.currentTimeMillis() - 120000),
      CanvasSessionLink("ses_core_02", "Core Event Spine & Mesh", "Core Runtime", "PurpAngolin", "node_verify_omni", 89, System.currentTimeMillis() - 30000),
      CanvasSessionLink("ses_voice_03", "Acoustic TTS & Audio", "Voice/Vision", "Kokoro Synth", "node_voice_audit", 18, System.currentTimeMillis() - 400000),
      CanvasSessionLink("ses_routing_04", "Provider Failover Benchmark", "Provider Routing", "Hermes Codex", "node_provider_bench", 31, System.currentTimeMillis() - 600000),
      CanvasSessionLink("ses_security_05", "Audit Proof & Keystore", "Security Audit", "OMNI Verifier", "node_sec_audit", 24, System.currentTimeMillis() - 800000)
    )
  )
  val linkedSessions: StateFlow<List<CanvasSessionLink>> = _linkedSessions.asStateFlow()

  private val _nodes = MutableStateFlow<List<CanvasNode>>(
    listOf(
      CanvasNode(
        nodeId = "node_user_goal",
        canvasId = "canvas_ship_android_01",
        title = "User Goal: Sovereign Android MVP",
        type = CanvasNodeType.GOAL,
        status = CanvasNodeStatus.DONE,
        owner = "Operator (You)",
        assignedAgent = "PurpAngolin",
        assignedNode = "Android Phone",
        providerModel = "demo/unrouted",
        allowedTools = listOf("read_file", "search_workspace"),
        executionAuthority = "SYSTEM_GRANTED",
        inputs = listOf("Mobile Spec v2", "A2A Protocol"),
        outputs = listOf("Architecture Blueprint"),
        dependencies = emptyList(),
        posX = 40f,
        posY = 60f,
        durationMs = 1200L,
        resultSummary = "Specs decomposed into 5 parallel sub-sessions."
      ),
      CanvasNode(
        nodeId = "node_research_hermes",
        canvasId = "canvas_ship_android_01",
        title = "Research Provider Routing",
        type = CanvasNodeType.TASK,
        status = CanvasNodeStatus.DONE,
        owner = "Hermes Codex",
        assignedAgent = "Hermes Codex",
        assignedNode = "Home PC",
        providerModel = "OpenRouter / minimax-01",
        allowedTools = listOf("network_bench", "list_providers"),
        executionAuthority = "ARMED_LEASE",
        inputs = listOf("node_user_goal"),
        outputs = listOf("provider_bench.json"),
        dependencies = listOf("node_user_goal"),
        posX = 40f,
        posY = 190f,
        durationMs = 45000L,
        evidenceDigest = "Latency Matrix: OpenRouter-free 240ms, MiniMax 310ms, Local 18ms",
        resultSummary = "Determined SMART AUTO failover cascade with zero API leak."
      ),
      CanvasNode(
        nodeId = "node_build_apk",
        canvasId = "canvas_ship_android_01",
        title = "APK Builder (Gradle Compile)",
        type = CanvasNodeType.TASK,
        status = CanvasNodeStatus.RUNNING,
        owner = "Builder Node",
        assignedAgent = "Builder Agent",
        assignedNode = "Home PC",
        providerModel = "OpenRouter / deepseek-coder",
        allowedTools = listOf("gradle_assemble", "ksp_process", "dex_opt"),
        executionAuthority = "ARMED_LEASE",
        inputs = listOf("node_research_hermes"),
        outputs = listOf("app-release.apk"),
        dependencies = listOf("node_research_hermes"),
        posX = 40f,
        posY = 330f,
        durationMs = 180000L,
        isRemoteOnly = true,
        filesChanged = 14,
        callCount = 38,
        evidenceDigest = "Task :app:assembleDebug -> compiling Kotlin Multiplatform sources",
        resultSummary = "Compiling applet release binary with full Room schema v2."
      ),
      CanvasNode(
        nodeId = "node_device_test",
        canvasId = "canvas_ship_android_01",
        title = "Android Native Device Test",
        type = CanvasNodeType.TASK,
        status = CanvasNodeStatus.WAITING_FOR_APPROVAL,
        owner = "Operator (You)",
        assignedAgent = "PurpAngolin",
        assignedNode = "Android Phone",
        providerModel = "demo/unrouted",
        allowedTools = listOf("run_instrumentation", "keystore_sign"),
        executionAuthority = "PENDING_LEASE",
        inputs = listOf("node_build_apk"),
        outputs = listOf("device_telemetry.log"),
        dependencies = listOf("node_build_apk"),
        posX = 40f,
        posY = 470f,
        durationMs = 0L,
        resultSummary = "Waiting for operator arm lease gesture on mobile device."
      ),
      CanvasNode(
        nodeId = "node_voice_audit",
        canvasId = "canvas_ship_android_01",
        title = "Kokoro Voice TTS Audit",
        type = CanvasNodeType.VERIFICATION,
        status = CanvasNodeStatus.DONE,
        owner = "Kokoro Synth",
        assignedAgent = "Kokoro Synth",
        assignedNode = "Home PC",
        providerModel = "Local Acoustic ONNX",
        allowedTools = listOf("synthesize_audio", "verify_checksum"),
        executionAuthority = "ARMED_LEASE",
        inputs = listOf("node_user_goal"),
        outputs = listOf("voice_sample_sha256"),
        dependencies = listOf("node_user_goal"),
        posX = 260f,
        posY = 190f,
        durationMs = 12000L,
        isRemoteOnly = true,
        evidenceDigest = "Acoustic model SHA256 matches Keystore: sha256_e82c...10f4",
        resultSummary = "16kHz real-time voice streaming verified with 45ms time-to-first-byte."
      ),
      CanvasNode(
        nodeId = "node_sec_audit",
        canvasId = "canvas_ship_android_01",
        title = "Hardware Keystore & Security Audit",
        type = CanvasNodeType.DECISION,
        status = CanvasNodeStatus.DONE,
        owner = "OMNI Verifier",
        assignedAgent = "OMNI Verifier",
        assignedNode = "Android Phone",
        providerModel = "Local Crypto Gate",
        allowedTools = listOf("check_keystore_keys", "audit_merkle_root"),
        executionAuthority = "SYSTEM_GRANTED",
        inputs = listOf("node_voice_audit"),
        outputs = listOf("security_receipt.proof"),
        dependencies = listOf("node_voice_audit"),
        posX = 260f,
        posY = 330f,
        durationMs = 3400L,
        evidenceDigest = "Merkle root validated: 0x9f8b72...a011",
        resultSummary = "Hardware Master Key encrypted securely in Android KeyStore."
      ),
      CanvasNode(
        nodeId = "node_verify_omni",
        canvasId = "canvas_ship_android_01",
        title = "OMNI Consensus Verification",
        type = CanvasNodeType.VERIFICATION,
        status = CanvasNodeStatus.TODO,
        owner = "OMNI Verifier",
        assignedAgent = "OMNI Verifier",
        assignedNode = "Android Phone",
        providerModel = "demo/unrouted",
        allowedTools = listOf("emit_cryptographic_receipt", "sign_merkle_proof"),
        executionAuthority = "PENDING_LEASE",
        inputs = listOf("node_device_test", "node_sec_audit"),
        outputs = listOf("release_receipt.sig"),
        dependencies = listOf("node_device_test", "node_sec_audit"),
        posX = 150f,
        posY = 600f,
        durationMs = 0L,
        resultSummary = "Queued for final cryptographic signing once device test passes."
      )
    )
  )
  val nodes: StateFlow<List<CanvasNode>> = _nodes.asStateFlow()

  private val _edges = MutableStateFlow<List<CanvasEdge>>(
    listOf(
      CanvasEdge("e1", "canvas_ship_android_01", "node_user_goal", "node_research_hermes", "spec -> research"),
      CanvasEdge("e2", "canvas_ship_android_01", "node_research_hermes", "node_build_apk", "specs -> compile"),
      CanvasEdge("e3", "canvas_ship_android_01", "node_build_apk", "node_device_test", "apk -> device"),
      CanvasEdge("e4", "canvas_ship_android_01", "node_user_goal", "node_voice_audit", "audio spec -> tts"),
      CanvasEdge("e5", "canvas_ship_android_01", "node_voice_audit", "node_sec_audit", "tts -> keystore audit"),
      CanvasEdge("e6", "canvas_ship_android_01", "node_device_test", "node_verify_omni", "test passes -> omni"),
      CanvasEdge("e7", "canvas_ship_android_01", "node_sec_audit", "node_verify_omni", "audit passes -> omni")
    )
  )
  val edges: StateFlow<List<CanvasEdge>> = _edges.asStateFlow()

  private val _artifacts = MutableStateFlow<List<CanvasArtifact>>(
    listOf(
      CanvasArtifact(
        artifactId = "art_apk_01",
        canvasId = "canvas_ship_android_01",
        title = "app-release.apk (v1.0.4-rc)",
        uri = "artifact://android/build/app-release.apk",
        type = "BUILD_APK",
        sizeBytes = 18450200L,
        checksumSha256 = "sha256_b4a8...d9e1",
        originSessionId = "ses_ui_01",
        originNodeId = "node_build_apk",
        versionLineage = "v1.0.3 -> v1.0.4-rc",
        createdAt = System.currentTimeMillis() - 300000L
      ),
      CanvasArtifact(
        artifactId = "art_provider_bench",
        canvasId = "canvas_ship_android_01",
        title = "provider_bench_results.json",
        uri = "artifact://reports/provider_bench.json",
        type = "REPORT",
        sizeBytes = 45200L,
        checksumSha256 = "sha256_e1c9...33aa",
        originSessionId = "ses_routing_04",
        originNodeId = "node_research_hermes",
        versionLineage = "v1.0",
        createdAt = System.currentTimeMillis() - 1200000L
      ),
      CanvasArtifact(
        artifactId = "art_omni_sig",
        canvasId = "canvas_ship_android_01",
        title = "omni_merkle_proof.sig",
        uri = "artifact://proofs/omni_merkle_proof.sig",
        type = "RECEIPT",
        sizeBytes = 1024L,
        checksumSha256 = "sha256_9f8b...a011",
        originSessionId = "ses_security_05",
        originNodeId = "node_sec_audit",
        versionLineage = "genesis_block",
        createdAt = System.currentTimeMillis() - 800000L
      ),
      CanvasArtifact(
        artifactId = "art_log_gradle",
        canvasId = "canvas_ship_android_01",
        title = "gradle_build_telemetry.log",
        uri = "artifact://logs/gradle_build.log",
        type = "LOGS",
        sizeBytes = 142000L,
        checksumSha256 = "sha256_77c3...412d",
        originSessionId = "ses_ui_01",
        originNodeId = "node_build_apk",
        versionLineage = "build_run_47",
        createdAt = System.currentTimeMillis() - 90000L
      )
    )
  )
  val artifacts: StateFlow<List<CanvasArtifact>> = _artifacts.asStateFlow()

  private val _presenceList = MutableStateFlow<List<CanvasPresence>>(
    listOf(
      CanvasPresence("p1", "usr_operator", "Operator (You)", "OPERATOR", "Android Phone", "online", "Reviewing Shared Mission Canvas", System.currentTimeMillis(), 4L, listOf("Lease Authorization", "Visual Steering", "Handoff")),
      CanvasPresence("p2", "node_home_pc", "Home Workstation Alpha-9", "HOME_PC", "Windows Workstation (RTX 4090)", "online", "Executing Gradle build :app:assembleDebug", System.currentTimeMillis() - 2000L, 18L, listOf("Full Shell", "CUDA 12.4", "Local Ollama", "Kokoro TTS")),
      CanvasPresence("p3", "agent_hermes", "Hermes Codex", "AGENT", "Home PC Node", "idle", "Awaiting Provider Routing update", System.currentTimeMillis() - 5000L, 22L, listOf("Plan Decomposition", "Deep Search", "A2A Dispatch")),
      CanvasPresence("p4", "agent_builder", "Builder Node", "AGENT", "Home PC Node", "executing", "Compiling Kotlin Multiplatform & Room schema", System.currentTimeMillis() - 1000L, 14L, listOf("Gradle Compile", "KSP", "Dex Optimization")),
      CanvasPresence("p5", "agent_purp", "PurpAngolin", "AGENT", "Android Phone (Local)", "idle", "Current Session Companion active", System.currentTimeMillis(), 2L, listOf("Local Gemma", "UI Synthesis", "Handoff Gateway")),
      CanvasPresence("p6", "agent_omni", "OMNI Verifier", "OMNI", "Android Phone (Local)", "verifying", "Tracking Merkle tree proofs & Keystore hashes", System.currentTimeMillis() - 3000L, 6L, listOf("Cryptographic Verification", "Receipt Ledger"))
    )
  )
  val presenceList: StateFlow<List<CanvasPresence>> = _presenceList.asStateFlow()

  private val _events = MutableStateFlow<List<CanvasEvent>>(
    listOf(
      CanvasEvent("ev_01", "canvas_ship_android_01", "node_user_goal", "Operator", "Android Phone", "ses_ui_01", null, System.currentTimeMillis() - 86400000L, 1001L, CanvasEventType.CANVAS_NODE_CREATED, "Created user goal: Sovereign Android MVP", "sha256_01"),
      CanvasEvent("ev_02", "canvas_ship_android_01", "node_research_hermes", "Hermes Codex", "Home PC", "ses_routing_04", "ev_01", System.currentTimeMillis() - 3600000L, 1020L, CanvasEventType.EXECUTION_STARTED, "Started provider benchmark task on Home PC", "sha256_02"),
      CanvasEvent("ev_03", "canvas_ship_android_01", "node_research_hermes", "Hermes Codex", "Home PC", "ses_routing_04", "ev_02", System.currentTimeMillis() - 3500000L, 1025L, CanvasEventType.ARTIFACT_ADDED, "Generated artifact://reports/provider_bench.json", "sha256_03"),
      CanvasEvent("ev_04", "canvas_ship_android_01", "node_build_apk", "Builder Node", "Home PC", "ses_ui_01", "ev_03", System.currentTimeMillis() - 180000L, 1038L, CanvasEventType.APPROVAL_GRANTED, "Operator armed 5m bounded lease for Gradle build", "sha256_04"),
      CanvasEvent("ev_05", "canvas_ship_android_01", "node_build_apk", "Builder Node", "Home PC", "ses_ui_01", "ev_04", System.currentTimeMillis() - 60000L, 1042L, CanvasEventType.EXECUTION_STARTED, "Home PC running Gradle assembleDebug with 14 modified files", "sha256_05")
    )
  )
  val events: StateFlow<List<CanvasEvent>> = _events.asStateFlow()

  private val _handoffs = MutableStateFlow<List<SessionHandoffCapsule>>(
    listOf(
      SessionHandoffCapsule(
        handoffId = "ho_01",
        canvasId = "canvas_ship_android_01",
        sourceSessionId = "ses_routing_04",
        targetSessionId = "ses_ui_01",
        sourceNode = "Home PC",
        targetNode = "Android Phone",
        objective = "Pass SMART AUTO Provider cascade results to Android UI status bar",
        currentState = "Provider failover table compiled. Local 18ms, OpenRouter-free 240ms, MiniMax 310ms.",
        decisions = listOf("Always prefer Local Gemma for offline (download required), OpenRouter free auto-routing for reasoning, MiniMax lane when key present."),
        unresolvedQuestions = listOf("Should TTS streaming pause automatically on incoming cellular call?"),
        artifacts = listOf("artifact://reports/provider_bench.json"),
        relevantMemory = listOf("Layer 3: Procedural recipe for provider fallback timeout"),
        evidence = listOf("Proof Receipt 0x77ab verified by OMNI"),
        activeAgents = listOf("Hermes Codex", "PurpAngolin"),
        pendingApprovals = listOf("Arm lease for Android native audio test"),
        executionState = "READY_FOR_INTEGRATION",
        nextRecommendedAction = "Incorporate Provider latency pills in UI HeaderMeshBar",
        timestamp = System.currentTimeMillis() - 1800000L
      )
    )
  )
  val handoffs: StateFlow<List<SessionHandoffCapsule>> = _handoffs.asStateFlow()

  private val _conflicts = MutableStateFlow<List<CanvasConflict>>(emptyList())
  val conflicts: StateFlow<List<CanvasConflict>> = _conflicts.asStateFlow()

  private val _crossSessionMessages = MutableStateFlow<List<CrossSessionMessage>>(
    listOf(
      CrossSessionMessage(
        messageId = "csm_01",
        canvasId = "canvas_ship_android_01",
        sourceSessionId = "ses_routing_04",
        targetSessionId = "ses_ui_01",
        senderActor = "Hermes Codex",
        targetType = "SESSION",
        content = "Handoff: Provider failover cascade completed. Ready for UI binding.",
        isDecision = true,
        isExecutionHandoff = true,
        boundedContextNodeIds = listOf("node_research_hermes"),
        boundedContextArtifactIds = listOf("art_provider_bench"),
        timestamp = System.currentTimeMillis() - 1700000L
      ),
      CrossSessionMessage(
        messageId = "csm_02",
        canvasId = "canvas_ship_android_01",
        sourceSessionId = "ses_ui_01",
        targetSessionId = "ALL",
        senderActor = "Operator (You)",
        targetType = "ALL",
        content = "Moving to phone. Keeping Android native APK build running on Home PC.",
        isDecision = false,
        isExecutionHandoff = false,
        timestamp = System.currentTimeMillis() - 1200000L
      ),
      CrossSessionMessage(
        messageId = "csm_03",
        canvasId = "canvas_ship_android_01",
        sourceSessionId = "ses_core_02",
        targetSessionId = "ses_ui_01",
        senderActor = "PurpAngolin",
        targetType = "CURRENT",
        content = "Local session shadowed cleanly. 7-Layer Memory and Merkle roots synced.",
        isDecision = true,
        isExecutionHandoff = false,
        timestamp = System.currentTimeMillis() - 300000L
      )
    )
  )
  val crossSessionMessages: StateFlow<List<CrossSessionMessage>> = _crossSessionMessages.asStateFlow()

  // -------------------------------------------------------------
  // Canvas View & Selection Actions
  // -------------------------------------------------------------

  fun setViewMode(mode: CanvasViewMode) {
    _activeViewMode.value = mode
  }

  fun switchCanvas(canvasId: String) {
    _activeCanvasId.value = canvasId
    _selectedNodeIds.value = emptySet()
    _selectedArtifactIds.value = emptySet()
  }

  fun toggleNodeSelection(nodeId: String) {
    val current = _selectedNodeIds.value.toMutableSet()
    if (current.contains(nodeId)) {
      current.remove(nodeId)
    } else {
      current.add(nodeId)
    }
    _selectedNodeIds.value = current
  }

  fun toggleArtifactSelection(artifactId: String) {
    val current = _selectedArtifactIds.value.toMutableSet()
    if (current.contains(artifactId)) {
      current.remove(artifactId)
    } else {
      current.add(artifactId)
    }
    _selectedArtifactIds.value = current
  }

  fun clearSelection() {
    _selectedNodeIds.value = emptySet()
    _selectedArtifactIds.value = emptySet()
  }

  // -------------------------------------------------------------
  // Node, Edge & Workflow Operations (Real Dependency Graph)
  // -------------------------------------------------------------

  fun addWorkflowNode(
    title: String,
    type: CanvasNodeType,
    assignedAgent: String,
    assignedNode: String,
    parentNodeId: String? = null
  ) {
    val newNodeId = "node_${UUID.randomUUID().toString().take(8)}"
    val newClock = ++logicalClockCounter
    val newNode = CanvasNode(
      nodeId = newNodeId,
      canvasId = _activeCanvasId.value,
      title = title,
      type = type,
      status = CanvasNodeStatus.TODO,
      owner = "Operator",
      assignedAgent = assignedAgent,
      assignedNode = assignedNode,
      dependencies = if (parentNodeId != null) listOf(parentNodeId) else emptyList(),
      posX = (40..240).random().toFloat(),
      posY = (100..500).random().toFloat()
    )

    _nodes.value = _nodes.value + newNode

    if (parentNodeId != null) {
      val edge = CanvasEdge(
        edgeId = "edge_${UUID.randomUUID().toString().take(8)}",
        canvasId = _activeCanvasId.value,
        fromNodeId = parentNodeId,
        toNodeId = newNodeId,
        label = "dep",
        isDependency = true
      )
      _edges.value = _edges.value + edge
    }

    recordEvent(
      nodeId = newNodeId,
      actor = "Operator",
      sourceNode = "Android Phone",
      operation = CanvasEventType.CANVAS_NODE_CREATED,
      summary = "Added node: $title ($type) assigned to $assignedAgent"
    )
  }

  fun updateNodeStatus(nodeId: String, newStatus: CanvasNodeStatus) {
    _nodes.value = _nodes.value.map {
      if (it.nodeId == nodeId) it.copy(status = newStatus) else it
    }
    recordEvent(
      nodeId = nodeId,
      actor = "Operator",
      sourceNode = "Android Phone",
      operation = CanvasEventType.EXECUTION_STARTED,
      summary = "Updated node $nodeId status to ${newStatus.label}"
    )
  }

  fun assignAgentToNode(nodeId: String, newAgent: String) {
    _nodes.value = _nodes.value.map {
      if (it.nodeId == nodeId) it.copy(assignedAgent = newAgent) else it
    }
    recordEvent(
      nodeId = nodeId,
      actor = "Operator",
      sourceNode = "Android Phone",
      operation = CanvasEventType.TASK_ASSIGNED,
      summary = "Reassigned node $nodeId to agent $newAgent"
    )
  }

  fun connectDependencyEdge(fromNodeId: String, toNodeId: String) {
    if (fromNodeId == toNodeId) return
    val exists = _edges.value.any { it.fromNodeId == fromNodeId && it.toNodeId == toNodeId }
    if (exists) return

    val edge = CanvasEdge(
      edgeId = "edge_${UUID.randomUUID().toString().take(8)}",
      canvasId = _activeCanvasId.value,
      fromNodeId = fromNodeId,
      toNodeId = toNodeId,
      label = "dependency",
      isDependency = true
    )
    _edges.value = _edges.value + edge

    // update target node dependencies
    _nodes.value = _nodes.value.map {
      if (it.nodeId == toNodeId && !it.dependencies.contains(fromNodeId)) {
        it.copy(dependencies = it.dependencies + fromNodeId)
      } else it
    }

    recordEvent(
      nodeId = toNodeId,
      actor = "Operator",
      sourceNode = "Android Phone",
      operation = CanvasEventType.WORKFLOW_EDGE_CREATED,
      summary = "Connected workflow dependency: $fromNodeId → $toNodeId"
    )
  }

  // -------------------------------------------------------------
  // Remote Work Steering (Phone -> Home PC controls)
  // -------------------------------------------------------------

  fun steerRemoteNode(
    nodeId: String,
    action: String, // "PAUSE", "RESUME", "APPROVE", "REJECT", "CANCEL", "PRIORITY_UP"
    param: String = ""
  ) {
    val targetNode = _nodes.value.find { it.nodeId == nodeId } ?: return

    when (action) {
      "PAUSE" -> {
        _nodes.value = _nodes.value.map { if (it.nodeId == nodeId) it.copy(status = CanvasNodeStatus.BLOCKED) else it }
      }
      "RESUME" -> {
        _nodes.value = _nodes.value.map { if (it.nodeId == nodeId) it.copy(status = CanvasNodeStatus.RUNNING) else it }
      }
      "APPROVE" -> {
        _nodes.value = _nodes.value.map {
          if (it.nodeId == nodeId) it.copy(
            status = CanvasNodeStatus.RUNNING,
            executionAuthority = "ARMED_LEASE"
          ) else it
        }
      }
      "REJECT" -> {
        _nodes.value = _nodes.value.map { if (it.nodeId == nodeId) it.copy(status = CanvasNodeStatus.FAILED) else it }
      }
      "CANCEL" -> {
        _nodes.value = _nodes.value.map { if (it.nodeId == nodeId) it.copy(status = CanvasNodeStatus.TODO) else it }
      }
    }

    recordEvent(
      nodeId = nodeId,
      actor = "Operator",
      sourceNode = "Android Phone",
      operation = CanvasEventType.REMOTE_STEER_APPLIED,
      summary = "Steered remote node [${targetNode.title}] with action: $action ($param)"
    )
  }

  // -------------------------------------------------------------
  // Cross-Session Messaging & Structured Handoff Capsules
  // -------------------------------------------------------------

  fun sendCrossSessionMessage(
    sourceSessionId: String,
    targetSessionId: String,
    targetType: String,
    content: String,
    isDecision: Boolean = false,
    isExecutionHandoff: Boolean = false
  ) {
    val msg = CrossSessionMessage(
      messageId = "csm_${UUID.randomUUID().toString().take(8)}",
      canvasId = _activeCanvasId.value,
      sourceSessionId = sourceSessionId,
      targetSessionId = targetSessionId,
      senderActor = "Operator (You)",
      targetType = targetType,
      content = content,
      isDecision = isDecision,
      isExecutionHandoff = isExecutionHandoff,
      boundedContextNodeIds = _selectedNodeIds.value.toList(),
      boundedContextArtifactIds = _selectedArtifactIds.value.toList(),
      timestamp = System.currentTimeMillis()
    )

    _crossSessionMessages.value = _crossSessionMessages.value + msg

    recordEvent(
      actor = "Operator",
      sourceNode = "Android Phone",
      sessionId = sourceSessionId,
      operation = CanvasEventType.SESSION_LINKED,
      summary = "Cross-session send to $targetType ($targetSessionId): \"${content.take(40)}...\""
    )
  }

  fun createSessionHandoff(
    sourceSessionId: String,
    targetSessionId: String,
    objective: String,
    currentState: String,
    nextAction: String
  ) {
    val handoff = SessionHandoffCapsule(
      handoffId = "ho_${UUID.randomUUID().toString().take(8)}",
      canvasId = _activeCanvasId.value,
      sourceSessionId = sourceSessionId,
      targetSessionId = targetSessionId,
      sourceNode = "Android Phone",
      targetNode = "Home PC",
      objective = objective,
      currentState = currentState,
      decisions = listOf("Operator confirmed bounded execution parameters", "Session shadowed locally"),
      unresolvedQuestions = emptyList(),
      artifacts = _selectedArtifactIds.value.toList().ifEmpty { listOf("artifact://android/build/app-release.apk") },
      relevantMemory = listOf("Layer 1: Turn state", "Layer 3: Procedural recipe"),
      evidence = listOf("OMNI cryptographic proof 0x9f8b...a011"),
      activeAgents = listOf("PurpAngolin", "Hermes Codex"),
      pendingApprovals = emptyList(),
      executionState = "SYNCHRONIZED",
      nextRecommendedAction = nextAction,
      timestamp = System.currentTimeMillis()
    )

    _handoffs.value = listOf(handoff) + _handoffs.value

    recordEvent(
      actor = "Operator",
      sourceNode = "Android Phone",
      sessionId = sourceSessionId,
      operation = CanvasEventType.HANDOFF_COMMITTED,
      summary = "Committed handoff capsule from $sourceSessionId to $targetSessionId: $objective"
    )
  }

  // -------------------------------------------------------------
  // Offline Takeover & Event Reconciliation with Visible Conflicts
  // -------------------------------------------------------------

  fun triggerHomePcDisconnect() {
    // When Home PC disappears: mark remote nodes as WAITING_FOR_HOME, activate local takeover
    _nodes.value = _nodes.value.map { node ->
      if (node.isRemoteOnly && node.status == CanvasNodeStatus.RUNNING) {
        node.copy(status = CanvasNodeStatus.WAITING_FOR_HOME)
      } else node
    }

    _presenceList.value = _presenceList.value.map { p ->
      if (p.actorId == "node_home_pc") p.copy(state = "offline", currentTask = "Heartbeat lost - Local Sovereign Takeover active") else p
    }

    recordEvent(
      actor = "SessionMesh",
      sourceNode = "Android Phone",
      operation = CanvasEventType.REMOTE_STEER_APPLIED,
      summary = "Home PC disconnected. Sovereign local takeover engaged. Remote-only tasks held in WAITING_FOR_HOME."
    )
  }

  fun triggerHomePcReconnect() {
    // Reconnect Home PC: re-enable remote tasks, run event-log reconciliation
    _presenceList.value = _presenceList.value.map { p ->
      if (p.actorId == "node_home_pc") p.copy(state = "online", currentTask = "Synchronized event spine with Android node") else p
    }

    _nodes.value = _nodes.value.map { node ->
      if (node.status == CanvasNodeStatus.WAITING_FOR_HOME) {
        node.copy(status = CanvasNodeStatus.RUNNING)
      } else node
    }

    // Simulate an actual detected conflict for demonstration if empty
    if (_conflicts.value.isEmpty()) {
      _conflicts.value = listOf(
        CanvasConflict(
          conflictId = "conf_01",
          canvasId = _activeCanvasId.value,
          nodeId = "node_research_hermes",
          nodeTitle = "Research Provider Routing",
          fieldKey = "assignedAgent",
          pcValue = "Hermes Codex (Home PC)",
          phoneValue = "PurpAngolin (Local Phone)",
          pcActor = "Home PC Daemon",
          phoneActor = "Operator (Mobile)",
          pcTimestamp = System.currentTimeMillis() - 60000L,
          phoneTimestamp = System.currentTimeMillis() - 40000L,
          status = "PENDING"
        )
      )
    }

    recordEvent(
      actor = "SessionMesh",
      sourceNode = "Android Phone",
      operation = CanvasEventType.CONFLICT_RESOLVED,
      summary = "Home PC reconnected. Event log reconciled cleanly. 1 pending concurrency conflict surfaced."
    )
  }

  fun resolveConflict(conflictId: String, resolution: String) { // "KEEP_PC", "KEEP_PHONE", "MERGE"
    val conflict = _conflicts.value.find { it.conflictId == conflictId } ?: return

    val status = when (resolution) {
      "KEEP_PC" -> "RESOLVED_PC"
      "KEEP_PHONE" -> "RESOLVED_PHONE"
      else -> "RESOLVED_MERGE"
    }

    _conflicts.value = _conflicts.value.filterNot { it.conflictId == conflictId }

    recordEvent(
      nodeId = conflict.nodeId,
      actor = "Operator",
      sourceNode = "Android Phone",
      operation = CanvasEventType.CONFLICT_RESOLVED,
      summary = "Resolved conflict on [${conflict.nodeTitle}] field '${conflict.fieldKey}' with choice: $resolution"
    )
  }

  // -------------------------------------------------------------
  // Internal Event Recording
  // -------------------------------------------------------------

  private fun recordEvent(
    nodeId: String? = null,
    actor: String,
    sourceNode: String,
    sessionId: String? = null,
    operation: CanvasEventType,
    summary: String
  ) {
    val clock = ++logicalClockCounter
    val event = CanvasEvent(
      eventId = "ev_${UUID.randomUUID().toString().take(8)}",
      canvasId = _activeCanvasId.value,
      nodeId = nodeId,
      actor = actor,
      sourceNode = sourceNode,
      sessionId = sessionId,
      parentEventId = _events.value.lastOrNull()?.eventId,
      timestamp = System.currentTimeMillis(),
      logicalClock = clock,
      operation = operation,
      payloadSummary = summary,
      payloadHash = sha256Hex("$clock:$summary")
    )
    _events.value = _events.value + event
  }

  private fun sha256Hex(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return "sha256_" + bytes.joinToString("") { "%02x".format(it) }.take(12)
  }
}
