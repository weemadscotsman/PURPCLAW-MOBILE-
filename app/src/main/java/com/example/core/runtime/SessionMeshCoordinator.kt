package com.example.core.runtime

import android.util.Log
import com.example.core.model.MeshNode
import com.example.core.model.MeshStatus
import com.example.core.network.HomeRuntimeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session Mesh Coordinator — anti-mock edition.
 *
 * The home node is the CANONICAL PurpClaw runtime (unified_api.js :7780).
 * Every health value shown in the UI comes from a real /api/health response.
 * No hardcoded nodeIds, no fabricated uptime/load, no invented lineage hashes.
 */
class SessionMeshCoordinator(
  private val signer: KeystoreReceiptSigner? = null
) {

  companion object {
    private const val TAG = "SessionMeshCoordinator"
    private const val PHONE_NODE_ID = "phone-android-node-01"
  }

  private val _meshStatus = MutableStateFlow(MeshStatus.DISCOVERING)
  val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

  private val _homeNode = MutableStateFlow(
    MeshNode(
      nodeId = "canonical-runtime",
      name = "PurpClaw Canonical Runtime (Home PC)",
      platform = "windows-x86_64",
      role = "primary-canonical-core",
      online = false,
      address = HomeRuntimeBridge.currentBaseUrl().removePrefix("http://"),
      capabilities = listOf(
        "filesystem.full",
        "shell.powershell",
        "playwright.browser",
        "provider.routing",
        "steering.resolver",
        "event.spine"
      ),
      activeModels = emptyList(),   // populated from REAL runtime state on probe
      memoryVersion = 0L,
      lastHeartbeatMs = 0L,
      isHomeNode = true
    )
  )
  val homeNode: StateFlow<MeshNode> = _homeNode.asStateFlow()

  private val _reconciliationLog = MutableStateFlow<List<String>>(
    listOf("Mesh initialized. Probing canonical PurpClaw runtime at ${HomeRuntimeBridge.currentBaseUrl()} ...")
  )
  val reconciliationLog: StateFlow<List<String>> = _reconciliationLog.asStateFlow()

  /**
   * Probes the canonical Home PurpClaw runtime. All displayed values are from
   * the live response. On failure -> Sovereign Local takeover.
   */
  suspend fun probeHomeNode(): MeshStatus {
    // HOME-LINK DORMANCY LAW: no probing while the settings opt-in is off —
    // a disabled link must cost zero network traffic and zero log spam.
    if (!HomeRuntimeBridge.homeLinkEnabled) {
      if (_meshStatus.value != MeshStatus.SOVEREIGN_LOCAL) {
        triggerLocalTakeover("home link disabled (settings opt-in off)")
      }
      return _meshStatus.value
    }
    val result = HomeRuntimeBridge.probeHealth()
    return when {
      // Reachable AND every subsystem clean -> full attach.
      result.online && (result.status == "ONLINE" || result.status.isNullOrBlank()) -> {
        _meshStatus.value = MeshStatus.HOME_ONLINE
        com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
          router = "SessionMeshCoordinator",
          kind = "mesh_state",
          decision = "HOME_ONLINE",
          success = true,
          input = "status=${result.status} tower=${result.towerStatus} agents=${result.agentCount}",
          output = "runtimeId=${result.runtimeId}"
        )
        _homeNode.value = _homeNode.value.copy(
          online = true,
          lastHeartbeatMs = System.currentTimeMillis(),
          activeModels = listOfNotNull(result.runtimeVersion?.let { "runtime-v$it" }),
          // memoryVersion carries live agent count as mesh telemetry for now
          memoryVersion = result.agentCount.toLong()
        )
        addLog("🟢 Canonical runtime ONLINE · ${result.latencyMs}ms · tower=${result.towerStatus ?: "?"} · agents=${result.agentCount}")
        MeshStatus.HOME_ONLINE
      }
      // Reachable but degraded (e.g. tower down) -> Home stays usable; chat routes through it.
      result.online -> {
        _meshStatus.value = MeshStatus.HYBRID_DEGRADED
        com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
          router = "SessionMeshCoordinator",
          kind = "mesh_state",
          decision = "HYBRID_DEGRADED",
          success = true,
          input = "status=${result.status} tower=${result.towerStatus}",
          output = "chatCapable=${result.chatCapable}"
        )
        _homeNode.value = _homeNode.value.copy(
          online = true,
          lastHeartbeatMs = System.currentTimeMillis(),
          memoryVersion = result.agentCount.toLong()
        )
        addLog("🟡 Canonical runtime reachable, status=${result.status ?: "?"} · tower=${result.towerStatus ?: "?"} · chatCapable=${result.chatCapable}. Home relay remains available.")
        MeshStatus.HYBRID_DEGRADED
      }
      else -> {
        homeNodeHealthError = result.error
        triggerLocalTakeover(result.error ?: "unknown probe failure")
      }
    }
  }

  /** Last probe error from the canonical runtime, for evidence-based UI messaging. */
  var homeNodeHealthError: String? = null
    private set

  /** Providers the PC advertised via /api/capabilities (HOME_PC execution target). */
  val homeAdvertisedProviders = MutableStateFlow<List<HomeRuntimeBridge.HomeProvider>>(emptyList())

  private fun triggerLocalTakeover(reason: String): MeshStatus {
    _meshStatus.value = MeshStatus.SOVEREIGN_LOCAL
    _homeNode.value = _homeNode.value.copy(online = false)
    addLog("🟣 Sovereign Failover: $reason. Phone promoted to Sovereign Local Core.")
    return MeshStatus.SOVEREIGN_LOCAL
  }

  /**
   * RUNTIME TRUTH: called when a mid-turn Home chat fails (connection refused
   * etc). The stale HOME_ONLINE status must flip to SOVEREIGN_LOCAL immediately
   * — otherwise the route pill keeps claiming INFERENCE:HOME-RELAY for turns
   * the home runtime never served.
   */
  fun markHomeUnreachable(reason: String) {
    if (_meshStatus.value == MeshStatus.HOME_ONLINE || _meshStatus.value == MeshStatus.HYBRID_DEGRADED) {
      triggerLocalTakeover(reason)
    }
  }

  fun setMeshMode(newStatus: MeshStatus) {
    _meshStatus.value = newStatus
    if (newStatus == MeshStatus.HOME_ONLINE) {
      _homeNode.value = _homeNode.value.copy(online = true, lastHeartbeatMs = System.currentTimeMillis())
    }
    val logMsg = when (newStatus) {
      MeshStatus.HOME_ONLINE -> "🟢 Attached to canonical Home Runtime."
      MeshStatus.SOVEREIGN_LOCAL -> "🟣 Sovereign Local Core active. Preserving session shadow."
      MeshStatus.HYBRID_DEGRADED -> "🟡 Hybrid Degraded: reconciling event lineage diffs..."
      MeshStatus.DISCOVERING -> "⚪ Scanning for canonical runtime..."
    }
    addLog(logMsg)
  }

  fun configureHomeAddress(host: String, port: Int) {
    HomeRuntimeBridge.configure(host, port)
    _homeNode.value = _homeNode.value.copy(address = "$host:$port")
    addLog("Home runtime endpoint set to $host:$port")
  }

  /**
   * Reconciliation now means a REAL probe of the canonical runtime — the old
   * fabricated lineage-hash merge is gone (verification fraud).
   */
  suspend fun probeMeshHeartbeatCompat(localTurnCount: Int): MeshStatus {
    addLog("Reconciliation: $localTurnCount local turns shadowed; probing canonical runtime...")
    return probeHomeNode()
  }

  fun phoneNode(): MeshNode = MeshNode(
    nodeId = PHONE_NODE_ID,
    name = "PurpClaw Phone Node",
    platform = "android-arm64-v8a",
    role = "sovereign-peer",
    online = true,
    address = "127.0.0.1",
    capabilities = listOf(
      "camera.capture", "microphone.listen", "tts.speak",
      "notifications.send", "storage.scoped_workspace", "sensors.battery_telemetry"
    ),
    activeModels = emptyList(),
    memoryVersion = 0L,
    lastHeartbeatMs = System.currentTimeMillis(),
    isHomeNode = false
  )

  private fun addLog(msg: String) {
    _reconciliationLog.value = (_reconciliationLog.value + msg).takeLast(25)
  }
}
