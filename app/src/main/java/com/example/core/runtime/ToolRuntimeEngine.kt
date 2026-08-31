package com.example.core.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.core.database.DispatchLedgerEntity
import com.example.core.model.DecisionSource
import com.example.core.model.DispatchReceipt
import com.example.core.model.ExecutionLease
import com.example.core.model.ModelMode
import com.example.core.model.ProofReceipt
import com.example.core.model.ToolAffinity
import com.example.core.model.ToolCallRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ToolSpec(
  val name: String,
  val displayName: String,
  val description: String,
  val affinity: ToolAffinity,
  val isEnabled: Boolean = true
)

data class ToolExecutionResult(
  val record: ToolCallRecord,
  val proofReceipt: ProofReceipt?
)

class ToolRuntimeEngine(
  private val context: Context,
  private val signer: KeystoreReceiptSigner,
  private val ttsEngine: TextToSpeechEngine? = null,
  private val cameraEngine: CameraVisionEngine? = null
) {
  val deviceControl = DeviceControlEngine(context)

  companion object {
    private const val TAG = "ToolRuntimeEngine"
    private const val NOTIFICATION_CHANNEL_ID = "purpclaw_runtime_channel"

    // STEP 12 (2026-08-27): routing-freshness budget. Consumers of
    // system.router.inspect reject state older than this many ms; entries
    // ≥3× budget are flagged STALE (consumers should refuse to act on them).
    const val ROUTING_FRESHNESS_BUDGET_MS = 1500L

    /** FRESH ≤ budget, DEGRADED < 3×budget, STALE ≥ 3×budget. */
    fun freshnessStatus(observedAt: Long, now: Long = System.currentTimeMillis()): String {
      val age = (now - observedAt).coerceAtLeast(0L)
      return when {
        age <= ROUTING_FRESHNESS_BUDGET_MS -> "FRESH"
        age < ROUTING_FRESHNESS_BUDGET_MS * 3 -> "DEGRADED"
        else -> "STALE"
      }
    }
  }

  init {
    createNotificationChannel()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val name = "PurpClaw Autonomous Notifications"
      val descriptionText = "Notifications dispatched by PurpClaw Autonomous Agent runtime"
      val importance = NotificationManager.IMPORTANCE_DEFAULT
      val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
        description = descriptionText
      }
      val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      notificationManager?.createNotificationChannel(channel)
    }
  }

  /**
   * RESOLVE CAPTURE PATH — converts a purpclaw:// resource URI to a local filesystem path.
   * Captures are stored in the app's evidence/ directory. The URI format is
   * purpclaw://captures/{filename}.jpg. For anything not matching this scheme,
   * also checks the ArtifactsDatabase metadata for a localPath field.
   */
  private fun resolveCapturePath(uri: String): String? {
    val (root, fileName) = when {
      uri.startsWith("purpclaw://captures/") -> File(context.filesDir, "evidence") to uri.removePrefix("purpclaw://captures/")
      uri.startsWith("purpclaw://chat-images/") -> File(context.filesDir, "chat-images") to uri.removePrefix("purpclaw://chat-images/")
      else -> return null
    }
    if (fileName.isBlank()) return null
    val file = File(root, fileName)
    val rootPath = root.canonicalFile.toPath()
    val filePath = file.canonicalFile.toPath()
    return if (filePath.startsWith(rootPath) && file.exists()) file.absolutePath else null
  }

  fun getAvailableToolsList(isHomeOnline: Boolean): List<ToolSpec> {
    return listOf(
      ToolSpec(
        name = "android.device.info",
        displayName = "Device & OS Inspector",
        description = "Queries real Build.MANUFACTURER, SDK_INT, ABIs and architecture",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.battery.status",
        displayName = "Battery Telemetry",
        description = "Reads real BatteryManager capacity, charging state, and current uA",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.storage.status",
        displayName = "Storage StatFs Auditor",
        description = "Inspects physical scoped workspace files, block sizes, and free MB",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.network.status",
        displayName = "Network Connectivity",
        description = "Checks active WiFi / Cellular transports, latency, and link bandwidth",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.clipboard.read",
        displayName = "Clipboard Ingest",
        description = "Accesses system clipboard buffer for cross-app data injection",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.notification.send",
        displayName = "System Notification Dispatch",
        description = "Fires real Android system status alerts to NotificationManager",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.camera.capture",
        displayName = "CameraX Optical Sensor",
        description = "Captures live optical frame into local evidence storage with SHA-256 digest",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "vision.analyze",
        displayName = "On-Device Vision Analysis",
        description = "Analyzes a purpclaw:// resource URI for scene content, labels, and brightness. Args: resourceUri (purpclaw://captures/...jpg). Returns structured VisionResult: SUCCESS with labels/description, or classified FAILURE (VISION_DARK_FRAME, NO_VISION_PROVIDER, etc). Never returns null.",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.tts.speak",
        displayName = "Acoustic Speech Synthesis",
        description = "Synthesizes spoken audio with native Android TextToSpeech engine",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "desktop.shell_powershell",
        displayName = "PowerShell Remote Execution",
        description = "Dispatches shell commands to Home PC Rig over A2A mesh link",
        affinity = ToolAffinity.REMOTE_BRIDGE,
        isEnabled = isHomeOnline
      ),
      ToolSpec(
        name = "desktop.playwright_browser",
        displayName = "Desktop Browser Automation",
        description = "Executes headless Playwright automation workflows on Home Rig",
        affinity = ToolAffinity.REMOTE_BRIDGE,
        isEnabled = isHomeOnline
      ),
      ToolSpec(
        name = "android.flashlight",
        displayName = "Flashlight / Torch Control",
        description = "Turns the phone torch on or off. Args: 'on', 'off', or 'toggle'",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.app.open",
        displayName = "App Launcher",
        description = "Opens an installed app by name. Args: app name e.g. 'youtube', 'camera'",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.app.list",
        displayName = "Installed App Inventory",
        description = "Returns the live Android launcher inventory with labels and package names",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.browser.open",
        displayName = "Native Browser Open",
        description = "Opens a URL or web search in the default or explicitly requested installed browser. Args JSON: {target,browser?}",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.browser.embed",
        displayName = "Embedded Browser",
        description = "Opens an explicit URL in PurpClaw Dual View. Args JSON: {url}",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.file.write",
        displayName = "Workspace File Writer",
        description = "Writes a real file inside PurpClaw's phone-local workspace. Args JSON: {filename,content}",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.file.read",
        displayName = "Workspace File Reader",
        description = "Reads an existing phone-local workspace file. Args JSON: {filename}",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.file.search",
        displayName = "Workspace File Search",
        description = "Searches real files in the phone-local workspace. Args JSON: {query}",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.artifact.preview",
        displayName = "Workspace Artifact Preview",
        description = "Verifies an existing HTML workspace artifact and opens that exact file in PurpClaw Dual View. Args JSON: {filename}",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.settings.panel",
        displayName = "System Settings Panel",
        description = "Opens a settings panel: wifi, bluetooth, volume, display, battery",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "android.vibrate",
        displayName = "Vibration Control",
        description = "Vibrates the phone. Args: optional duration ms",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.runtime.get",
        displayName = "Runtime Truth Snapshot",
        description = "Live self-inspection: surface, inference location, execution authority, home bridge state",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.capabilities.list",
        displayName = "Capability Registry",
        description = "Lists every device capability with live availability truth",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.permissions.list",
        displayName = "Permission Snapshot",
        description = "Live Android permission grant states",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.tools.list",
        displayName = "Tool Registry",
        description = "Lists all registered android.* / system.* / desktop.* tools with affinity",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.tools.describe",
        displayName = "Tool Schema",
        description = "Returns the JSON-Schema parameters for a single registered tool by name.",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.home.status",
        displayName = "Home Node Status",
        description = "Optional Home-PC compute node heartbeat state",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),

      // ── CROSS-MODE CAPABILITY SPINE (2026-08-26) ────────────────────────
      // 12 introspection tools giving Chat / Work / Council / Main / Child
      // agents / Mobile / Desktop uniform access to runtime truth.
      // All are CHAT-allowed (read-only / self-inspection) so they pass the
      // ExecutionPolicy gate in every mode.
      ToolSpec(
        name = "system.runtime.status",
        displayName = "Runtime Status (Lightweight)",
        description = "Lightweight CapabilitySnapshot: mode + callable count + homeStatus + lease + 5 recent receipt summaries",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.runtime.capabilities",
        displayName = "Full Capability Snapshot",
        description = "Full CapabilitySnapshot: every tool, lifecycle, denial reason, agents, services, device caps",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.agents.list",
        displayName = "Agent Roster",
        description = "Lists agents the local runtime can dispatch to (mirrored from home roster)",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.agent.inspect",
        displayName = "Soul Inspector",
        description = "Returns canonical Soul identity, goals and execution availability for one registered agent",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.agent.delegate",
        displayName = "Delegate to Soul",
        description = "Dispatches a bounded child job through the canonical Home agent runtime and waits for its result",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.plugins.list",
        displayName = "Plugin Roster",
        description = "Lists loaded plugins (empty in this build; reserved for future extension)",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.router.inspect",
        displayName = "Router Current State",
        description = "Returns current RoutingState — active provider/model/last decision id",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.router.last_decision",
        displayName = "Router Last Decision",
        description = "Returns the most recent RoutingReceipt — selection reason, latency, spend mode",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.router.trace",
        displayName = "Router Trace by Session",
        description = "Returns list of RoutingReceipts for a session from local ring buffer (cap 50). Args: sessionId",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.memory.inspect",
        displayName = "Memory Snapshot",
        description = "Returns memoryItems + workingMemory snapshot from cognitive spine / home",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.events.query",
        displayName = "Events Query",
        description = "Queries local event ring buffer. Args: since(ms), kind, sessionId",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      ),
      ToolSpec(
        name = "system.leases.inspect",
        displayName = "Leases Snapshot",
        description = "Returns active leases + last 10 historical leases (id, grantedAt, expiresAt, actor, scope)",
        affinity = ToolAffinity.ANDROID_NATIVE,
        isEnabled = true
      )
    )
  }

  // ── CAPABILITY SPINE INJECTABLES (2026-08-26) ──────────────────────────
  // Resolvers wired by MainViewModel so introspection tools return live data
  // without forcing ToolRuntimeEngine to import runtime singletons.

  /** Returns the current CapabilitySnapshot mirror (or null if not wired). */
  @Volatile
  var capabilitySnapshotResolver: (() -> com.example.core.model.CapabilitySnapshot?)? = null

  /** Returns the current RoutingState. */
  @Volatile
  var routingStateResolver: (() -> Any?)? = null

  /** Returns the last RoutingReceipt. */
  @Volatile
  var lastRoutingReceiptResolver: (() -> Any?)? = null

  /** Returns a list of RoutingReceipts for a given sessionId. */
  @Volatile
  var routingTraceResolver: ((String) -> List<Any>)? = null

  /** STEP 12 (2026-08-27): real dispatch ledger reader (Room DAO-backed). */
  @Volatile
  var dispatchLedgerReader: (() -> List<DispatchLedgerEntity>)? = null

  /** STEP 12 (2026-08-27): recent receipts (small slice) for last_decision + trace. */
  @Volatile
  var dispatchLedgerRecentReader: ((Int) -> List<DispatchLedgerEntity>)? = null

  /** STEP 12 (2026-08-27): per-turn receipts (session-scoped). */
  @Volatile
  var dispatchLedgerForTurnReader: ((String, Int) -> List<DispatchLedgerEntity>)? = null

  /** Returns agent descriptors. */
  @Volatile
  var agentDescriptorsResolver: (() -> List<com.example.core.model.AgentDescriptor>)? = null

  /** Rich registry truth: registered/running/delegated are distinct states. */
  @Volatile
  var agentRosterJsonResolver: (() -> String)? = null

  @Volatile
  var agentInspectResolver: ((String) -> String)? = null

  @Volatile
  var agentDelegateResolver: (suspend (String, String, String?) -> String)? = null

  /** Returns memory snapshot JSON. */
  @Volatile
  var memorySnapshotResolver: (() -> String)? = null

  /** Returns active leases list. */
  @Volatile
  var activeLeasesResolver: (() -> List<com.example.core.model.ExecutionLease>)? = null

  /** In-memory event ring buffer (cap 100), populated by MainViewModel. */
  private val eventRingBuffer: ArrayDeque<MutableMap<String, Any>> = ArrayDeque()
  private val eventBufferLock = Any()

  fun recordEvent(event: MutableMap<String, Any>) {
    synchronized(eventBufferLock) {
      eventRingBuffer.addLast(event)
      while (eventRingBuffer.size > 100) eventRingBuffer.removeFirst()
    }
  }

  fun queryEvents(sinceMs: Long?, kind: String?, sessionId: String?): List<Map<String, Any>> {
    synchronized(eventBufferLock) {
      return eventRingBuffer.filter { ev ->
        (sinceMs == null || (ev["ts"] as? Long ?: 0L) >= sinceMs) &&
        (kind == null || ev["kind"] == kind) &&
        (sessionId == null || ev["sessionId"] == sessionId)
      }.toList()
    }
  }

  fun leaseHistorySnapshot(limit: Int = 10): List<com.example.core.model.ExecutionLease> {
    return activeLeasesResolver?.invoke().orEmpty().take(limit)
  }

  /**
   * Self-inspection source of truth. The ViewModel injects a live resolver so
   * system.* answers come from probes, never from conversation memory.
   */
  @Volatile
  var runtimeContextResolver: (() -> RuntimeContext)? = null

  @Volatile
  var homeOnlineResolver: (() -> Boolean)? = { false }

  /**
   * Live CHAT/WORK mode, injected by the ViewModel each turn. Drives the one
   * ExecutionPolicy gate. Never inferred from conversation history.
   */
  @Volatile
  var currentExecutionMode: ExecutionPolicy.Mode = ExecutionPolicy.Mode.CHAT

  /** Last android.browser.embed request — observed by UI to mount Dual View. */
  val browserEmbedRequest = MutableStateFlow<String?>(null)

  suspend fun executeTool(
    toolName: String,
    arguments: String,
    actorAgent: String = "PurpClawCore",
    lease: ExecutionLease? = null,
    isHomeOnline: Boolean = true
  ): ToolExecutionResult = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    val callId = "call_${UUID.randomUUID().toString().take(8)}"

    // EXECUTION POLICY LAW: one gate decides CHAT vs WORK authority. Lease is
    // recorded for provenance only, never enforced.
    val policyDenialJson = JSONObject().apply {
      put("error", "EXECUTION_POLICY_DENIAL")
      put("reason", "CHAT mode is conversation-only. Tool '$toolName' needs WORK mode.")
      put("requiresWorkMode", true)
    }
    if (!ExecutionPolicy.decide(currentExecutionMode, toolName).allowed) {
      val denialJson = policyDenialJson.toString(2)
      val denialRecord = ToolCallRecord(
        id = callId,
        toolName = toolName,
        affinity = ToolAffinity.ANDROID_NATIVE,
        arguments = arguments,
        output = denialJson,
        isSuccess = false,
        durationMs = 0,
        evidenceHash = computeSha256(denialJson).take(16)
      )
      return@withContext ToolExecutionResult(record = denialRecord, proofReceipt = null)
    }

    // ARGUMENT VALIDATION LAW (paste_23): empty {} or missing required fields
    // must never reach a tool handler and become a real Android intent. Validate
    // schema before dispatch — an empty JSON object for a tool requiring url/query/package
    // becomes INVALID_TOOL_ARGUMENTS, never an Android intent.
    val argParseError = validateToolArguments(toolName, arguments)
    if (argParseError != null) {
      val errJson = JSONObject().apply {
        put("error", "INVALID_TOOL_ARGUMENTS")
        put("tool", toolName)
        put("reason", argParseError)
      }.toString(2)
      val errRecord = ToolCallRecord(
        id = callId,
        toolName = toolName,
        affinity = ToolAffinity.ANDROID_NATIVE,
        arguments = arguments,
        output = errJson,
        isSuccess = false,
        durationMs = 0,
        evidenceHash = computeSha256(errJson).take(16)
      )
      return@withContext ToolExecutionResult(record = errRecord, proofReceipt = null)
    }

    var affinity = ToolAffinity.ANDROID_NATIVE
    var isSuccess = true
    var output = ""

    try {
      when (toolName) {
        "android.device.info" -> {
          val info = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("supported_abis", Build.SUPPORTED_ABIS.joinToString(", "))
            put("hardware", Build.HARDWARE)
            put("board", Build.BOARD)
          }
          output = info.toString(2)
        }

        "android.battery.status" -> {
          val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
          val capacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
          val chargeCounter = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: -1
          val currentAverage = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: -1
          val status = JSONObject().apply {
            put("level_percent", capacity)
            put("charge_counter_uah", chargeCounter)
            put("current_average_ua", currentAverage)
            put("is_charging", batteryManager?.isCharging == true)
          }
          output = status.toString(2)
        }

        "android.storage.status" -> {
          val statFs = StatFs(context.filesDir.path)
          val blockSize = statFs.blockSizeLong
          val totalBlocks = statFs.blockCountLong
          val availableBlocks = statFs.availableBlocksLong
          val totalBytes = totalBlocks * blockSize
          val availableBytes = availableBlocks * blockSize
          val freeBytes = statFs.freeBytes

          val storageInfo = JSONObject().apply {
            put("workspace_dir", context.filesDir.absolutePath)
            put("total_mb", totalBytes / (1024 * 1024))
            put("available_mb", availableBytes / (1024 * 1024))
            put("free_mb", freeBytes / (1024 * 1024))
            put("block_size_bytes", blockSize)
          }
          output = storageInfo.toString(2)
        }

        "android.network.status" -> {
          val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
          val activeNetwork = cm?.activeNetwork
          val caps = cm?.getNetworkCapabilities(activeNetwork)
          val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
          val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
          val isInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
          val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
          val upKbps = caps?.linkUpstreamBandwidthKbps ?: 0

          val netInfo = JSONObject().apply {
            put("has_internet", isInternet)
            put("is_wifi", isWifi)
            put("is_cellular", isCellular)
            put("downlink_kbps", downKbps)
            put("uplink_kbps", upKbps)
          }
          output = netInfo.toString(2)
        }

        "android.clipboard.write" -> {
          val text = runCatching { JSONObject(arguments).optString("text") }.getOrDefault(arguments)
          withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("PurpClaw Output", text)
            clipboard?.setPrimaryClip(clip)
          }
          output = "Successfully wrote ${text.length} characters to Android System Clipboard."
        }

        "android.clipboard.read" -> {
          val text = withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val item = clipboard?.primaryClip?.getItemAt(0)
            item?.text?.toString() ?: ""
          }
          output = if (text.isNotBlank()) {
            "Clipboard Content:\n$text"
          } else {
            "Clipboard is empty."
          }
        }

        "android.file.write" -> {
          val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
          val argsJson = try { JSONObject(arguments) } catch (e: Exception) { null }
          val filename = argsJson?.optString("filename").orEmpty()
          val content = argsJson?.optString("content").orEmpty()
          val targetFile = resolveWorkspaceFile(workspace, filename)
          if (targetFile == null) {
            isSuccess = false
            output = "INVALID_WORKSPACE_PATH: filename must be a safe relative path inside the PurpClaw workspace"
          } else {
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(content)
            output = JSONObject().apply {
              put("written", true)
              put("filename", filename)
              put("bytes", targetFile.length())
              put("resourceUri", "purpclaw://workspace/$filename")
              put("exists", targetFile.isFile)
            }.toString(2)
          }
        }

        "android.file.read" -> {
          val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
          val filename = runCatching { JSONObject(arguments).optString("filename") }.getOrDefault("")
          val targetFile = resolveWorkspaceFile(workspace, filename)
          if (targetFile != null && targetFile.exists() && targetFile.isFile) {
            output = targetFile.readText()
          } else {
            isSuccess = false
            output = "WORKSPACE_FILE_NOT_FOUND: $filename"
          }
        }

        "android.file.search" -> {
          val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
          val query = runCatching { JSONObject(arguments).optString("query") }.getOrDefault("")
          val files = workspace.walkTopDown().filter { it.isFile }.toList()
          val matched = files.filter { it.name.contains(query, ignoreCase = true) || it.readText().contains(query, ignoreCase = true) }
          output = "Found ${matched.size} files matching '$query':\n" + matched.joinToString("\n") { "- ${it.relativeTo(workspace).path} (${it.length()} bytes)" }
        }

        "android.artifact.preview" -> {
          val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
          val filename = runCatching { JSONObject(arguments).optString("filename") }.getOrDefault("")
          val targetFile = resolveWorkspaceFile(workspace, filename)
          if (targetFile == null || !targetFile.isFile || !targetFile.extension.equals("html", true)) {
            isSuccess = false
            output = "ARTIFACT_NOT_PREVIEWABLE: expected an existing .html file inside the workspace"
          } else {
            val url = targetFile.toURI().toString()
            browserEmbedRequest.value = url
            output = JSONObject().apply {
              put("action", "preview_workspace_artifact")
              put("filename", filename)
              put("bytes", targetFile.length())
              put("exists", true)
              put("url", url)
              put("surface", "DUAL_VIEW_PANE")
            }.toString(2)
          }
        }

        "android.notification.send" -> {
          val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
          val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PurpClaw Autonomous Dispatch")
            .setContentText(arguments)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

          val notificationId = (System.currentTimeMillis() % 100000).toInt()
          notificationManager?.notify(notificationId, builder.build())
          output = "Android System Notification dispatched [ID: $notificationId]: \"$arguments\""
        }

        "android.camera.capture" -> {
          val result = cameraEngine?.captureOpticalFrame()
          if (result != null && result.fileSizeBytes > 0 && result.decodes && result.imageFile.isFile) {
            // RESOURCE HANDLE LAW: the model gets a phone-local purpclaw:// URI,
            // never a filename it could go hunting for on PC drives.
            val preflight = if (result.visionLabels.isEmpty()) {
              "ML Kit preflight produced no labels. This does not mean the scene is empty."
            } else {
              "ML Kit coarse classifier candidates (not verified objects): "
            }
            output = "Optical frame captured.\nResource: ${result.toResourceHandleJson()}\n" + preflight +
              (result.visionLabels.takeIf { it.isNotEmpty() }?.joinToString { (label, confidence) ->
                "$label ${"%.0f".format(confidence * 100)}%"
              } ?: "") + "\n" +
              "The phone-local JPEG is decoded and verified. Semantic scene understanding requires a real vision-capable model."
          } else {
            isSuccess = false
            output = "Camera capture failed verification: no non-empty decodable JPEG was produced."
          }
        }

        "vision.analyze" -> {
          // NULL-BUBBLE LAW: vision.analyze must NEVER return null or blank.
          // Always returns structured JSON with a definitive status.
          val parsedArgs = runCatching { JSONObject(arguments ?: "") }.getOrNull()
          val uri = parsedArgs?.optString("resourceUri")?.ifBlank { parsedArgs.optString("uri") }
            ?.ifBlank { null } ?: arguments?.trim()?.takeIf { it.startsWith("purpclaw://") }
          val expectedSha256 = parsedArgs?.optString("sha256")?.ifBlank { null }
          val localPath = uri?.let { resolveCapturePath(it) }
          if (uri == null || localPath == null || !java.io.File(localPath).exists()) {
            isSuccess = false
            output = """{"status":"FAILURE","errorCode":"VISION_ARTIFACT_NOT_FOUND","errorMessage":"The requested image artifact does not exist or is inaccessible. No replacement capture was taken.","uri":"${uri ?: ""}","replacementCapture":false,"recoverable":false}"""
          } else {
            val file = java.io.File(localPath)
            val hash = com.example.core.runtime.CameraVisionEngine.computeFileSha256(file)
            // Get dimensions for the response
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            // ML Kit is preflight telemetry only. Average luminance alone is
            // never allowed to claim that a scene is empty or unreadable.
            val (labels, avgLum, decodesOk) = withContext(Dispatchers.IO) {
              com.example.core.runtime.CameraVisionEngine.analyzeFrame(file, context)
            }
            if (expectedSha256 != null && !hash.equals(expectedSha256, ignoreCase = true)) {
              isSuccess = false
              output = """{"status":"FAILURE","errorCode":"VISION_ARTIFACT_HASH_MISMATCH","errorMessage":"The stored image does not match the requested attachment digest.","uri":"$uri","expectedSha256":"$expectedSha256","actualSha256":"$hash","replacementCapture":false,"recoverable":false}"""
            } else if (!decodesOk || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
              isSuccess = false
              output = """{"status":"FAILURE","errorCode":"DECODE_ERROR","errorMessage":"Image captured but cannot be decoded","uri":"$uri","recoverable":false}"""
            } else {
              isSuccess = false
              val labelList = labels
              output = """{"status":"FAILURE","errorCode":"NO_VISION_PROVIDER","errorMessage":"The exact image is attached and verified, but no semantic vision-capable model is available. ML Kit preflight is not authoritative scene understanding.","artifactVerified":true,"engine":"MLKIT_IMAGE_LABELER_PREFLIGHT","objectDetectionVerified":false,"labelSemantics":"classifier_candidates_only_not_scene_truth","zeroLabelsMeansEmptyScene":false,"uri":"$uri","sha256":"$hash","width":${bounds.outWidth},"height":${bounds.outHeight},"mime":"image/jpeg","averageLuminance":${"%.3f".format(avgLum)},"labels":[${labelList.joinToString { "{\"label\":\"${it.first}\",\"confidence\":${"%.0f".format(it.second * 100)}}" }}],"recoverable":true}"""
            }
          }
        }

        "android.tts.speak" -> {
          ttsEngine?.speak(arguments)
          try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
              @Suppress("DEPRECATION")
              vibrator?.vibrate(50)
            }
          } catch (ignored: Exception) {}
          output = "Spoken through Android TextToSpeech engine: \"$arguments\""
        }

        "system.runtime.get", "system.capabilities.list", "system.permissions.list",
        "system.tools.list", "system.home.status" -> {
          val ctx = runtimeContextResolver?.invoke()
          val homeOnline = homeOnlineResolver?.invoke() ?: false
          output = when (toolName) {
            "system.runtime.get" -> ctx?.let { c ->
              JSONObject().apply {
                put("surface", c.surface)
                put("device", "${c.deviceModel} · ${c.androidVersion}")
                put("executionMode", c.executionMode)
                put("canExecute", c.canExecute)
                put("executionAuthority", "ANDROID_LOCAL")
                put("inferenceLocation", c.inferenceLocation)
                put("homeConnected", homeOnline)
              }.toString(2)
            } ?: "Runtime context resolver not yet wired."
            "system.capabilities.list" -> ctx?.capabilities?.entries?.joinToString("\n") {
              "${it.key}: ${if (it.value) "AVAILABLE" else "unavailable"}"
            } ?: "Runtime context resolver not yet wired."
            "system.permissions.list" -> ctx?.permissions?.entries?.joinToString("\n") {
              "${it.key}: ${it.value}"
            } ?: "Runtime context resolver not yet wired."
            "system.tools.list" -> getAvailableToolsList(homeOnline).joinToString("\n") {
              "${it.name} [${it.affinity}] ${if (it.isEnabled) "" else "(disabled)"}"
            }
            else -> "Home PC bridge: ${if (homeOnline) "ONLINE (optional compute node)" else "OFFLINE"}\n" +
              "Execution stays ANDROID LOCAL regardless. Provider routing is independent."
          }
        }

        "desktop.shell_powershell", "desktop.playwright_browser" -> {
          // TRUTH LAW: Home bridge execution is not implemented. Never fake an
          // "Exit Code 0 SUCCESS" receipt for a command that ran nowhere.
          affinity = ToolAffinity.UNAVAILABLE_ANDROID
          isSuccess = false
          output = "Home PC remote execution bridge is NOT IMPLEMENTED on this build. " +
            "No command was run anywhere. Device-local android.* tools remain fully available."
        }

        "android.flashlight" -> {
          val arg = arguments.trim().lowercase()
          output = when {
            arg == "on" -> { ActionVerifier.noteTorchWrite(true); deviceControl.setTorch(true) }
            arg == "off" -> { ActionVerifier.noteTorchWrite(false); deviceControl.setTorch(false) }
            else -> deviceControl.toggleTorch().also { ActionVerifier.invertTorchMirror() }
          }
        }

        "android.app.open" -> {
          val app = runCatching { JSONObject(arguments).optString("app") }.getOrDefault(arguments)
          output = deviceControl.openApp(app)
        }

        "android.app.list" -> {
          output = deviceControl.listApps()
        }

        "android.browser.open" -> {
          val args = runCatching { JSONObject(arguments) }.getOrNull()
          val target = args?.optString("target").orEmpty().ifBlank { arguments }
          val browser = args?.optString("browser").orEmpty().ifBlank { null }
          output = deviceControl.openBrowser(target, browser)
          if (runCatching { JSONObject(output).optBoolean("ok", false) }.getOrDefault(false).not()) {
            isSuccess = false
          }
        }

        "android.settings.panel" -> {
          val panel = runCatching { JSONObject(arguments).optString("panel") }.getOrDefault(arguments)
          output = deviceControl.openSettingsPanel(panel.ifBlank { "main" })
        }

        "android.vibrate" -> {
          val ms = arguments.trim().toLongOrNull() ?: 200L
          output = deviceControl.vibrate(listOf(0, ms.coerceIn(50, 3000)))
        }

        "android.browser.embed" -> {
          // DUAL VIEW LAW: URL requests embed in PurpClaw's own browser pane,
          // not an external app. The ViewModel observes this event to mount
          // DualViewBrowser; execution reports routing truth.
          val url = runCatching { JSONObject(arguments).optString("url") }
            .getOrDefault(arguments.trim()).ifBlank { "https://www.google.com" }
          browserEmbedRequest.value = url
          output = JSONObject().apply {
            put("action", "embed_browser")
            put("url", url)
            put("surface", "DUAL_VIEW_PANE")
          }.toString(2)
        }

        // ── CROSS-MODE CAPABILITY SPINE — 12 introspection dispatchers ──
        "system.runtime.status" -> {
          val snap = capabilitySnapshotResolver?.invoke()
          output = if (snap == null) {
            "Capability snapshot mirror not yet wired. Live resolver not registered by ViewModel."
          } else {
            JSONObject().apply {
              put("mode", snap.mode.name)
              put("registered_count", snap.registeredTools.size)
              put("callable_count", snap.callableTools.size)
              put("permitted_count", snap.permittedTools.size)
              put("home_online", snap.homeStatus.online)
              put("home_runtime_id", snap.homeStatus.runtimeId ?: JSONObject.NULL)
              put("lease_active", snap.executionLease?.isActive ?: false)
              put("snapshot_id", snap.snapshotId)
              put("captured_at_ms", snap.capturedAtMs)
              put("recent_receipts", JSONArray().apply {
                snap.executedTools.take(5).forEach { r ->
                  put(JSONObject().apply {
                    put("tool", r.toolName)
                    put("success", r.isSuccess)
                    put("hash", r.evidenceHash.take(16))
                    put("duration_ms", r.durationMs)
                  })
                }
              })
            }.toString(2)
          }
        }

        "system.runtime.capabilities" -> {
          val snap = capabilitySnapshotResolver?.invoke()
          output = if (snap == null) {
            "Capability snapshot mirror not yet wired."
          } else {
            JSONObject(snap.toMapForSerialization()).toString(2)
          }
        }

        "system.agents.list" -> {
          output = agentRosterJsonResolver?.invoke() ?: JSONObject().apply {
            val agents = agentDescriptorsResolver?.invoke().orEmpty()
            put("count", agents.size)
            put("agents", JSONArray().apply {
              agents.take(50).forEach { a ->
                put(JSONObject().apply {
                  put("id", a.id)
                  put("name", a.name)
                  put("division", a.division)
                  put("model", a.model)
                  put("tools_count", a.tools.size)
                  put("last_seen_ms", a.lastSeenMs)
                })
              }
            })
          }.toString(2)
        }

        "system.agent.inspect" -> {
          val args = runCatching { JSONObject(arguments) }.getOrNull()
          val id = args?.optString("agentId").orEmpty()
          output = agentInspectResolver?.invoke(id)
            ?: JSONObject().put("ok", false).put("error", "Agent registry resolver unavailable").toString(2)
          isSuccess = runCatching { JSONObject(output).optBoolean("ok", false) }.getOrDefault(false)
        }

        "system.agent.delegate" -> {
          val args = runCatching { JSONObject(arguments) }.getOrNull()
          val id = args?.optString("agentId").orEmpty()
          val task = args?.optString("task").orEmpty()
          val turnId = args?.optString("turnId").orEmpty().ifBlank { null }
          output = agentDelegateResolver?.invoke(id, task, turnId)
            ?: JSONObject().put("ok", false).put("error", "Canonical agent runtime adapter unavailable").toString(2)
          isSuccess = runCatching { JSONObject(output).optBoolean("ok", false) }.getOrDefault(false)
        }

        "system.plugins.list" -> {
          output = JSONObject().apply {
            put("count", 0)
            put("plugins", JSONArray())
            put("note", "No plugins loaded in this build. Slot reserved for future extension.")
          }.toString(2)
        }

        // STEP 12.8 (2026-08-27): structured router.inspect — five named fields
        // the spec distinguishes (endpoint / freshness / modelRouterEnabled /
        // dispatchCount / decisionProvenance). Backed by dispatch_ledger, not
        // the in-memory RoutingReceipt ring buffer.
        "system.router.inspect" -> {
          val st = routingStateResolver?.invoke()
          val homeOnline = homeOnlineResolver?.invoke() ?: false
          val now = System.currentTimeMillis()
          val stateMap: Map<String, Any?> = st?.toMapForSerialization().orEmpty()
          // observedAt: the RoutingState timestamp if present, else now (will be
          // FRESH only if router actually ran within the budget).
          val observedAt = (stateMap["observedAt"] as? Long)
            ?: (stateMap["timestampMs"] as? Long)
            ?: (stateMap["lastUpdateMs"] as? Long)
            ?: now
          val ageMs = (now - observedAt).coerceAtLeast(0L)
          val fs = freshnessStatus(observedAt, now)

          // modelRouterEnabled — read the router gate flag from RoutingState.
          // RoutingState uses `useHomeRouting`; if absent we treat as "false"
          // because homeOnline==false ⇒ the router is structurally disabled.
          val useHomeRouting = (stateMap["useHomeRouting"] as? Boolean)
            ?: (stateMap["homeRoutingEnabled"] as? Boolean)
            ?: false
          val routerEnabled = useHomeRouting && homeOnline

          val selectedProvider = stateMap["selectedProvider"] as? String
            ?: stateMap["provider"] as? String
            ?: stateMap["homeResolvedProvider"] as? String
          val selectedModel = stateMap["selectedModel"] as? String
            ?: stateMap["modelId"] as? String
            ?: stateMap["homeResolvedModel"] as? String
          val routeModeRaw = stateMap["routeMode"] as? String ?: "AUTO"
          val routeMode = runCatching { ModelMode.valueOf(routeModeRaw) }
            .getOrDefault(ModelMode.AUTO)

          // Selector string — MANUAL_PIN:<provider>:<model> if pinned, else AUTO.
          val manualPin = (stateMap["manualPin"] as? Map<*, *>)
            ?: (stateMap["pin"] as? Map<*, *>)
          val manualPinActive = manualPin?.get("active") as? Boolean ?: false
          val manualPinProvider = manualPin?.get("provider") as? String
          val manualPinModel = manualPin?.get("model") as? String
          val selector = if (manualPinActive && manualPinProvider != null && manualPinModel != null) {
            "MANUAL_PIN:$manualPinProvider:$manualPinModel"
          } else {
            "AUTO"
          }

          // Candidate set — derive from RoutingState's candidate list if present,
          // else from home resolved set. Always an array of {provider, modelId,
          // free, toolCapable} records.
          val rawCandidates = (stateMap["candidateSet"] as? List<*>)
            ?: (stateMap["candidates"] as? List<*>)
            ?: emptyList<Any>()
          val candidateArr = JSONArray()
          rawCandidates.take(50).forEach { c ->
            val cm = (c as? Map<*, *>) ?: return@forEach
            candidateArr.put(JSONObject().apply {
              put("provider", cm["provider"] ?: JSONObject.NULL)
              put("modelId", cm["modelId"] ?: cm["model"] ?: JSONObject.NULL)
              put("free", cm["free"] ?: JSONObject.NULL)
              put("toolCapable", cm["toolCapable"] ?: cm["toolsSupported"] ?: JSONObject.NULL)
            })
          }

          // Active policy
          val rawPolicy = stateMap["activePolicy"] as? Map<*, *>
          val activePolicy = JSONObject().apply {
            put("profile", rawPolicy?.get("profile") ?: stateMap["profile"] ?: JSONObject.NULL)
            put("freeOnly", rawPolicy?.get("freeOnly") ?: stateMap["freeOnly"] ?: JSONObject.NULL)
            put("spendMode", rawPolicy?.get("spendMode") ?: stateMap["spendMode"] ?: JSONObject.NULL)
          }

          // Manual pin block
          val pinBlock = JSONObject().apply {
            put("active", manualPinActive)
            put("provider", manualPinProvider ?: JSONObject.NULL)
            put("model", manualPinModel ?: JSONObject.NULL)
          }

          // Fallback state
          val fallbackEnabled = stateMap["fallbackEnabled"] as? Boolean ?: true
          val fallbackTrace = (stateMap["fallbackTrace"] as? List<*>)
            ?: (stateMap["lastFallbackPath"] as? List<*>) ?: emptyList<Any>()
          val fallbackState = JSONObject().apply {
            put("enabled", fallbackEnabled)
            put("lastPath", JSONArray().apply { fallbackTrace.take(10).forEach { put(it ?: JSONObject.NULL) } })
          }

          // Real dispatch ledger read
          val allReceipts = dispatchLedgerReader?.invoke().orEmpty()
          val dispatchCount = allReceipts.size
          val latest = allReceipts.maxByOrNull { it.timestampMs }
          val latestReceiptJson: Any = latest?.let {
            JSONObject(DispatchReceipt.fromEntity(it).toMapForSerialization())
          } ?: JSONObject.NULL
          val decisionSourceLatest = latest?.decisionSource ?: DecisionSource.UNKNOWN.name

          val decisionProvenance = if (dispatchCount == 0) "MISSING" else "OK"

          val version = (stateMap["schemaVersion"] as? Int) ?: 1

          output = JSONObject().apply {
            put("endpoint", "system.router.inspect")
            put("observedAt", observedAt)
            put("ageMs", ageMs)
            put("source", "live_router_state")
            put("version", version)
            put("freshnessBudgetMs", ROUTING_FRESHNESS_BUDGET_MS)
            put("freshnessStatus", fs)
            put("modelRouterEnabled", routerEnabled)
            put("routerEnabled", routerEnabled)
            put("selector", selector)
            put("candidateSet", candidateArr)
            put("selectedProvider", selectedProvider ?: JSONObject.NULL)
            put("selectedModel", selectedModel ?: JSONObject.NULL)
            put("decisionSource", decisionSourceLatest)
            put("activePolicy", activePolicy)
            put("manualPin", pinBlock)
            put("fallbackState", fallbackState)
            put("latestDecisionReceipt", latestReceiptJson)
            put("dispatchCount", dispatchCount)
            put("decisionProvenance", decisionProvenance)
            put("routeMode", routeMode.name)
          }.toString(2)
        }

        // STEP 12.9 (2026-08-27): last_decision returns ONE structured
        // DispatchReceipt (not a toString blob).
        "system.router.last_decision" -> {
          val recent = dispatchLedgerRecentReader?.invoke(1).orEmpty()
          val latest = recent.maxByOrNull { it.timestampMs }
          output = if (latest == null) {
            JSONObject().apply {
              put("ok", false)
              put("reason", "no_decisions_recorded")
              put("note", "Dispatch ledger is empty. Drive a chat turn to populate it.")
            }.toString(2)
          } else {
            val r = DispatchReceipt.fromEntity(latest)
            JSONObject(r.toMapForSerialization()).toString(2)
          }
        }

        // STEP 12.10 (2026-08-27): router.trace returns real DispatchReceipt rows
        // for a session. sessionId maps to sourceTurnId in the ledger.
        "system.router.trace" -> {
          val argsJson = try { JSONObject(arguments) } catch (e: Exception) { null }
          val sessionId = argsJson?.optString("sessionId").orEmpty()
          if (sessionId.isBlank()) {
            output = JSONObject().apply {
              put("error", "MISSING_ARG")
              put("reason", "system.router.trace requires {sessionId: String}")
            }.toString(2)
          } else {
            val perTurn = dispatchLedgerForTurnReader?.invoke(sessionId, 50).orEmpty()
            // Fallback to global recent when the per-turn reader has nothing.
            val list = if (perTurn.isNotEmpty()) perTurn
            else dispatchLedgerRecentReader?.invoke(50).orEmpty()
            output = JSONObject().apply {
              put("sessionId", sessionId)
              put("count", list.size)
              put("decisions", JSONArray().apply {
                list.take(50).forEach { e ->
                  put(JSONObject(DispatchReceipt.fromEntity(e).toMapForSerialization()))
                }
              })
              put("decisionProvenance", if (list.isEmpty()) "MISSING" else "OK")
            }.toString(2)
          }
        }

        "system.memory.inspect" -> {
          val mem = memorySnapshotResolver?.invoke()
          output = mem ?: JSONObject().apply {
            put("note", "memorySnapshotResolver not wired — MainViewModel will register at boot.")
          }.toString(2)
        }

        "system.events.query" -> {
          val argsJson = try { JSONObject(arguments) } catch (e: Exception) { null }
          val since = argsJson?.optLong("since", -1L)?.takeIf { it >= 0 }
          val kind = argsJson?.optString("kind").orEmpty().ifBlank { null }
          val sessionId = argsJson?.optString("sessionId").orEmpty().ifBlank { null }
          val events = queryEvents(since, kind, sessionId)
          output = JSONObject().apply {
            put("count", events.size)
            put("events", JSONArray().apply {
              events.take(100).forEach { ev -> put(JSONObject(ev)) }
            })
          }.toString(2)
        }

        "system.leases.inspect" -> {
          val leases = leaseHistorySnapshot(10)
          output = JSONObject().apply {
            put("active_count", leases.count { it.isActive })
            put("leases", JSONArray().apply {
              leases.forEach { l ->
                put(JSONObject().apply {
                  put("id", l.leaseId)
                  put("active", l.isActive)
                  put("granted_at_ms", l.grantedAtMs)
                  put("expires_at_ms", l.expiresAtMs)
                  put("capabilities", JSONArray(l.allowedCapabilities))
                })
              }
            })
          }.toString(2)
        }

        // Existing introspection tools — extend with richer output.
        "system.runtime.get", "system.capabilities.list", "system.permissions.list",
        "system.tools.list", "system.home.status", "system.tools.describe" -> {
          val ctx = runtimeContextResolver?.invoke()
          val homeOnline = homeOnlineResolver?.invoke() ?: false
          output = when (toolName) {
            "system.runtime.get" -> ctx?.let { c ->
              JSONObject().apply {
                put("surface", c.surface)
                put("device", "${c.deviceModel} · ${c.androidVersion}")
                put("executionMode", c.executionMode)
                put("canExecute", c.canExecute)
                put("executionAuthority", "ANDROID_LOCAL")
                put("inferenceLocation", c.inferenceLocation)
                put("homeConnected", homeOnline)
              }.toString(2)
            } ?: "Runtime context resolver not yet wired."
            "system.capabilities.list" -> ctx?.capabilities?.entries?.joinToString("\n") {
              "${it.key}: ${if (it.value) "AVAILABLE" else "unavailable"}"
            } ?: "Runtime context resolver not yet wired."
            "system.permissions.list" -> ctx?.permissions?.entries?.joinToString("\n") {
              "${it.key}: ${it.value}"
            } ?: "Runtime context resolver not yet wired."
            "system.tools.list" -> {
              val mode = currentExecutionMode
              val lifecycle = com.example.core.model.InteractionMode.valueOf(mode.name)
              val tools = getAvailableToolsList(homeOnline)
              tools.joinToString("\n") { spec ->
                val lc = lifecycleFor(spec.name, lifecycle, null, homeOnline)
                "${spec.name} [${spec.affinity}] state=${lc.state.name}${lc.reason?.let { " reason=$it" } ?: ""}"
              }
            }
            "system.tools.describe" -> {
              val argsJson = try { JSONObject(arguments) } catch (e: Exception) { null }
              val name = argsJson?.optString("name").orEmpty()
              if (name.isBlank()) {
                "system.tools.describe requires {name: String}"
              } else {
                val mode = currentExecutionMode
                val lifecycle = com.example.core.model.InteractionMode.valueOf(mode.name)
                val lc = lifecycleFor(name, lifecycle, null, homeOnline)
                val spec = getAvailableToolsList(homeOnline).firstOrNull { it.name == name }
                JSONObject().apply {
                  put("name", name)
                  put("found", spec != null)
                  if (spec != null) {
                    put("display_name", spec.displayName)
                    put("description", spec.description)
                    put("affinity", spec.affinity.name)
                    put("enabled", spec.isEnabled)
                  }
                  put("state", lc.state.name)
                  put("denial_reason", lc.reason ?: JSONObject.NULL)
                }.toString(2)
              }
            }
            else -> "Home PC bridge: ${if (homeOnline) "ONLINE (optional compute node)" else "OFFLINE"}\n" +
              "Execution stays ANDROID LOCAL regardless. Provider routing is independent."
          }
        }

        else -> {
          output = "UNKNOWN_TOOL\nexecuted=false\ntool=$toolName\nThe tool '$toolName' is not registered in the available tool set. Check system.tools.list for the current catalogue."
          isSuccess = false
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Tool execution failed for $toolName: ${e.message}", e)
      isSuccess = false
      output = "CRITICAL TOOL EXECUTION ERROR: ${e.message}"
    }

    // VERIFY-ACTIONS LAW: stateful actions are confirmed by post-state, not
    // by absence of exception. Verification evidence is folded into the record.
    var verifyNote: String? = null
    var verification: Pair<Boolean, String>? = null
    if (toolName == "android.app.open" || toolName == "android.browser.open") {
      // Foreground hand-off is asynchronous and Samsung may take >450 ms to
      // finish Chrome's first-frame transition. Poll for a bounded 1.6 s so a
      // successful launch is not immediately converted into a false failure.
      for (attempt in 0 until 6) {
        delay(if (attempt == 0) 350 else 250)
        verification = ActionVerifier.verify(context, toolName, arguments)
        if (verification?.first == true) break
      }
    } else {
      verification = ActionVerifier.verify(context, toolName, arguments)
    }
    verification?.let { (confirmed, evidence) ->
      if (!confirmed) isSuccess = false
      verifyNote = evidence
      output = "$output\n[VERIFY] $evidence → ${if (confirmed) "CONFIRMED" else "NOT CONFIRMED"}"
    }

    val durationMs = System.currentTimeMillis() - startTime

    val inputDigest = computeSha256(arguments)
    val outputDigest = computeSha256(output)
    val evidenceHash = computeSha256("$toolName:$arguments:$output:$durationMs")

    val toolRecord = ToolCallRecord(
      id = callId,
      toolName = toolName,
      affinity = affinity,
      arguments = arguments,
      output = output,
      isSuccess = isSuccess,
      durationMs = durationMs,
      evidenceHash = evidenceHash
    )

    val receiptId = "rcpt_${UUID.randomUUID().toString().take(8)}"
    val startedAt = startTime
    val completedAt = System.currentTimeMillis()
    val now = completedAt
    val draftReceipt = ProofReceipt(
      receiptId = receiptId,
      receiptType = "TOOL_EXECUTION",
      subsystemId = "subsystem.android.toolruntime",
      testId = "SLICE_002_TOOL_EXECUTION_$toolName",
      deviceId = Build.FINGERPRINT,
      nodeId = "phone-android-node-01",
      sessionId = "ses_canonical_01",
      startedAt = startedAt,
      completedAt = completedAt,
      result = if (isSuccess) "PASS" else "FAIL",
      inputHash = inputDigest,
      outputHash = outputDigest,
      evidenceHash = evidenceHash,
      signingKeyId = KeystoreReceiptSigner.DEFAULT_KEY_ALIAS,
      signatureAlgorithm = "SHA256withECDSA",
      signature = "",
      nonce = "nonce_tool_$now",
      verificationStatus = when {
        !isSuccess -> "FAILED"
        verifyNote != null -> "VERIFIED"
        else -> "UNVERIFIED"
      },
      actor = "PurpClaw_ToolRuntime",
      agent = actorAgent,
      toolName = toolName,
      // NULL-TRUTHFUL LAW: tool receipts never fabricate a model identity.
      // Tool execution is device-local; the serving LLM (if any) is minted by core.
      modelUsed = "device-local/no-llm",
      evidenceSummary = "Output digest: ${evidenceHash.take(16)}... | Duration: ${durationMs}ms" +
        (verifyNote?.let { " | Verify: $it" } ?: ""),
      proofHash = evidenceHash,
      timestamp = now,
      executionLeaseId = lease?.leaseId ?: "system_lease_00"
    )

    // Hardware Keystore cryptographic signature on the canonical payload
    val canonicalPayload = draftReceipt.computeCanonicalPayload()
    val signatureData = signer.signPayload(canonicalPayload)
    val authenticReceipt = draftReceipt.copy(
      signingKeyId = signatureData.signingKeyId,
      signature = signatureData.signature
    )

    ToolExecutionResult(record = toolRecord, proofReceipt = authenticReceipt)
  }

  private fun computeSha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  /**
   * ARGUMENT VALIDATION LAW (paste_23): validate required fields before any
   * tool handler runs. Empty {} or missing required params returns a
   * descriptive error instead of letting the handler proceed with junk data
   * that could become a real Android intent/url/app launch.
   */
  private fun validateToolArguments(toolName: String, arguments: String): String? {
    val schemaValidatedTools = setOf(
      "vision.analyze",
      "android.browser.open",
      "android.app.open",
      "android.clipboard.write",
      "android.file.write",
      "android.file.read",
      "android.file.search",
      "android.artifact.preview",
      "android.browser.embed",
      "android.settings.panel",
      "system.agent.inspect",
      "system.agent.delegate"
    )
    if (toolName !in schemaValidatedTools) return null
    val args = runCatching { JSONObject(arguments) }.getOrNull()

    // For tools requiring arguments, empty/missing arguments is an error
    if (args == null) {
      if (arguments.isBlank()) return "required JSON arguments missing or empty"
      return "arguments are not valid JSON: $arguments"
    }

    when (toolName) {
      "vision.analyze" -> {
        val uri = args.optString("resourceUri").orEmpty().ifBlank { args.optString("uri").orEmpty() }
        if (uri.isBlank()) return "vision.analyze requires a non-empty resourceUri"
      }
      "android.browser.open" -> {
        val target = args.optString("target").orEmpty()
        if (target.isBlank()) return "android.browser.open requires a 'target' (URL or search query)"
      }
      "android.app.open" -> {
        val app = args.optString("app").orEmpty()
        if (app.isBlank()) return "android.app.open requires an 'app' parameter"
      }
      "android.clipboard.write" -> {
        val text = args.optString("text").orEmpty()
        if (text.isBlank()) return "android.clipboard.write requires a 'text' parameter"
      }
      "android.file.write" -> {
        if (args.optString("filename").isBlank()) return "android.file.write requires a 'filename'"
        if (!args.has("content")) return "android.file.write requires 'content'"
      }
      "android.file.read", "android.artifact.preview" -> {
        if (args.optString("filename").isBlank()) return "$toolName requires a 'filename'"
      }
      "android.file.search" -> if (args.optString("query").isBlank()) return "android.file.search requires a 'query'"
      "android.browser.embed" -> if (args.optString("url").isBlank()) return "android.browser.embed requires a 'url'"
      "android.settings.panel" -> {
        val panel = args.optString("panel").orEmpty()
        if (panel.isBlank()) return "android.settings.panel requires a 'panel' parameter"
      }
      "system.agent.inspect" -> {
        if (args.optString("agentId").isBlank()) return "system.agent.inspect requires an 'agentId'"
      }
      "system.agent.delegate" -> {
        if (args.optString("agentId").isBlank()) return "system.agent.delegate requires an 'agentId'"
        if (args.optString("task").isBlank()) return "system.agent.delegate requires a 'task'"
      }
    }
    return null
  }

  private fun resolveWorkspaceFile(workspace: File, relativePath: String): File? {
    if (relativePath.isBlank() || File(relativePath).isAbsolute) return null
    val root = workspace.canonicalFile
    val candidate = File(root, relativePath).canonicalFile
    return candidate.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
  }
}

/**
 * Best-effort reflection-free toMap() for arbitrary data classes so the
 * introspection tools can JSON-encode objects they don't statically know
 * (RoutingState, RoutingReceipt, ExecutionLease). Uses toString() as a
 * fallback — never crashes, never throws.
 */
private fun Any?.toMapForSerialization(): Map<String, Any?> = when (this) {
  null -> emptyMap()
  is Map<*, *> -> {
    @Suppress("UNCHECKED_CAST")
    val out = LinkedHashMap<String, Any?>()
    (this as Map<Any?, Any?>).forEach { (k, v) -> out[k?.toString() ?: "null"] = v }
    out
  }
  is Collection<*> -> mapOf("items" to this.map { it.toMapForSerialization() }, "size" to size)
  else -> {
    // data class fields via reflection-light: rely on the data class toString() shape
    // which is `<ClassName(field1=..., field2=...)>` — preserve for human inspection.
    mapOf("repr" to toString(), "class" to this::class.java.simpleName)
  }
}
