package com.example.core.runtime

import com.example.core.model.AgentRecord
import com.example.core.model.CouncilRecord
import com.example.core.model.InteractionMode
import com.example.core.model.MissionRecord
import com.example.core.model.MissionStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AgentTowerManager {

  private val _agents = MutableStateFlow<List<AgentRecord>>(
    listOf(
      AgentRecord(
        id = "agent_angolin",
        name = "PurpAngolin",
        soul = "Sovereign Guide",
        division = "Core Architecture",
        role = "System Orchestrator",
        declaredCaps = listOf("system.orchestrate", "memory.recall", "swarm.coordinate", "mesh.route"),
        avatarColor = 0xFFB5179E,
        status = "idle",
        memoryScope = "Global 7-Layer",
        reputationScore = 0.99f
      ),
      AgentRecord(
        id = "agent_babshaggoth",
        name = "Babshaggoth",
        soul = "Deep Critic & Sentry",
        division = "Security & Audit",
        role = "Council Inquisitor",
        declaredCaps = listOf("security.audit", "proof.verify", "anomaly.detect"),
        avatarColor = 0xFF7209B7,
        status = "idle",
        memoryScope = "Symbolic & Counterfactual",
        reputationScore = 0.97f
      ),
      AgentRecord(
        id = "agent_lyra",
        name = "Lyra Voice",
        soul = "Embodiment & Acoustic",
        division = "Audio/Speech",
        role = "Kokoro / STT Specialist",
        declaredCaps = listOf("microphone.listen", "tts.speak", "audio.transcribe"),
        avatarColor = 0xFF4CC9F0,
        status = "idle",
        memoryScope = "Affective & Episodic",
        reputationScore = 0.98f
      ),
      AgentRecord(
        id = "agent_aegis",
        name = "Aegis Sentinel",
        soul = "Guardian",
        division = "Runtime Security",
        role = "Lease & Permission Arbiter",
        declaredCaps = listOf("lease.grant", "permissions.verify", "sandbox.enforce"),
        avatarColor = 0xFF10B981,
        status = "idle",
        memoryScope = "Symbolic & Procedural",
        reputationScore = 0.99f
      ),
      AgentRecord(
        id = "agent_forge",
        name = "CodeForge",
        soul = "Synthesizer",
        division = "Engineering",
        role = "Software & Diff Engine",
        declaredCaps = listOf("code.generate", "archive.inspect", "diff.apply"),
        avatarColor = 0xFFF59E0B,
        status = "idle",
        memoryScope = "Procedural & Semantic",
        reputationScore = 0.96f
      ),
      AgentRecord(
        id = "agent_yolo",
        name = "OpticYOLO",
        soul = "Perception",
        division = "Vision",
        role = "Object Detection & Visual Evidence",
        declaredCaps = listOf("camera.capture", "camera.yolo_vision", "evidence.snap"),
        avatarColor = 0xFFF43F5E,
        status = "idle",
        memoryScope = "Episodic & Spatial",
        reputationScore = 0.97f
      )
    )
  )
  val agents: StateFlow<List<AgentRecord>> = _agents.asStateFlow()

  private val _activeMissions = MutableStateFlow<List<MissionRecord>>(
    listOf(
      MissionRecord(
        id = "msn_001",
        title = "Home-First Mesh Runtime Health Audit",
        goal = "Verify bidirectional session failover, 7-layer memory persistence, and tool leasing.",
        mode = InteractionMode.WORK,
        status = "active",
        steps = listOf(
          MissionStep("s1", "Scan local Android hardware capabilities (Camera, Mic, GPS)", "agent_yolo", "completed", "Detected 9 active sensors"),
          MissionStep("s2", "Verify Home Workstation LAN heartbeat & Lineage checkpoint", "agent_angolin", "completed", "Checkpoint #412 verified"),
          MissionStep("s3", "Arm Execution lease for safe storage inspection", "agent_aegis", "in_progress", null),
          MissionStep("s4", "Produce OMNI cryptographic proof receipt", "agent_babshaggoth", "pending", null)
        ),
        progress = 0.65f,
        activeAgents = listOf("PurpAngolin", "Babshaggoth", "Aegis Sentinel"),
        artifacts = listOf("mesh_topology.json", "hardware_capabilities.log"),
        createdAt = System.currentTimeMillis() - 3600000
      )
    )
  )
  val activeMissions: StateFlow<List<MissionRecord>> = _activeMissions.asStateFlow()

  private val _councils = MutableStateFlow<List<CouncilRecord>>(
    listOf(
      CouncilRecord(
        id = "council_01",
        issue = "Should Phone automatically take over execution when Home PC heartbeat latency exceeds 2500ms?",
        domain = "Session Mesh & Failover Policy",
        chairAgent = "PurpAngolin",
        members = listOf("PurpAngolin", "Babshaggoth", "Aegis Sentinel", "Lyra Voice"),
        votesFor = 3,
        votesAgainst = 1,
        dissentSummary = "Babshaggoth: Warned that aggressive failover on flaky Wi-Fi may cause split-brain if leases overlap.",
        consensusAction = "ADOPTED: Promote phone with local session shadow; enforce single active execution lease.",
        timestamp = System.currentTimeMillis() - 7200000
      )
    )
  )
  val councils: StateFlow<List<CouncilRecord>> = _councils.asStateFlow()

  fun spawnAgent(name: String, role: String, division: String, declaredCaps: List<String>) {
    val newAgent = AgentRecord(
      id = "agent_${UUID.randomUUID().toString().take(6)}",
      name = name,
      soul = "Custom Specialist",
      division = division,
      role = role,
      declaredCaps = declaredCaps,
      avatarColor = 0xFF9D4EDD,
      status = "idle",
      memoryScope = "Episodic & Semantic"
    )
    _agents.value = _agents.value + newAgent
  }

  fun createMission(title: String, goal: String, mode: InteractionMode) {
    val newMission = MissionRecord(
      id = "msn_${UUID.randomUUID().toString().take(6)}",
      title = title,
      goal = goal,
      mode = mode,
      status = "active",
      steps = listOf(
        MissionStep("st_1", "Decompose user intent into capability requirements", "PurpAngolin", "completed", "Intent mapped to 3 stages"),
        MissionStep("st_2", "Acquire bounded child execution lease", "Aegis Sentinel", "in_progress", null),
        MissionStep("st_3", "Synthesize final response and commit to 7-layer memory", "CodeForge", "pending", null)
      ),
      progress = 0.4f,
      activeAgents = listOf("PurpAngolin", "Aegis Sentinel", "CodeForge"),
      artifacts = emptyList(),
      createdAt = System.currentTimeMillis()
    )
    _activeMissions.value = listOf(newMission) + _activeMissions.value
  }

  fun conveneCouncil(issue: String, domain: String) {
    val newCouncil = CouncilRecord(
      id = "council_${UUID.randomUUID().toString().take(6)}",
      issue = issue,
      domain = domain,
      chairAgent = "PurpAngolin",
      members = listOf("PurpAngolin", "Babshaggoth", "Aegis Sentinel", "CodeForge"),
      votesFor = 3,
      votesAgainst = 1,
      dissentSummary = "Babshaggoth: Recorded risk notes on host boundary isolation.",
      consensusAction = "RECOMMENDATION: Arm bounded execution lease with operator verification.",
      timestamp = System.currentTimeMillis()
    )
    _councils.value = listOf(newCouncil) + _councils.value
  }
}
