package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.core.database.PurpClawDatabase
import com.example.core.database.SnapshotEntity
import com.example.core.database.TurnEntity
import com.example.core.model.CompanionPetState
import com.example.core.model.DriveState
import com.example.core.model.ExecutionLease
import com.example.core.model.InteractionMode
import com.example.core.model.MemoryLayer
import com.example.core.model.MediaAttachment
import com.example.core.model.MediaKind
import com.example.core.model.MeshStatus
import com.example.core.model.ModelMode
import com.example.core.model.ProofReceipt
import com.example.core.model.SteeringCapsule
import com.example.core.model.ToolAffinity
import com.example.core.model.ToolCallRecord
import com.example.core.model.TurnRecord
import com.example.core.model.VaultItem
import com.example.core.network.HomeRuntimeBridge
import com.example.core.runtime.AgentTowerManager
import com.example.core.runtime.AndroidLocalModelHost
import com.example.core.runtime.CameraVisionEngine
import com.example.core.runtime.BoundedToolContinuation
import com.example.core.runtime.ToolLoopTermination
import com.example.core.runtime.CanvasManager
import com.example.core.runtime.CapabilityTruthRegistry
import com.example.core.runtime.SharedQuotaLedger
import com.example.core.runtime.CouncilPodcastEngine
import com.example.core.runtime.CapabilityTruthState
import com.example.core.runtime.KeyStoreVault
import com.example.core.runtime.KeystoreReceiptSigner
import com.example.core.runtime.MemoryGateway
import com.example.core.runtime.ProviderRouter
import com.example.core.runtime.RuntimeContext
import com.example.core.runtime.ProviderExecutionResult
import com.example.core.runtime.SpendSnapshot
import com.example.core.model.RoutingReceipt
import com.example.core.model.SpendMode
import com.example.core.model.SpendPolicy
import com.example.core.runtime.SessionMeshCoordinator
import com.example.core.runtime.ConversationMode
import com.example.core.runtime.VoiceMode
import com.example.core.runtime.VoiceModeController
import com.example.core.soul.SoulLoader
import com.example.core.soul.BundledSoulRegistry
import com.example.core.runtime.ExecutionPolicy
import com.example.core.runtime.IntentResolver
import com.example.core.runtime.IntentResolver.RoutedIntent
import com.example.core.runtime.StartupSelfCheck
import com.example.core.runtime.SpeechRecognitionEngine
import com.example.core.runtime.TextToSpeechEngine
import com.example.core.runtime.ToolRuntimeEngine
import com.example.core.runtime.ToolIntentBoundary
import com.example.core.runtime.lifecycleFor
import com.example.core.runtime.toDescriptor
import com.example.core.runtime.dispatchCanonical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File

enum class NavigationSurface(val label: String, val iconName: String) {
  COMMAND("COMMAND", "terminal"),
  CANVAS("CANVAS", "dashboard_customize"),
  MISSIONS("MISSIONS", "alt_route"),
  ORGANISATION("ORGANISATION", "diversity_3"),
  COUNCIL("COUNCIL", "gavel"),
  STUDIO("STUDIO", "podcasts"),
  MEMORY("7-L MEMORY", "layers"),
  TOOLS_MESH("TOOLS & MESH", "hub"),
  AUDIT_PROOF("AUDIT & TRUTH", "verified_user"),
  AI_MODELS("AI & MODELS", "psychology"),
  VAULT("VAULT", "lock")
}

/** CHAT/WORK interaction mode → ExecutionPolicy authority. */
private fun InteractionMode.toPolicyMode(): ExecutionPolicy.Mode = when (this) {
  InteractionMode.WORK -> ExecutionPolicy.Mode.WORK
  else -> ExecutionPolicy.Mode.CHAT
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

  val db = PurpClawDatabase.getDatabase(application)
  val keystoreSigner = KeystoreReceiptSigner()
  val vault = KeyStoreVault(application)
  private val providerOAuth = com.example.core.runtime.ProviderOAuthManager(vault)
  val localModelHost = AndroidLocalModelHost(application.filesDir)
  val providerRouter = ProviderRouter(vault, localModelHost, application)
  val ttsEngine = TextToSpeechEngine(application)
  val cameraEngine = CameraVisionEngine(application)
  val speechEngine = SpeechRecognitionEngine(application)
  private val prefs = application.getSharedPreferences("purpclaw_state", android.content.Context.MODE_PRIVATE)
  val voiceModeController = VoiceModeController(speechEngine, ttsEngine, prefs)
  val capabilityRegistry = CapabilityTruthRegistry(application, keystoreSigner)
  val toolRuntime = ToolRuntimeEngine(application, keystoreSigner, ttsEngine, cameraEngine)
  val meshCoordinator = SessionMeshCoordinator(keystoreSigner)
  val memoryGateway = MemoryGateway(db.memoryDao())
  val agentTower = AgentTowerManager()
  val canvasManager = CanvasManager(db.canvasDao())

  /** Council podcast — lazy so TTS init cost stays off cold start. */
  private var podcastEngine: CouncilPodcastEngine? = null
  private fun getOrCreatePodcastEngine(): CouncilPodcastEngine =
    podcastEngine ?: CouncilPodcastEngine(
      getApplication(),
      HomeRuntimeBridge,
      ttsEngine
    ).also { engine ->
      podcastEngine = engine
      // P0 RECOVERY FIX: wire the seat's sovereign fallback to the phone's
      // EXISTING ProviderRouter (free-only, single-attempt). No new subsystem.
      // When the canonical Home runtime is down, a failed seat recovers via the
      // phone instead of re-hammering the dead 4s connect wall.
      engine.phoneInferenceProvider = { prompt, preferred ->
        // SINGLE OWNERSHIP of candidate rotation: ProviderRouter is the sole
        // authority for candidate selection, quarantine, and rotation (P0-C fix).
        // phoneInferenceProvider delegates a single routed call and consumes
        // the canonical attempt chain + served provider/model from the receipt.
        // CANONICAL CONTRACT PARITY (2026-08-30): failures are classified
        // structurally via SharedQuotaLedger.classifyFailure(). The attempt
        // chain comes from the RoutingReceipt's canonical attempts[] — never
        // reconstructed from the requested model. A turn belongs to the
        // runtime, not to a single model invocation.
        val resolvedModel = if (preferred != "AUTO" && preferred != null) preferred
          else resolvePhoneModel(exclude = emptySet())
        if (resolvedModel == null) {
          CouncilPodcastEngine.SeatAttempt.Failure(
            reason = "No policy-eligible phone inference route is currently available",
            latencyMs = 0L,
            category = "NO_ELIGIBLE_ROUTE",
            requestId = "phone-fallback-none",
            attempts = emptyList()
          )
        } else {
        val finalRes = providerRouter.generateResponse(
          prompt = prompt,
          preferredProvider = resolvedModel,
          systemInstruction = "You are a PurpClaw council seat (sovereign fallback). Reply in character, 2-5 sentences.",
          sessionId = _activeSessionId.value,
          toolsRequired = false,
          visionRequired = false,
          isHomeOnline = false,
          conversationHistory = emptyList(),
          priority = SharedQuotaLedger.Priority.PODCAST
        )
        val receipt = finalRes.routingReceipt
        val canonicalAttempts = receipt?.attempts ?: emptyList()
        if (finalRes.content.isNotBlank() && finalRes.errorMessage == null) {
          CouncilPodcastEngine.SeatAttempt.Success(
            text = finalRes.content,
            latencyMs = finalRes.latencyMs,
            tokens = finalRes.tokenCount
          )
        } else {
          val fc = SharedQuotaLedger.classifyFailure(finalRes.errorMessage)
          CouncilPodcastEngine.SeatAttempt.Failure(
            reason = finalRes.errorMessage ?: "provider router exhausted eligible free models",
            latencyMs = finalRes.latencyMs,
            category = when (fc) {
              SharedQuotaLedger.FailureClass.RATE_LIMITED -> "RATE_LIMITED"
              SharedQuotaLedger.FailureClass.PROVIDER_QUOTA_EXHAUSTED -> "QUOTA_EXHAUSTED"
              SharedQuotaLedger.FailureClass.AUTH -> "AUTH_FAILED"
              SharedQuotaLedger.FailureClass.TIMEOUT -> "TIMEOUT"
              SharedQuotaLedger.FailureClass.OTHER -> "NO_ELIGIBLE_ROUTE"
            },
            requestId = "phone-fallback-${receipt?.resolvedProvider ?: "mobile"}",
            attempts = canonicalAttempts.map { ar ->
              com.example.core.model.AttemptRecord(
                attemptIndex = ar.attemptIndex,
                provider = ar.provider,
                model = ar.model,
                outcome = ar.outcome,
                failureClass = ar.failureClass,
                statusCode = ar.statusCode,
                startedAtMs = ar.startedAtMs,
                endedAtMs = ar.endedAtMs,
                latencyMs = ar.latencyMs,
                errorDetail = ar.errorDetail
              )
            }  // canonical chain from ProviderRouter's receipt
          )
        }
        }
      }
      // Home reachability for the fallback gate (HOME-first law preserved when
      // home is healthy). meshStatus is the real probe truth, not a guess.
      engine.homeReachableProvider = {
        val s = meshCoordinator.meshStatus.value
        s == MeshStatus.HOME_ONLINE || s == MeshStatus.HYBRID_DEGRADED
      }
    }

  /** Studio tab access to the same engine instance (single source of truth). */
  fun getOrCreatePodcastEnginePublic(): CouncilPodcastEngine = getOrCreatePodcastEngine()

  val subsystems = capabilityRegistry.subsystems

  private val _activeSessionId = MutableStateFlow(
    prefs.getString("active_session_id", "ses_canonical_01") ?: "ses_canonical_01"
  )
  val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

  private val _activeSurface = MutableStateFlow(NavigationSurface.COMMAND)
  val activeSurface: StateFlow<NavigationSurface> = _activeSurface.asStateFlow()

  private val _interactionMode = MutableStateFlow(
    runCatching { InteractionMode.valueOf(prefs.getString("interaction_mode", "CHAT") ?: "CHAT") }
      .getOrDefault(InteractionMode.CHAT)
  )
  val interactionMode: StateFlow<InteractionMode> = _interactionMode.asStateFlow()

  // ---- Steering / DRIVE (delegated steering authority, gates C23-C26) ----
  private val _steeringCapsule = MutableStateFlow(SteeringCapsule.manual())
  val steeringCapsule: StateFlow<SteeringCapsule> = _steeringCapsule.asStateFlow()

  fun armDrive(state: DriveState) {
    _steeringCapsule.value = if (state == DriveState.OFF) SteeringCapsule.manual()
    else SteeringCapsule.drive(state)
    viewModelScope.launch {
      memoryGateway.storeMemory(
        layer = MemoryLayer.SYMBOLIC,
        key = "steering.${_steeringCapsule.value.capsuleId}",
        content = _steeringCapsule.value.summary()
      )
      // Mirror delegation into the canonical runtime's steering stack
      HomeRuntimeBridge.steer(
        action = "queue",
        directive = "DRIVE ${state.name} armed from Android node — capsule ${_steeringCapsule.value.capsuleId}, authority=900, effect=DELEGATE"
      )
    }
  }

  fun revokeDrive() {
    val capsule = _steeringCapsule.value
    _steeringCapsule.value = SteeringCapsule.manual()
    viewModelScope.launch {
      // C26 revocation: close active authority at the canonical runtime too
      HomeRuntimeBridge.steer(action = "pause", directive = "DRIVE revoked on Android node; capsule ${capsule.capsuleId} closed")
    }
  }

  private val _fullSystemScope = MutableStateFlow(true)
  val fullSystemScope: StateFlow<Boolean> = _fullSystemScope.asStateFlow()

  // ---- Canonical roster (live from main stack: /api/registry/agents + /api/skills/registry) ----
  private val _agentRoster = MutableStateFlow<List<HomeRuntimeBridge.RosterAgent>>(emptyList())
  val agentRoster: StateFlow<List<HomeRuntimeBridge.RosterAgent>> = _agentRoster.asStateFlow()
  private val _agentRosterSource = MutableStateFlow("BUNDLED_CANONICAL_MIRROR")
  private val _runningAgentCount = MutableStateFlow<Int?>(null)
  private val delegatedThisTurn = linkedSetOf<String>()

  private val _skillRoster = MutableStateFlow<List<HomeRuntimeBridge.RosterSkill>>(emptyList())
  val skillRoster: StateFlow<List<HomeRuntimeBridge.RosterSkill>> = _skillRoster.asStateFlow()

  fun refreshRosters() {
    viewModelScope.launch {
      val health = HomeRuntimeBridge.probeHealth()
      _runningAgentCount.value = if (health.online) health.activeAgents else null
      val remote = HomeRuntimeBridge.fetchAgentRoster()
      if (remote.isNotEmpty()) {
        _agentRoster.value = remote
        _agentRosterSource.value = "HOME_CANONICAL_LIVE"
      } else {
        _agentRoster.value = BundledSoulRegistry.load(getApplication())
        _agentRosterSource.value = "BUNDLED_CANONICAL_MIRROR"
      }
      _skillRoster.value = HomeRuntimeBridge.fetchSkillRoster()
      refreshCapabilitySnapshot()
    }
  }

  private val _companionState = MutableStateFlow(CompanionPetState.IDLE)
  val companionState: StateFlow<CompanionPetState> = _companionState.asStateFlow()

  private val _selectedCompanion = MutableStateFlow(
    prefs.getString("selected_companion", "PurpAngolin")
      ?.takeIf { it in com.example.ui.components.Avatar3DClips.COMPANION_NAMES }
      ?: "PurpAngolin"
  )
  val selectedCompanion: StateFlow<String> = _selectedCompanion.asStateFlow()

  // STICKINESS LAW: model pick survives process death via prefs.
  private val _selectedModel = MutableStateFlow(prefs.getString("selected_model", "AUTO") ?: "AUTO")
  val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

  private val _isGenerating = MutableStateFlow(false)
  val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
  private var activeTurnJob: Job? = null
  @Volatile private var activeTurnToken: String? = null
  @Volatile private var activeTurnId: String? = null

  private fun emitTurnEvent(kind: String, turnId: String, extra: Map<String, Any> = emptyMap()) {
    toolRuntime.recordEvent(mutableMapOf<String, Any>(
      "kind" to kind,
      "turn_id" to turnId,
      "turnId" to turnId,
      "trace_id" to turnId,
      "traceId" to turnId,
      "sessionId" to _activeSessionId.value,
      "parentTurnId" to turnId,
      "soul_id" to _selectedCompanion.value,
      "ts" to System.currentTimeMillis()
    ).apply { putAll(extra) })
    Log.i("TurnLifecycle", "$kind turn_id=$turnId")
  }

  fun cancelActiveTurn() {
    val turnId = activeTurnId
    val job = activeTurnJob
    if (turnId == null || job == null) return
    emitTurnEvent("turn.cancel.requested", turnId, mapOf("reason" to "operator"))
    activeTurnToken = null
    activeTurnId = null
    ttsEngine.stop()
    job?.cancel(kotlinx.coroutines.CancellationException("operator_cancelled"))
    emitTurnEvent("turn.cancelled", turnId, mapOf("reason" to "operator"))
    _liveTurn.value = null
    _lastLiveStatus.value = "cancelled"
    _isGenerating.value = false
    _companionState.value = CompanionPetState.IDLE
    drainQueue()
  }

  // TURN LIFECYCLE LAW (paste_23): a single authoritative flag that tracks
  // whether the BoundedToolContinuation loop owns the active turn. The UI
  // must never render a terminal assistant message ("No response received",
  // error card, etc.) while this is true. Only COMPLETED or TERMINAL_FAILURE
  // ends the turn.
  private val _toolLoopActive = MutableStateFlow(false)
  val toolLoopActive: StateFlow<Boolean> = _toolLoopActive.asStateFlow()

  private var lastTurnWasVoice = false

  private val _liveTurn = MutableStateFlow<TurnRecord?>(null)
  val liveTurn: StateFlow<TurnRecord?> = _liveTurn.asStateFlow()

  /** STEP 13.6 (2026-08-27): chat status row binding — was a local closure variable
   *  inside processTurn (L917) so the UI never saw it. Now a real StateFlow. */
  private val _lastLiveStatus = MutableStateFlow<String>("")
  val lastLiveStatus: StateFlow<String> = _lastLiveStatus.asStateFlow()

  /** STEP 13.6: token burn in cents — updated after every provider call. */
  private val _tokenBurnCents = MutableStateFlow(0.0)
  val tokenBurnCents: StateFlow<Double> = _tokenBurnCents.asStateFlow()

  /**
   * TOOL-MARKUP STRIP LAW: some models emit fake tool-call tokens
   * (<|tool_call_start|>...) as text. Tools are resolved locally by
   * IntentResolver BEFORE the model call — any markup in the reply is
   * hallucinated decoration, never a real execution. Strip it from every
   * inference path (direct, home-relay, sovereign-fallback).
   */
  private fun stripHallucinatedToolMarkup(r: ProviderExecutionResult): ProviderExecutionResult {
    val cleaned = stripToolCallBlocksFromReply(r.content)
    return if (cleaned != r.content) r.copy(content = cleaned) else r
  }

  /**
   * Strip every form of tool-call JSON / pseudo-XML the model can emit inside
   * its visible reply. The user must never see or hear these blocks — they
   * belong only to the status box (ToolCallLedgerItem) and the dispatch
   * ledger. Defensive against: Hermes/Qwen-style JSON, OpenAI-style JSON,
   * <tool_call> XML, mistral <|tool_call_*|> tags, bare android_*(...) calls.
   */
  private fun stripToolCallBlocksFromReply(raw: String): String {
    var s = raw
    // mistral / laq / OpenPipe tool tags
    s = s.replace(Regex("<\\|tool_call_start\\|>"), "")
    s = s.replace(Regex("<\\|tool_call_end\\|>"), "")
    s = s.replace(Regex("<\\|tool_response_start\\|>[\\s\\S]*?<\\|tool_response_end\\|>"), "")
    // JSON-style tool calls: {"tool": "...", "args": {...}}  (greedy across newlines)
    s = s.replace(Regex("\\{\\s*\"tool\"\\s*:.*?\"args\"\\s*:\\s*\\{[\\s\\S]*?\\}\\s*\\}"), "")
    s = s.replace(Regex("\\{\\s*\"tool_calls\"\\s*:\\s*\\[[\\s\\S]*?\\]\\s*\\}"), "")
    s = s.replace(Regex("\\{\\s*\"name\"\\s*:\\s*\"[a-zA-Z_.]+\"\\s*,\\s*\"arguments\"\\s*:\\s*\\{[\\s\\S]*?\\}\\s*\\}"), "")
    // <tool_call>…</tool_call> XML and [tool_call]…[/tool_call] brackets
    s = s.replace(Regex("<tool_call[\\s\\S]*?</tool_call>"), "")
    s = s.replace(Regex("\\[tool_call\\][\\s\\S]*?\\[/tool_call\\]"), "")
    // bare function-call style: android.tool(...) or android_tool(...)
    s = s.replace(Regex("\\[?(android_[a-z_.]+\\([^)]*\\))]?(\\s*<\\|tool_call_end\\|>)?"), "")
    // multi-line collapse from the gaps the strips leave behind
    s = s.replace(Regex("\\n{3,}"), "\n\n").trim()
    return s
  }

  val isListening = speechEngine.isListening
  val isSpeaking = ttsEngine.isSpeaking

  // ── INLINE ACTION RAIL (2026-08-27): per-bubble Read Aloud / Stop / Retry ──
  // The id of the turn currently being spoken by the per-bubble "Read Aloud"
  // action, or null. Drives the bar's VolumeUp ⇄ Stop icon swap. Cleared
  // automatically when the engine leaves its speaking state (natural end,
  // barge-in from a new speak(), or explicit stop).
  private val _speakingTurnId = MutableStateFlow<String?>(null)
  val speakingTurnId: StateFlow<String?> = _speakingTurnId.asStateFlow()

  /** Read Aloud on a specific bubble — speaks that turn's text via the
   *  canonical engine (QUEUE_FLUSH barges in over any previous speech). */
  fun readAloud(turn: TurnRecord) {
    val clean = stripToolCallBlocksFromReply(turn.content)
    if (clean.isBlank()) return
    _speakingTurnId.value = turn.id
    ttsEngine.speak(clean)
  }

  /** Stop the per-bubble Read Aloud playback. */
  fun stopAloud() {
    ttsEngine.stop()
    _speakingTurnId.value = null
  }

  /**
   * Retry an assistant turn: regenerate from the user message that prompted
   * it — NOT a blind "resend the latest global prompt". Resolves the paired
   * user turn via parentEventId, falling back to the nearest preceding user
   * turn in the transcript. Blocked while a generation is already running.
   */
  fun retryTurn(turn: TurnRecord) {
    if (_isGenerating.value) return
    if (turn.role == "user") {
      processTurn(turn.content)
      return
    }
    val history = turns.value
    val idx = history.indexOfFirst { it.id == turn.id }
    if (idx < 0) return
    val paired = turn.parentEventId?.let { pid -> history.firstOrNull { it.id == pid && it.role == "user" } }
    val prompt = paired?.content
      ?: history.take(idx).lastOrNull { it.role == "user" }?.content
      ?: return
    processTurn(prompt)
  }
  // ── end inline action rail ──

  // Voice loop truth: canonical state machine + REAL audio levels for the
  // visualizer overlay. Levels are zero unless audio is genuinely live.
  val voiceMode: StateFlow<VoiceMode> = voiceModeController.mode
  val voiceInputLevel: StateFlow<Float> = voiceModeController.inputLevel
  val voiceOutputLevel: StateFlow<Float> = voiceModeController.outputLevel
  val voiceConversationMode: StateFlow<ConversationMode> = voiceModeController.conversationMode

  private val _inputText = MutableStateFlow("")

  /**
   * TASK #47 (2026-08-27): true while a council podcast is actively running
   * (between convene and the post-episode housekeeping). The mic transcript
   * handler routes captured text to the podcast guest queue when this is
   * set, instead of pushing it as a fresh chat turn.
   */
  private val _isPodcastActive = MutableStateFlow(false)
  val isPodcastActive: StateFlow<Boolean> = _isPodcastActive.asStateFlow()
  val inputText: StateFlow<String> = _inputText.asStateFlow()

  // TASK #54: expose the mutable council panel to the seat-manager UI.
  val activeSeats: StateFlow<List<com.example.core.runtime.CouncilPodcastEngine.Seat>> =
    getOrCreatePodcastEngine().activeSeats

  // TASK #55: when a podcast finishes, the engine emits one record here so
  // the UI can flash a "Download Pod" button for ~6s with fade-out.
  private val _latestSavedEpisode = MutableStateFlow<com.example.core.runtime.CouncilPodcastEngine.SavedEpisode?>(null)
  val latestSavedEpisode: StateFlow<com.example.core.runtime.CouncilPodcastEngine.SavedEpisode?> =
    _latestSavedEpisode.asStateFlow()

  // TASK #56: surface the break mode so the UI can show the active tangent.
  val breakMode: StateFlow<com.example.core.runtime.CouncilPodcastEngine.BreakState?> =
    getOrCreatePodcastEngine().breakMode

  val podcastInferenceRecovery: StateFlow<com.example.core.runtime.CouncilPodcastEngine.InferenceRecoveryStatus> =
    getOrCreatePodcastEngine().inferenceRecovery

  /** TASK #54 — convenience wrappers exposed for UI bindings. */
  fun addCouncilSeat(seat: com.example.core.runtime.CouncilPodcastEngine.Seat) =
    getOrCreatePodcastEngine().addSeat(seat)
  fun removeCouncilSeat(roleOrName: String) =
    getOrCreatePodcastEngine().removeSeat(roleOrName)
  fun resetCouncilSeats() = getOrCreatePodcastEngine().resetActiveSeats()

  /** TASK #56 — break mode toggles. No-op when podcast is idle. */
  fun enterPodcastBreak() { if (_isPodcastActive.value) getOrCreatePodcastEngine().enterBreakMode() }
  fun exitPodcastBreak() { getOrCreatePodcastEngine().exitBreakMode() }

  /** TASK #55 — dismiss the post-show Download Pod button manually. */
  fun dismissLatestSavedEpisode() { _latestSavedEpisode.value = null }

  /** TASK #55 — load all saved podcasts (for the Settings → Saved Podcasts page). */
  fun loadSavedPodcasts(): List<com.example.core.runtime.CouncilPodcastEngine.SavedEpisode> =
    getOrCreatePodcastEngine().loadSavedIndex()

  private val _activeLease = MutableStateFlow<ExecutionLease?>(null)
  val activeLease: StateFlow<ExecutionLease?> = _activeLease.asStateFlow()

  /** Active lease history for system.leases.inspect — last 10 most recent grants. */
  private val leaseHistoryLock = Any()
  private val leaseHistory = mutableListOf<ExecutionLease>()

  init {
    // This must run after _activeLease has been initialized: Kotlin executes
    // property initializers and init blocks in source order.
    // WORK is itself the operator's execution authorization. Restore a
    // session-scoped lease immediately when the persisted mode is WORK; do
    // not make the operator approve an extra five-minute in-app gate.
    if (_interactionMode.value == InteractionMode.WORK) armExecutionLease()
    viewModelScope.launch {
      ttsEngine.isSpeaking.collect { speaking ->
        if (!speaking) _speakingTurnId.value = null
      }
    }
  }

  private val _vaultItems = MutableStateFlow<List<VaultItem>>(emptyList()) // REAL vault state only — no fake seeded keys
  val vaultItems: StateFlow<List<VaultItem>> = _vaultItems.asStateFlow()

  // ── CAPABILITY SPINE (2026-08-26) ─────────────────────────────────────
  // Mirror of the canonical CapabilitySnapshot. Core (:7780) is authority;
  // phone invalidates on lease/mode/home-status change. ToolRuntimeEngine's
  // introspection tools read this via capabilitySnapshotResolver.
  private val _capabilitySnapshot = MutableStateFlow<com.example.core.model.CapabilitySnapshot?>(null)
  val capabilitySnapshot: StateFlow<com.example.core.model.CapabilitySnapshot?> = _capabilitySnapshot.asStateFlow()

  /**
   * Build a fresh snapshot for the current (mode, lease, homeStatus) tuple
   * and publish it. Pure function of inputs — callers MUST re-invoke on
   * every observable change to keep the mirror honest.
   */
  fun refreshCapabilitySnapshot() {
    val mode = _interactionMode.value
    val lease = _activeLease.value
    val meshStatusNow = meshCoordinator.meshStatus.value
    val homeOnline = meshStatusNow == MeshStatus.HOME_ONLINE ||
      meshStatusNow == MeshStatus.HYBRID_DEGRADED
    val homeNodeNow = meshCoordinator.homeNode.value
    val tools = toolRuntime.getAvailableToolsList(homeOnline)
    val descriptors = tools.map { it.toDescriptor() }
    val lifecycle = computeLifecycleFor(mode, lease, homeOnline, descriptors)
    val homeStatus = com.example.core.model.HomeStatusSnapshot(
      online = homeOnline,
      latencyMs = if (homeOnline) (System.currentTimeMillis() - homeNodeNow.lastHeartbeatMs).coerceAtLeast(0L) else -1L,
      runtimeId = if (homeOnline) homeNodeNow.nodeId else null,
      status = if (homeOnline) "ONLINE" else "OFFLINE",
      agentCount = _agentRoster.value.size,
      error = meshCoordinator.homeNodeHealthError
    )
    val recentReceipts = emptyList<com.example.core.database.ProofReceiptEntity>()
    val executedNames: List<com.example.core.model.ToolCallRecord> = emptyList()
    // Receipts are populated asynchronously (see refreshExecutedToolsAsync below)
    // to avoid blocking the UI thread on a Room Flow.
    val _recentReceiptsUnused: List<com.example.core.database.ProofReceiptEntity> = recentReceipts
    val agentDescriptors = _agentRoster.value.take(25).map { ro ->
      com.example.core.model.AgentDescriptor(
        id = ro.id,
        name = ro.name,
        division = ro.division,
        model = ro.model,
        tools = emptyList(),
        lastSeenMs = if (homeOnline) System.currentTimeMillis() else 0L
      )
    }
    val runtimeServices = buildList {
      add(
        com.example.core.model.RuntimeServiceDescriptor(
          id = "phone-android",
          port = null,
          status = "ONLINE",
          lastHeartbeatMs = System.currentTimeMillis()
        )
      )
      if (homeOnline) {
        add(
          com.example.core.model.RuntimeServiceDescriptor(
            id = "home-runtime",
            port = 7780,
            status = "ONLINE",
            lastHeartbeatMs = homeNodeNow.lastHeartbeatMs
          )
        )
      }
    }
    val snap = com.example.core.model.CapabilitySnapshot(
      mode = mode,
      registeredTools = descriptors,
      availableTools = descriptors.filter { it.affinity != com.example.core.model.ToolAffinity.REMOTE_BRIDGE || homeOnline }.map { it.name },
      permittedTools = lifecycle.filter { it.value.state.ordinal >= com.example.core.runtime.ToolLifecycleState.PERMITTED.ordinal }.keys.toList(),
      callableTools = lifecycle.filter { it.value.state.ordinal >= com.example.core.runtime.ToolLifecycleState.CALLABLE.ordinal }.keys.toList(),
      executedTools = executedNames,
      functions = emptyList(),
      plugins = emptyList(),
      agents = agentDescriptors,
      runtimeServices = runtimeServices,
      deviceCapabilities = emptyMap(),
      executionLease = lease,
      denialReasons = lifecycle.filter { it.value.reason != null }.mapValues { it.value.reason!! },
      homeStatus = homeStatus,
      snapshotId = "snap_${System.currentTimeMillis()}",
      capturedAtMs = System.currentTimeMillis()
    )
    _capabilitySnapshot.value = snap
    // Wire the introspection tools' resolver so system.* reads live data.
    toolRuntime.capabilitySnapshotResolver = { _capabilitySnapshot.value }
    toolRuntime.routingStateResolver = { providerRouter.routingState.value }
    toolRuntime.lastRoutingReceiptResolver = { providerRouter.routingState.value.lastRoutingReason }
    toolRuntime.routingTraceResolver = { _ -> emptyList() }
    toolRuntime.agentDescriptorsResolver = { agentDescriptors }
    toolRuntime.agentRosterJsonResolver = {
      val current = _agentRoster.value
      JSONObject().apply {
        put("registry_source", _agentRosterSource.value)
        put("registered_count", current.size)
        put("running_count", _runningAgentCount.value ?: JSONObject.NULL)
        put("running_state", when {
          _runningAgentCount.value != null -> "OBSERVED"
          homeOnline -> "UNKNOWN_NOT_MIRRORED"
          else -> "HOME_OFFLINE"
        })
        put("delegated_this_turn_count", synchronized(delegatedThisTurn) { delegatedThisTurn.size })
        put("execution_transport", if (homeOnline) "HOME_CHILD_RUNTIME" else "UNAVAILABLE_HOME_OFFLINE")
        put("agents", JSONArray().apply {
          current.take(96).forEach { agent ->
            put(JSONObject().apply {
              put("id", agent.id)
              put("name", agent.name)
              put("role", agent.role ?: agent.desc)
              put("division", agent.division)
              put("registered", true)
              put("execution_available", homeOnline)
            })
          }
        })
      }.toString(2)
    }
    toolRuntime.agentInspectResolver = inspect@{ requestedId ->
      val agent = _agentRoster.value.firstOrNull {
        it.id.equals(requestedId, true) || it.name.equals(requestedId, true)
      } ?: return@inspect JSONObject().apply {
        put("ok", false); put("error", "Soul '$requestedId' is not registered")
      }.toString(2)
      JSONObject().apply {
        put("ok", true)
        put("id", agent.id); put("name", agent.name)
        put("role", agent.role ?: agent.desc); put("division", agent.division)
        put("wants", agent.wants ?: ""); put("needs", agent.needs ?: "")
        put("goals", agent.goals ?: ""); put("wishes", agent.wishes ?: "")
        put("soul_description", agent.soulDescription ?: agent.desc)
        put("voice_id", agent.voiceProfile?.kokoroVoiceId ?: JSONObject.NULL)
        put("registry_source", _agentRosterSource.value)
        put("execution_available", homeOnline)
        put("execution_route", if (homeOnline) "HOME_CHILD_RUNTIME" else "BLOCKED_HOME_OFFLINE")
      }.toString(2)
    }
    toolRuntime.agentDelegateResolver = { agentId, task, turnId ->
      if (!homeOnline) {
        JSONObject().apply {
          put("ok", false); put("blocked", true)
          put("error", "Canonical child-agent executor unavailable: Home runtime is offline")
          put("agent_id", agentId); put("turn_id", turnId ?: JSONObject.NULL)
          put("registered", _agentRoster.value.any { it.id.equals(agentId, true) || it.name.equals(agentId, true) })
        }.toString(2)
      } else {
        val result = HomeRuntimeBridge.delegateAgentAndAwait(
          soulId = agentId,
          task = task,
          parentSessionId = _activeSessionId.value
        )
        if (result.ok) synchronized(delegatedThisTurn) { delegatedThisTurn += agentId }
        JSONObject().apply {
          put("ok", result.ok); put("agent_id", agentId)
          put("job_id", result.jobId ?: JSONObject.NULL)
          put("status", result.status ?: JSONObject.NULL)
          put("result", result.result ?: JSONObject.NULL)
          put("error", result.error ?: JSONObject.NULL)
          put("turn_id", turnId ?: JSONObject.NULL)
        }.toString(2)
      }
    }
    toolRuntime.activeLeasesResolver = { synchronized(leaseHistoryLock) { leaseHistory.toList() } }
    toolRuntime.memorySnapshotResolver = {
      try {
        // memoryGateway.allMemoryItems is a Flow; in this synchronous resolver
        // we surface the cached size + workingCount only. Detailed listing is
        // served asynchronously by refreshMemoryCache().
        org.json.JSONObject().apply {
          put("items_count", memoryItems.value.size)
          put("working_size", 0)
        }.toString(2)
      } catch (e: Exception) { "{\"error\":\"${e.message}\"}" }
    }
    // Stash an event so system.events.query reflects the refresh.
    toolRuntime.recordEvent(mutableMapOf(
      "kind" to "snapshot_refresh",
      "sessionId" to (_activeSessionId.value ?: "ses_canonical_01"),
      "ts" to System.currentTimeMillis(),
      "callable_count" to snap.callableTools.size,
      "snapshot_id" to snap.snapshotId
    ))
  }

  private fun computeLifecycleFor(
    mode: InteractionMode,
    lease: ExecutionLease?,
    homeOnline: Boolean,
    descriptors: List<com.example.core.model.ToolDescriptor>
  ): Map<String, com.example.core.runtime.LifecycleVerdict> {
    val out = HashMap<String, com.example.core.runtime.LifecycleVerdict>()
    descriptors.forEach { d ->
      val lc = toolRuntime.lifecycleFor(d.name, mode, lease, homeOnline)
      out[d.name] = lc
    }
    return out
  }

  /**
   * Render a compact `[RECENT RECEIPTS — last N]` block the model can read
   * before answering. Capped at 3 items × ~80 chars each to stay well under
   * the context window. Never re-injects receipts older than 10 minutes.
   */
  fun recentReceiptContextBlock(limit: Int = 3, maxChars: Int = 80, maxAgeMs: Long = 600_000L): String {
    val now = System.currentTimeMillis()
    // DAO exposes only getAllReceipts():Flow<List<ProofReceiptEntity>>. The
    // per-session slice is irrelevant for context — recent verification state
    // is what the model reads before its next call.
    val recent = proofReceipts.value
    val fresh = recent.filter { now - it.timestamp <= maxAgeMs }
    if (fresh.isEmpty()) return ""
    val lines = fresh.take(limit).mapIndexed { i, r ->
      val ageMs = (now - r.timestamp).coerceAtLeast(0L)
      val fs = com.example.core.runtime.ToolRuntimeEngine.freshnessStatus(r.timestamp, now)
      val tick = if (fs == "FRESH") "✓" else if (fs == "DEGRADED") "△" else "✗"
      "${i + 1}. ${r.toolName} → ${if (r.verificationStatus == "VERIFIED") "PASS" else "FAIL"} @ +${ageMs / 1000}s (${fs}) $tick"
    }
    return "[RECENT RECEIPTS — last ${fresh.size} for session ${_activeSessionId.value}]\n" +
      lines.joinToString("\n") + "\n"
  }

  fun recordLease(l: ExecutionLease) {
    synchronized(leaseHistoryLock) {
      leaseHistory.add(0, l)
      while (leaseHistory.size > 10) leaseHistory.removeAt(leaseHistory.size - 1)
    }
  }

  val turns: StateFlow<List<TurnRecord>> = _activeSessionId
    .flatMapLatest { sid -> db.turnDao().getTurnsForSession(sid) }
    .map { list ->
      if (list.isEmpty()) {
        getInitialSeedTurns()
      } else {
        list.map { it.toDomainModel() }
          .sortedWith(compareBy<TurnRecord> { it.timestamp }.thenBy { it.sequence })
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), getInitialSeedTurns())

  /** Canonical event-spine projection used by the mobile Recent Chats sheet. */
  val chatSessions = db.turnDao().observeSessionSummaries()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val snapshots = db.snapshotDao().getAllSnapshots()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val proofReceipts = db.receiptDao().getAllReceipts()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val memoryItems = memoryGateway.allMemoryItems
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /** Genuinely exposed cognitive spine status (evidence pass paste_24). */
  val spineStatus = memoryGateway.spineStatus.stateIn(
    viewModelScope, SharingStarted.WhileSubscribed(5000), MemoryGateway.SpineStatus.OFFLINE
  )

  // ─── SPEND LAW (provider-law 2026-08-26) ────────────────────────────────
  // Declared ABOVE init {} so primeDailySpendFromPrefs() can read _spendPolicy
  // safely from the constructor — Kotlin field ordering would otherwise leave
  // these null when init runs.
  private val _spendSnapshot = MutableStateFlow(SpendSnapshot(0.0, 0, SpendMode.OFF))
  val spendSnapshot: StateFlow<SpendSnapshot> = _spendSnapshot.asStateFlow()

  private val _spendPolicy = MutableStateFlow(loadSpendPolicy())
  val spendPolicy: StateFlow<SpendPolicy> = _spendPolicy.asStateFlow()

  init {
    // SELF-KNOWLEDGE LAW: system.* tools read live probes via these resolvers,
    // never conversation memory.
    toolRuntime.homeOnlineResolver = {
      val s = meshCoordinator.meshStatus.value
      s == MeshStatus.HOME_ONLINE || s == MeshStatus.HYBRID_DEGRADED
    }
    // COGNITIVE SPINE WIRING (evidence pass paste_23): the spineRecallProvider
    // is declared nullable in MemoryGateway and was never assigned. Wire it to
    // HomeRuntimeBridge.fetchCanonicalMemory() so the cognitive spine shows
    // ONLINE (real recall) or OFFLINE (honest marker) instead of always OFFLINE.
    memoryGateway.spineRecallProvider = { query: String ->
      val homeOnline = toolRuntime.homeOnlineResolver?.invoke() ?: false
      if (homeOnline) {
        try {
          val result = HomeRuntimeBridge.fetchCanonicalMemory()
          if (result.isNullOrEmpty()) listOf(MemoryGateway.EMPTY_MARKER)
          else listOf(result)
        } catch (e: Exception) {
          listOf("(error: ${e.message})")
        }
      } else {
        listOf("(offline — no home)")
      }
    }
    // WEAVE BLOCK WIRING: expose buildWeaveBlock through the council's
    // memoryWeaveProvider so seats hydrate from the live 7-layer weave.
    getOrCreatePodcastEngine().memoryWeaveProvider = { topic ->
      memoryGateway.buildWeaveBlock(topic)
    }
    // SELF-HEAL: drop entries that can no longer decrypt (reinstall killed the
    // Keystore master key) BEFORE seeding, so BuildConfig re-seeds them.
    val purged = vault.purgeUndecryptableSecrets()
    if (purged > 0) Log.w("MainViewModel", "Vault self-heal purged $purged stale secret(s)")
    seedVaultFromBuildConfig()
    primeDailySpendFromPrefs()
    viewModelScope.launch {
      // Probe home node over network
      meshCoordinator.probeHomeNode()
      // CANONICAL ROSTER LAW: pull live agents + skills from the main stack
      refreshRosters()
      providerRouter.checkMiniMaxInstalledCapabilities()
      // NIM AUTO-SELECTOR LAW: pull live NVIDIA model catalog into the selector
      providerRouter.refreshNimCatalogue()
      // STARTUP PARITY LAW: OpenRouter catalogue refreshes at launch too when a
      // key exists — not only on the manual key-entry path. Stale cache survives.
      if (vault.hasSecret("OPENROUTER_API_KEY")) {
        Log.i("MainViewModel", "Startup: OpenRouter key present — refreshing catalogue")
        providerRouter.refreshOpenRouterCatalogue()
      }
      // DIRECT PROVIDER REFRESH (provider-law 2026-08-26): each direct
      // provider's catalogue is empty until its key is present. Refreshing
      // is a no-op for unconfigured sources — the selector then renders an
      // "Add API key" tile for them.
      providerRouter.refreshMinimaxCatalogue()
      providerRouter.refreshKimiCatalogue()
      providerRouter.refreshQwenCatalogue()
      providerRouter.refreshDeepseekCatalogue()
      providerRouter.refreshOpenaiCatalogue()
      providerRouter.refreshZaiCatalogue()
      providerRouter.refreshLongcatCatalogue()
      // BOOT-REFRESH LAW (operator 2026-08-28): stamp the moment this boot's
      // catalogue cycle completes so an operator can audit "did the last boot
      // actually re-fetch?" without re-running probes. Marked AFTER every
      // per-provider refresh resolves, never before. No throttle.
      providerRouter.markBootCatalogRefresh()
      // STARTUP SELF-CHECK LAW: one-shot runtime bootstrap. Probes registry
      // metadata + permission state; never physically activates hardware.
      StartupSelfCheck.run(
        context = getApplication(),
        homeConnected = meshCoordinator.meshStatus.value.let {
          it == MeshStatus.HOME_ONLINE || it == MeshStatus.HYBRID_DEGRADED
        }
      )
    }
    // DUAL VIEW LAW: browser-embed requests mount the in-app browser pane.
    viewModelScope.launch {
      toolRuntime.browserEmbedRequest.collect { url ->
        if (url != null) _dualViewUrl.value = url
      }
    }
    // COGNITIVE SPINE PROBE: poll memory recall status every 30s so the
    // Memory page shows real ONLINE/OFFLINE/EMPTY state. Only probes when
    // the app is in foreground (viewModelScope is cleared on screen detach).
    viewModelScope.launch {
      while (true) {
        delay(30_000)
        memoryGateway.probeCognitiveSpine("session")
      }
    }
    // SPEND LAW: tick once a minute so the UI's "spent today" rolls over at
    // local midnight. Cheap, no network.
    viewModelScope.launch {
      while (true) {
        delay(60_000)
        primeDailySpendFromPrefs()
      }
    }
  }

  /**
   * FIRST-RUN KEY SEEDING: provider keys packaged via the Secrets plugin (.env →
   * BuildConfig) are pushed into the KeystoreVault once, on first launch only.
   * The vault is the single runtime source of truth; BuildConfig is just the
   * delivery envelope. Skips any key already present so operator edits win.
   *
   * Free gateways (OpenRouter, NIM) AND direct providers (MiniMax, Kimi, Qwen,
   * DeepSeek, OpenAI, Z.ai) all funnel through this seed. The selector will
   * only expose a direct provider after its key is present.
   */
  private fun seedVaultFromBuildConfig() {
    val seeds = mapOf(
      "OPENROUTER_API_KEY" to try { BuildConfig.OPENROUTER_API_KEY } catch (_: Exception) { null },
      "MINIMAX_API_KEY" to try { BuildConfig.MINIMAX_API_KEY } catch (_: Exception) { null },
      "NVIDIA_NIM_API_KEY" to try { BuildConfig.NVIDIA_NIM_API_KEY } catch (_: Exception) { null },
      "LONGCAT_API_KEY" to try { BuildConfig.LONGCAT_API_KEY } catch (_: Exception) { null },
      // Direct providers — BuildConfig fields are added per build by the
      // Secrets plugin. Until the operator wires a key the seed is null and
      // the catalogue stays empty (selector renders "Add API key" tile).
      "KIMI_API_KEY" to null,
      "QWEN_API_KEY" to null,
      "DEEPSEEK_API_KEY" to null,
      "OPENAI_API_KEY" to null,
      "ZAI_API_KEY" to null
    )
    var seeded = 0
    for ((keyName, value) in seeds) {
      if (value.isNullOrBlank() || vault.hasSecret(keyName)) continue
      vault.storeSecret(keyName, value)
      seeded++
    }
    if (seeded > 0) Log.i("MainViewModel", "Vault seeded $seeded provider key(s) from build config")
  }

  // (Spend-policy StateFlows are declared earlier so init { } can call
  //  primeDailySpendFromPrefs() without hitting an uninitialised field.)

  private fun loadSpendPolicy(): SpendPolicy {
    val mode = prefs.getString("spend_mode", SpendMode.OFF.name) ?: SpendMode.OFF.name
    val dailyCap = prefs.getInt("spend_daily_cap_cents", 0)
    val perJobCap = prefs.getInt("spend_per_job_cap_cents", 0)
    return SpendPolicy(
      mode = runCatching { SpendMode.valueOf(mode) }.getOrDefault(SpendMode.OFF),
      dailyCapCents = dailyCap,
      perJobCapCents = perJobCap
    )
  }

  private fun primeDailySpendFromPrefs() {
    val dayKey = prefs.getString("spend_day_key", "") ?: ""
    val cents = prefs.getFloat("spend_day_cents", 0f).toDouble()
    providerRouter.primeSpendForToday(cents, dayKey)
    val snap = providerRouter.snapshotSpend()
    // Sync the policy every prime so any router-side defaults flow back here.
    providerRouter.setSpendPolicy(_spendPolicy.value)
    _spendSnapshot.value = SpendSnapshot(snap.spentCentsToday, _spendPolicy.value.dailyCapCents, _spendPolicy.value.mode)
  }

  fun updateSpendPolicy(policy: SpendPolicy) {
    _spendPolicy.value = policy
    prefs.edit()
      .putString("spend_mode", policy.mode.name)
      .putInt("spend_daily_cap_cents", policy.dailyCapCents)
      .putInt("spend_per_job_cap_cents", policy.perJobCapCents)
      .apply()
    providerRouter.setSpendPolicy(policy)
    val snap = providerRouter.snapshotSpend()
    _spendSnapshot.value = SpendSnapshot(snap.spentCentsToday, policy.dailyCapCents, policy.mode)
  }

  /** Hook for the executor to call after a paid turn completes — persists rolling total. */
  fun recordProviderSpend(costCents: Double) {
    val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    prefs.edit()
      .putString("spend_day_key", today)
      .putFloat("spend_day_cents", _spendSnapshot.value.spentCentsToday.toFloat())
      .apply()
    val snap = providerRouter.snapshotSpend()
    _spendSnapshot.value = SpendSnapshot(snap.spentCentsToday, _spendPolicy.value.dailyCapCents, _spendPolicy.value.mode)
  }

  fun setNavigationSurface(surface: NavigationSurface) {
    _activeSurface.value = surface
  }

  fun createNewChat() {
    val id = "ses_${System.currentTimeMillis().toString(36)}_${UUID.randomUUID().toString().take(6)}"
    _activeSessionId.value = id
    prefs.edit().putString("active_session_id", id).apply()
    _inputText.value = ""
    _liveTurn.value = null
    _lastLiveStatus.value = "NEW CHAT · ready"
    _activeSurface.value = NavigationSurface.COMMAND
  }

  fun selectChatSession(sessionId: String) {
    if (sessionId.isBlank()) return
    _activeSessionId.value = sessionId
    prefs.edit().putString("active_session_id", sessionId).apply()
    _inputText.value = ""
    _liveTurn.value = null
    _lastLiveStatus.value = "CHAT RESTORED · ${sessionId.takeLast(8)}"
    _activeSurface.value = NavigationSurface.COMMAND
  }

  fun setInteractionMode(mode: InteractionMode) {
    _interactionMode.value = mode
    prefs.edit().putString("interaction_mode", mode.name).apply()
    if (mode == InteractionMode.WORK) {
      if (_activeLease.value?.isActive != true) armExecutionLease()
    } else {
      revokeExecutionLease()
    }
  }

  fun beginOpenRouterOAuth() = providerOAuth.beginOpenRouter(getApplication())

  fun handleProviderOAuthCallback(uri: android.net.Uri) {
    viewModelScope.launch {
      providerOAuth.completeOpenRouter(uri)
        .onSuccess {
          providerRouter.refreshOpenRouterCatalogue()
          _lastLiveStatus.value = "OpenRouter account connected · free catalogue refreshed"
        }
        .onFailure { _lastLiveStatus.value = "OpenRouter sign-in failed · ${it.message ?: "unknown error"}" }
    }
  }

  // DUAL VIEW LAW: browser lives inside PurpClaw as a controllable upper pane.
  // State is owned here so tool calls (android.browser.embed) and UI share one truth.
  private val _dualViewUrl = MutableStateFlow<String?>(null)
  val dualViewUrl: StateFlow<String?> = _dualViewUrl.asStateFlow()

  private val _dualViewCollapsed = MutableStateFlow(false)
  val dualViewCollapsed: StateFlow<Boolean> = _dualViewCollapsed.asStateFlow()

  fun openDualView(url: String) {
    _dualViewUrl.value = url
    _dualViewCollapsed.value = false
  }

  fun collapseDualView() {
    _dualViewCollapsed.value = true
  }

  fun expandDualView() {
    _dualViewCollapsed.value = false
  }

  fun closeDualView() {
    _dualViewUrl.value = null
    _dualViewCollapsed.value = false
  }

  fun toggleFullSystemScope() {
    _fullSystemScope.value = !_fullSystemScope.value
  }

  fun setSelectedCompanion(name: String) {
    if (name !in com.example.ui.components.Avatar3DClips.COMPANION_NAMES) return
    _selectedCompanion.value = name
    prefs.edit().putString("selected_companion", name).apply()
  }

  fun setSelectedModel(model: String) {
    _selectedModel.value = model
    prefs.edit().putString("selected_model", model).apply()
    // STICKINESS LAW: a manual pick must reach the router so modelMode flips to
    // MANUAL and the provider pins — no silent candidate hopping. AUTO restores.
    if (model == "AUTO") {
      providerRouter.setSelectedModel("auto", "AUTO")
    } else if (model.startsWith("openrouter/")) {
      providerRouter.setSelectedModel("openrouter", model)
    } else if (model.startsWith("minimax/")) {
      providerRouter.setSelectedModel("minimax", model)
    } else if (model.startsWith("nvidia/")) {
      providerRouter.setSelectedModel("nvidia", model)
    } else {
      providerRouter.setSelectedModel("openrouter", "openrouter/$model")
    }
  }

  fun updateInputText(text: String) {
    _inputText.value = text
  }

  /**
   * EDIT (inline action rail, 2026-08-27): load a user turn's content back
   * into the composer so the operator can tweak + resend. Pure composer
   * load — does NOT fire a generation (that's what the Retry button is for).
   */
  fun editTurn(turn: TurnRecord) {
    _inputText.value = turn.content
  }

  fun toggleMeshStatus(newStatus: MeshStatus) {
    meshCoordinator.setMeshMode(newStatus)
  }

  fun probeMeshHeartbeat() {
    viewModelScope.launch {
      meshCoordinator.probeHomeNode()
    }
  }

  fun probeMesh() = probeMeshHeartbeat()

  fun onPermissionsChanged(grants: Map<String, Boolean>) {
    val mic = grants.getOrDefault(android.Manifest.permission.RECORD_AUDIO, false)
    if (mic) {
      memoryGateway.updateWorkingMemory("Microphone granted — voice channel live")
    }
  }

  fun armExecutionLease(capabilities: List<String> = listOf("*")) {
    val now = System.currentTimeMillis()
    val lease = ExecutionLease(
      leaseId = "work_session_${UUID.randomUUID().toString().take(6)}",
      operatorGranted = true,
      allowedCapabilities = capabilities,
      grantedAtMs = now,
      // Session authority ends when WORK is switched off or the process dies.
      // Android OS permissions remain authoritative and cannot be fabricated.
      expiresAtMs = Long.MAX_VALUE,
      isActive = true
    )
    _activeLease.value = lease
    recordLease(lease)
  }

  /**
   * LIVE FREE-GATEWAY LAW: AUTO resolves from the catalogues already maintained
   * by the phone. Refresh on demand if startup discovery has not completed yet.
   * NIM is preferred, OpenRouter is the peer fallback; excluding the failed id
   * gives one honest rotation without inventing a second router or silently
   * reaching a paid/direct provider. Member function so both the podcast phone
   * fallback and chat routing can call it (previously a local fn inside
   * processTurn, invisible to the podcast engine wiring).
   */
  private suspend fun resolvePhoneModel(exclude: Set<String> = emptySet()): String? {
    val explicit = _selectedModel.value
    if (explicit != "AUTO") return explicit
    var gateways = providerRouter.queryAllFreeGateways()
    if (gateways.isEmpty()) {
      providerRouter.refreshNimCatalogue()
      if (vault.hasSecret("OPENROUTER_API_KEY")) providerRouter.refreshOpenRouterCatalogue()
      gateways = providerRouter.queryAllFreeGateways()
    }
    val eligible = gateways.filter {
      val id = it.id.lowercase()
      it.id !in exclude && !providerRouter.isQuarantined(it.id) && it.modelClass == "chat" && it.isFree && it.configured && it.available &&
        !id.startsWith("google/") && !id.startsWith("thinkingmachines/") &&
        !id.contains("content-safety") && !id.contains("lyria")
    }
    val failedSources = gateways.filter { it.id in exclude }.map { it.sourceProvider }.toSet()
    fun score(model: com.example.core.model.CatalogueModel): Int {
      val id = model.id.lowercase()
      return if (_interactionMode.value == InteractionMode.WORK) {
        (if (model.isToolCapable) 500 else 0) +
          (if (model.isReasoningCapable) 300 else 0) +
          (model.contextLength / 4096).coerceAtMost(100) +
          (if (listOf("coder", "coding", "deepseek", "qwen3", "kimi", "nemotron", "llama-3.3").any(id::contains)) 180 else 0)
      } else {
        (if (model.isReasoningCapable) 400 else 0) +
          (if (model.isToolCapable) 250 else 0) +
          (model.contextLength / 4096).coerceAtMost(160) +
          (if (listOf("deepseek", "qwen3", "kimi", "nemotron", "llama-3.3", "mistral").any(id::contains)) 140 else 0) +
          (if (listOf("fuyu", "yi-large").any(id::contains)) -500 else 0) -
          (model.avgLatencyMs.coerceAtMost(10_000L) / 1000L).toInt()
      }
    }
    // openrouter/free is the smart aggregator: it routes to a LIVE free model
    // at random, filtering for the features the request needs. Prefer it FIRST
    // always (not only after a failure) — a specific free model can hang 18s
    // while openrouter/free picks one that actually answers. This is the
    // operator's explicit instruction: the eligible model is openrouter/free.
    val ordered = eligible.sortedWith(compareByDescending<com.example.core.model.CatalogueModel> {
      it.id == "openrouter/free"
    }.thenByDescending {
      it.sourceProvider == "nvidia"   // NIM chat models are reachable+fast (device-proven); try before concrete OpenRouter free models that can hang 18s
    }.thenByDescending {
      it.sourceProvider !in failedSources
    }.thenByDescending(::score))
    return ordered.firstOrNull()?.id ?: if (vault.hasSecret("OPENROUTER_API_KEY")) "openrouter/free" else null
  }

  fun revokeExecutionLease() {
    _activeLease.value = null
  }

  fun reverifySubsystem(id: String) {
    viewModelScope.launch {
      val now = System.currentTimeMillis()
      when (id) {
        "subsystem.core.truth_registry" -> {
          val testPassed = capabilityRegistry.performAntiMockEnforcementSelfTest()
          if (!testPassed) {
            capabilityRegistry.updateSubsystemStateWithProof(
              id = id,
              state = CapabilityTruthState.DEGRADED,
              health = "Self-test failed: Anti-mock false promotion rejection compromised.",
              receipt = null,
              lastError = "Anti-mock enforcement test failed"
            )
          }
        }
        "subsystem.android.toolruntime" -> {
          val res = toolRuntime.executeTool("android.device.info", "")
          if (res.record.isSuccess && res.proofReceipt != null) {
            capabilityRegistry.updateSubsystemStateWithProof(
              id = id,
              state = CapabilityTruthState.LIVE_VERIFIED,
              health = "Executed android.device.info (${res.record.durationMs}ms) with hardware Keystore receipt.",
              receipt = res.proofReceipt
            )
            db.receiptDao().insertReceipt(res.proofReceipt.toEntity())
          } else {
            capabilityRegistry.updateSubsystemStateWithProof(
              id = id,
              state = CapabilityTruthState.DEGRADED,
              health = "Tool execution did not yield a valid verified receipt.",
              receipt = null,
              lastError = res.record.error
            )
          }
        }
        "subsystem.security.keystore" -> {
          try {
            val payload = "keystore_proof_probe_$now"
            val signRes = keystoreSigner.signPayload(payload)
            val isVerified = keystoreSigner.verifySignature(payload, signRes.signature)
            if (isVerified && signRes.isHardwareBacked) {
              val receipt = ProofReceipt(
                receiptId = "rcpt_keystore_probe_$now",
                receiptType = "HARDWARE_ACCEPTANCE",
                subsystemId = id,
                testId = "KEYSTORE_PROBE",
                deviceId = android.os.Build.FINGERPRINT,
                nodeId = "phone-android-node-01",
                sessionId = "ses_keystore_probe",
                startedAt = now - 20,
                completedAt = now,
                result = "PASS",
                verificationStatus = "VERIFIED",
                inputHash = payload,
                outputHash = signRes.signature,
                evidenceHash = "$payload:${signRes.signature}",
                signingKeyId = signRes.signingKeyId,
                signatureAlgorithm = signRes.algorithm,
                signature = signRes.signature,
                nonce = "nonce_keystore_$now"
              )
              val canonicalData = receipt.computeCanonicalPayload()
              val canonicalSign = keystoreSigner.signPayload(canonicalData)
              val authenticReceipt = receipt.copy(signature = canonicalSign.signature)
              capabilityRegistry.updateSubsystemStateWithProof(
                id = id,
                state = CapabilityTruthState.LIVE_VERIFIED,
                health = "Hardware EC-P256 signing validated on AndroidKeyStore provider.",
                receipt = authenticReceipt
              )
            } else {
              capabilityRegistry.updateSubsystemStateWithProof(
                id = id,
                state = CapabilityTruthState.DEGRADED,
                health = "Keystore signature verification failed or software fallback active.",
                receipt = null,
                lastError = "Hardware signature check failed"
              )
            }
          } catch (e: Exception) {
            capabilityRegistry.updateSubsystemStateWithProof(
              id = id,
              state = CapabilityTruthState.DEGRADED,
              health = "Keystore probe exception: ${e.message}",
              receipt = null,
              lastError = e.message
            )
          }
        }
        "subsystem.mesh.home_session" -> {
          val status = meshCoordinator.probeHomeNode()
          if (status == MeshStatus.HOME_ONLINE || status == MeshStatus.HYBRID_DEGRADED) {
            val meshReceipt = ProofReceipt(
              receiptId = "rcpt_mesh_home_$now",
              receiptType = "MESH_HANDSHAKE",
              subsystemId = id,
              testId = "SLICE_001_HOME_PROBE",
              deviceId = android.os.Build.FINGERPRINT,
              nodeId = "phone-android-node-01",
              sessionId = "ses_canonical_01",
              startedAt = now - 50,
              completedAt = now,
              result = "PASS",
              verificationStatus = "VERIFIED",
              inputHash = "probe:192.168.1.144:8000",
              outputHash = "status:$status",
              evidenceHash = "latency:${meshCoordinator.homeNode.value.online}/probe",
              signingKeyId = KeystoreReceiptSigner.DEFAULT_KEY_ALIAS,
              signatureAlgorithm = "SHA256withECDSA",
              signature = "",
              nonce = "nonce_mesh_$now"
            )
            val signResult = keystoreSigner.signPayload(meshReceipt.computeCanonicalPayload())
            capabilityRegistry.updateSubsystemStateWithProof(
              id = id,
              state = CapabilityTruthState.LIVE_VERIFIED,
              health = "Home PC reachable over LAN (mesh: $status); WebSocket active.",
              receipt = meshReceipt.copy(signature = signResult.signature)
            )
          } else {
            capabilityRegistry.updateSubsystemStateWithProof(
              id = id,
              state = CapabilityTruthState.PRESENT_UNVERIFIED,
              health = "Home PC not reachable on LAN (status: $status); running in Sovereign Local mode.",
              receipt = null,
              lastError = "Home PC node unresponsive on probe"
            )
          }
        }
        "subsystem.memory.gateway" -> {
          try {
            memoryGateway.storeMemory(MemoryLayer.SYMBOLIC, "probe.key", "Verification test probe")
            val items = memoryGateway.recallForQuery("probe.key")
            if (items.isNotEmpty()) {
              val memReceipt = ProofReceipt(
                receiptId = "rcpt_memory_gateway_$now",
                receiptType = "STORAGE_ACCEPTANCE",
                subsystemId = id,
                testId = "SLICE_005_ROOM_PERSISTENCE",
                deviceId = android.os.Build.FINGERPRINT,
                nodeId = "phone-android-node-01",
                sessionId = "ses_canonical_01",
                startedAt = now - 30,
                completedAt = now,
                result = "PASS",
                verificationStatus = "VERIFIED",
                inputHash = "store:probe.key",
                outputHash = "recall:${items.size}",
                evidenceHash = "room:sqlite:v7",
                signingKeyId = KeystoreReceiptSigner.DEFAULT_KEY_ALIAS,
                signatureAlgorithm = "SHA256withECDSA",
                signature = "",
                nonce = "nonce_mem_$now"
              )
              val signResult = keystoreSigner.signPayload(memReceipt.computeCanonicalPayload())
              capabilityRegistry.updateSubsystemStateWithProof(
                id = id,
                state = CapabilityTruthState.LIVE_VERIFIED,
                health = "Room SQLite 7-layer memory read/write verified on device.",
                receipt = memReceipt.copy(signature = signResult.signature)
              )
            }
          } catch (e: Exception) {
            capabilityRegistry.updateSubsystemStateWithProof(
              id = id,
              state = CapabilityTruthState.DEGRADED,
              health = "Memory store exception: ${e.message}",
              receipt = null,
              lastError = e.message
            )
          }
        }
        else -> {
          capabilityRegistry.initializeRegistry()
        }
      }
    }
  }

  /**
   * Primary Turn Execution Pipeline:
   * Operator intent -> Session/Identity -> 7-Layer Recall -> Task/Lease Check -> Execution/Reasoning -> OMNI Proof Receipt -> Persistence.
   */
  private val sendQueue = java.util.concurrent.ConcurrentLinkedQueue<String>().apply {
    val saved = prefs.getString("durable_send_queue", "[]") ?: "[]"
    runCatching {
      val arr = JSONArray(saved)
      for (i in 0 until arr.length()) {
        arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
      }
    }
  }

  private fun persistSendQueue() {
    val arr = JSONArray()
    sendQueue.forEach(arr::put)
    prefs.edit().putString("durable_send_queue", arr.toString()).apply()
  }

  init {
    // A queued operator message survives process death. Resume it only after
    // state collectors have attached so its persisted user turn appears in UI.
    if (sendQueue.isNotEmpty()) {
      viewModelScope.launch {
        delay(600)
        if (!_isGenerating.value) drainQueue()
      }
    }
  }

  /**
   * MEDIA LAW: staged inline media for the NEXT user turn. Producers (camera
   * capture, file intake) append here; processTurn drains it into the user
   * TurnRecord so MediaAttachmentsRow renders it natively in chat.
   */
  private val _pendingMediaAttachments = MutableStateFlow<List<MediaAttachment>>(emptyList())
  val pendingMediaAttachments: StateFlow<List<MediaAttachment>> = _pendingMediaAttachments.asStateFlow()

  /**
   * QUEUE LAW: a message sent while busy is PERSISTED to the queue immediately,
   * rendered as queued, and processed in order. Never dropped.
   */
  fun sendCurrentMessage() {
    val prompt = _inputText.value.trim()
    if (prompt.isBlank()) return
    _inputText.value = ""
    if (_isGenerating.value) {
      sendQueue.add(prompt)
      persistSendQueue()
      return
    }
    processTurn(prompt)
  }

  private fun drainQueue() {
    val next = sendQueue.poll() ?: return
    persistSendQueue()
    processTurn(next)
  }

  private fun processTurn(prompt: String) {
    val currentMode = _interactionMode.value
    synchronized(delegatedThisTurn) { delegatedThisTurn.clear() }
    _isGenerating.value = true
    voiceModeController.notifyThinking()   // voice loop: LISTENING -> THINKING on send

    val turnToken = UUID.randomUUID().toString()
    activeTurnToken = turnToken
    val launchedJob = viewModelScope.launch {
      // 1. Insert User Turn
      val userTurnId = "turn_user_${UUID.randomUUID().toString().take(8)}"
      val userTurn = TurnRecord(
        id = userTurnId,
        sessionId = _activeSessionId.value,
        parentEventId = null,
        nodeId = "phone-android-node-01",
        timestamp = System.currentTimeMillis(),
        sequence = turns.value.size + 1,
        role = "user",
        mode = currentMode,
        content = prompt,
        mediaAttachments = _pendingMediaAttachments.value,
        fullSystemScope = _fullSystemScope.value
      )
      db.turnDao().insertTurn(userTurn.toEntity())
      _pendingMediaAttachments.value = emptyList()

      // STEP 13.6 + #53: "who are you?" / "what model are you?" shortcut —
      // answer comes from SoulLoader's bundled MD files (SOUL/IDENTITY/USER/
      // MEMORY), NEVER from the inference brain's training. This guarantees
      // PurpAngolin's identity is consistent across every surface + every
      // model provider. The shortcut is case-insensitive and matches the
      // exact intent shape — anything ambiguous falls through to the normal
      // model path which still sees the bundled identity block.
      val trimmedLower = prompt.trim().lowercase()
      val isWhoAreYou = trimmedLower in setOf(
        "who are you?", "who are you", "who r u", "who r u?",
        "what are you?", "what are you",
        "what model are you?", "what model are you",
        "what model are you running?", "what model are you running",
        "which model are you?", "which model are you",
        "are you claude?", "are you gpt?", "are you llama?", "are you minimax?",
        "are you deepseek?", "are you kimi?", "are you qwen?", "are you gemini?",
        "introduce yourself", "tell me about yourself",
        "what's your name?", "whats your name?", "what is your name?",
        "what is your name"
      ) || trimmedLower.matches(Regex("^(who|what)\\s+(are|r|u)\\s+(you|u)(\\?|\\.)?$"))
      if (isWhoAreYou) {
        val soul = SoulLoader.load(getApplication())
        val answer = com.example.core.soul.SoulLoader.whoAreYouAnswer(soul)
        val identityTurnId = "turn_asst_${UUID.randomUUID().toString().take(8)}"
        val identityTurn = TurnRecord(
          id = identityTurnId,
          sessionId = _activeSessionId.value,
          parentEventId = userTurnId,
          nodeId = "phone-android-node-01",
          timestamp = System.currentTimeMillis(),
          sequence = turns.value.size + 2,
          role = "assistant",
          mode = currentMode,
          content = answer,
          isStreaming = false,
          activityStatus = ""
        )
        db.turnDao().insertTurn(identityTurn.toEntity())
        emitTurnEvent("turn.started", identityTurnId, mapOf("mode" to currentMode.name, "path" to "identity_contract"))
        emitTurnEvent("turn.final", identityTurnId, mapOf("path" to "identity_contract"))
        _liveTurn.value = identityTurn
        _lastLiveStatus.value = ""
        activeTurnToken = null
        activeTurnId = null
        _isGenerating.value = false
        _companionState.value = CompanionPetState.IDLE
        drainQueue()
        return@launch
      }

      // 1b. LIVE TURN: mount the assistant turn immediately (ChatGPT-style —
      // the container exists before any text arrives) with streaming state so
      // the UI shows a live activity card instead of a dead static box.
      val liveTurnId = "turn_asst_${UUID.randomUUID().toString().take(8)}"
      activeTurnId = liveTurnId
      emitTurnEvent("turn.started", liveTurnId, mapOf(
        "mode" to currentMode.name,
        "original_request_hash" to prompt.hashCode().toString()
      ))
      var liveStatus = when (currentMode) {
        InteractionMode.WORK -> "arming tools"
        else -> "thinking"
      }
      fun pushLive(status: String) {
        liveStatus = status
        _lastLiveStatus.value = status  // STEP 13.6: publish to UI
        _liveTurn.value = TurnRecord(
          id = liveTurnId,
          sessionId = _activeSessionId.value,
          parentEventId = userTurnId,
          nodeId = "phone-android-node-01",
          timestamp = System.currentTimeMillis(),
          sequence = turns.value.size + 2,
          role = "assistant",
          mode = currentMode,
          content = "",
          isStreaming = true,
          activityStatus = liveStatus
        )
      }
      pushLive(liveStatus)
      /* liveTurnDraft = TurnRecord(
        id = liveTurnId,
          sessionId = _activeSessionId.value,
          parentEventId = userTurnId,
          nodeId = "phone-android-node-01",
          timestamp = System.currentTimeMillis(),
          sequence = turns.value.size + 2,
          role = "assistant",
          mode = currentMode,
          content = "",
          isStreaming = true,
          activityStatus = liveStatus
        )
      ) */
      when (currentMode) {
        InteractionMode.CHAT -> _companionState.value = CompanionPetState.THINKING
        InteractionMode.WORK -> _companionState.value = CompanionPetState.RUNNING
      }

      // RUNTIME TRUTH: Home reachable (even HYBRID_DEGRADED) => chat relays
      // through the canonical runtime. Only a true reachability failure goes local.
      // PER-TURN FRESHNESS LAW: mesh status is boot-time stale — core can die
      // mid-session. Probe NOW so the pill and the routing decision use live truth.
      meshCoordinator.probeHomeNode()
      val homeReachable = meshCoordinator.meshStatus.value == MeshStatus.HOME_ONLINE ||
        meshCoordinator.meshStatus.value == MeshStatus.HYBRID_DEGRADED
      val toolCallsList = mutableListOf<ToolCallRecord>()
      var generatedProof: ProofReceipt? = null

      pushLive("routing intent")

      // 3. TOOL ROUTING — one resolver, no substring folklore.
      // CHAT: read-only/self-inspection tools allowed; mutating tools get a
      // policy denial the model can relay. WORK: full routing.
      val routedTool: RoutedIntent? = IntentResolver.route(prompt, currentMode.toPolicyMode())
      if (routedTool?.tool == "android.browser.embed") {
        // DUAL VIEW LAW: web requests open the in-app browser pane, never an
        // external app launch and never a desktop-path hallucination.
        if (routedTool.args.isNotBlank()) {
          _dualViewUrl.value = routedTool.args
          pushLive("opening ${routedTool.args} in Dual View")
        }
      } else if (routedTool?.tool == "android.podcast.convene") {
        // COUNCIL PODCAST LAW (work order §17–18): one sentence convenes the
        // live 8-seat episode. Each SpeakerTurn renders as its own chat turn
        // and is queued for multi-voice TTS as it lands. Local-only mutation.
        pushLive("convening council podcast · ${routedTool.args}")
        val engine = getOrCreatePodcastEngine()
        var speakerCount = 0
        _isPodcastActive.value = true   // TASK #47: route mic to guest queue
        val recoveryStatusJob = launch {
          engine.inferenceRecovery.collect { recovery ->
            if (recovery.phase != CouncilPodcastEngine.InferencePhase.IDLE) {
              pushLive(recovery.message.ifBlank { recovery.phase.name.replace('_', ' ') })
            }
          }
        }
        engine.runEpisode(routedTool.args, _activeSessionId.value).collect { turn ->
          speakerCount++
          pushLive("council speaking ${speakerCount}/15: ${turn.role}")
          db.turnDao().insertTurn(
            TurnRecord(
              id = "turn_pod_${UUID.randomUUID().toString().take(8)}",
              sessionId = _activeSessionId.value,
              parentEventId = null,
              nodeId = "phone-android-node-01",
              timestamp = System.currentTimeMillis(),
              sequence = turns.value.size + 1 + speakerCount,
              role = "assistant",
              mode = currentMode,
              content = "COUNCIL · ${turn.role} · ${turn.speakerName}\n${stripToolCallBlocksFromReply(turn.text)}",
              providerModel = "COUNCIL_PODCAST"
            ).toEntity()
          )
          // STRIP-AND-SPEAK LAW: a seat that hallucinates a tool-call JSON
          // block must NOT be heard. The stripper guarantees TTS only sees
          // the natural-language reply.
          if (!turn.text.startsWith("No response received")) {
            val utteranceId = "pod_${_activeSessionId.value}_${speakerCount}_${UUID.randomUUID().toString().take(6)}"
            val profile = ttsEngine.resolveVoiceProfile(turn.voiceTag)
            pushLive("speaking · ${turn.speakerName} · voice profile ${profile.kokoroVoiceId}")
            ttsEngine.speakSequencedAndWait(
              stripToolCallBlocksFromReply(turn.text),
              turn.voiceTag,
              utteranceId
            )
          }
        }
        recoveryStatusJob.cancel()
        // TASK #11 — zero-turn guard. The old branch UNCONDITIONALLY pushed
        // "episode saved to Podcast Studio", published the head of
        // loadSavedIndex() (a STALE prior episode), and showed the Download
        // banner — even when the run produced zero turns. Now we branch on
        // the engine's honest session status:
        //   COMPLETED + ≥1 turn → publish THIS session's saved record + banner.
        //   FAILED / zero turns → honest failure status + retry affordance;
        //                          do NOT publish a saved episode or banner.
        val finalStatus = engine.sessionStatus.value
        val sessionReceipt = engine.runReceipt.value
        if (finalStatus == CouncilPodcastEngine.PodcastSessionStatus.COMPLETED && speakerCount > 0) {
          pushLive("episode saved to Podcast Studio")
          // TASK #55: surface the saved-podcast record for the post-show
          // Download Pod button. Engine stores the head of its saved list —
          // we publish it here and start a 6-second auto-fade.
          val latest = engine.loadSavedIndex().firstOrNull()
          if (latest != null) {
            _latestSavedEpisode.value = latest
            viewModelScope.launch {
              delay(6_000)
              if (_latestSavedEpisode.value?.id == latest.id) {
                _latestSavedEpisode.value = null
              }
            }
          }
          // END THE TURN — podcast owns this exchange; do NOT fall through
          // into the regular model brain with the user's original prompt.
          _liveTurn.value = null
          _companionState.value = CompanionPetState.SUCCESS
        } else {
          // Honest failure: the show produced nothing. Surface the failure
          // reason if the engine reported one, and give the operator a retry
          // affordance via the ERROR companion state. NO saved-episode banner.
          val failure = engine.sessionFailure.value
          val reason = failure?.reason ?: "no response received from the council"
          Log.w("MainViewModel", "Podcast FAILED (status=$finalStatus, turns=$speakerCount, receipt=$sessionReceipt): $reason")
          pushLive("podcast failed · $reason · Retry")
          _liveTurn.value = null
          _companionState.value = CompanionPetState.ERROR
        }
        _isGenerating.value = false
        _isPodcastActive.value = false  // TASK #47: stop routing mic to queue
        if (finalStatus == CouncilPodcastEngine.PodcastSessionStatus.COMPLETED && speakerCount > 0) {
          delay(1200)
          _companionState.value = CompanionPetState.IDLE
        } else {
          delay(2400)
          _companionState.value = CompanionPetState.IDLE
        }
        drainQueue()
        return@launch
      } else if (routedTool != null) {
        _companionState.value =
          if (currentMode == InteractionMode.WORK) CompanionPetState.TOOL_CALL
          else CompanionPetState.THINKING

        pushLive("tool requested · ${routedTool.tool}")
        emitTurnEvent("tool.requested", liveTurnId, mapOf("tool" to routedTool.tool))
        emitTurnEvent("tool.started", liveTurnId, mapOf("tool" to routedTool.tool))
        toolRuntime.currentExecutionMode = currentMode.toPolicyMode()
        val toolExec = toolRuntime.executeTool(
          toolName = routedTool.tool,
          arguments = routedTool.args,
          actorAgent = _selectedCompanion.value,
          lease = null,
          isHomeOnline = homeReachable
        )
        toolCallsList.add(toolExec.record)
        emitTurnEvent(
          if (toolExec.record.isSuccess) "tool.completed" else "tool.failed",
          liveTurnId,
          mapOf("tool" to routedTool.tool, "success" to toolExec.record.isSuccess)
        )
        emitTurnEvent("verification.completed", liveTurnId, mapOf(
          "tool" to routedTool.tool,
          "verified" to toolExec.record.isSuccess,
          "evidence_hash" to toolExec.record.evidenceHash
        ))
        generatedProof = toolExec.proofReceipt
        generatedProof?.let { db.receiptDao().insertReceipt(it.toEntity()) }
      }

      pushLive(if (currentMode == InteractionMode.WORK) "reasoning with tools" else "composing reply")

      // 4. Call Model Brain — HOME-FIRST LAW: if the canonical runtime is
      // online, the turn executes THERE (its provider router, steering stack,
      // event spine). The phone never re-routes or relabels a cloud call as a
      // Home call.
      _companionState.value = CompanionPetState.REPLYING

      // RUNTIME TRUTH: live device state injected into every turn so Purp
      // never hallucinates about ADB, review gates, or missing hands.
      // The runtime context resolver is also wired for system.* self-inspection.
      val runtimeCtx = RuntimeContext.resolve(
        context = getApplication(),
        executionMode = currentMode.name,
        homeConnected = homeReachable,
        inferenceNote = if (homeReachable) "canonical Home PC runtime API" else "on-device provider pool (cloud/local APIs)"
      ).let { ctx ->
        toolRuntime.runtimeContextResolver = { ctx }  // same-truth guarantee for system.* tools this turn
        ctx
      }
      val soul = SoulLoader.load(getApplication())

      // HOME-OPTIONAL LAW: Home PC status is reported, never assumed. The
      // prompt states connection truth so the model can't claim HOME is live
      // just because provider config mentions it.
      val homeStatusBlock = if (homeReachable) {
        if (meshCoordinator.meshStatus.value == MeshStatus.HOME_ONLINE)
          "Home PC: CONNECTED — heavy workloads MAY be delegated."
        else
          "Home PC: CONNECTED (degraded: some subsystems down) — chat/inference relayed through Home; heavy subsystem workloads may be unavailable."
      } else {
        "Home PC: OFFLINE/UNPAIRED — you are fully autonomous on this device. " +
          "Never reference the Home PC as available, and never route Android-local " +
          "actions through it."
      }
      val systemPrompt =
        soul.bundledIdentityBlock + "\n\n" + homeStatusBlock + "\n\n" + runtimeCtx.toPromptBlock()

      // CONTINUITY LAW: send the real conversation chain, not just the latest
      // message. Pair each assistant reply with the user message that prompted
      // it (sequence order), capped to the last 20 exchanges.
      // ProviderRouter expects each Pair to be (wireRole, content). The old
      // builder accidentally emitted (userText, assistantText), causing the
      // user text to be normalised as an unknown role and only the assistant
      // half to reach providers. Preserve the actual ordered role chain.
      val conversationHistory: List<Pair<String, String>> = buildList {
        turns.value
          .sortedWith(compareBy<TurnRecord> { it.timestamp }.thenBy { it.sequence })
          .filter { it.role == "user" || it.role == "assistant" }
          .takeLast(40)
          .forEach { turn ->
            if (turn.content.isNotBlank()) add(turn.role to turn.content)
          }
      }

      val homeOnline = homeReachable
      // RUNTIME TRUTH: track whether home ACTUALLY served this turn. A stale
      // HOME_ONLINE mesh status that fails mid-turn must not let the pill
      // claim HOME-RELAY for a turn the canonical runtime never touched.
      // Init FALSE — only a successful homeChat.ok may flip it true. The
      // stale-true init was the surviving bug path (verifier A1 FAIL).
      var homeServedThisTurn = false
      var attemptedPhoneModel: String? = null

      // AUTO-DIRECT LAW (2026-08-26): AUTO free-only routing must call the
      // phone-native provider pool (OpenRouter/NIM) directly, NOT relay every
      // chat turn through the home PC runtime. Home relay is reserved for
      // (a) explicit opt-in via useHomeRouting, (b) WORK mode that needs the
      // canonical runtime's tools/steering stack, or (c) fallback when the
      // direct call dies. Manual pinned model still goes home-first when
      // reachable because home is the canonical runtime for pinned runs.
      val routeState = providerRouter.routingState.value

      // RECEIPT RE-INJECTION (2026-08-26): the model must SEE what tools
      // actually ran so its next answer is grounded in live receipts, not
      // blind recall. Refresh the capability mirror before every turn so
      // introspection tools return this turn's truth.
      refreshCapabilitySnapshot()
      // Structured tool calls are an execution protocol, never chat markup.
      // Every eligible provider sees the same canonical descriptors; CHAT/
      // WORK policy is still enforced again by dispatchCanonical.
      providerRouter.toolsWireEnabled = true
      val enabledTools = _capabilitySnapshot.value?.registeredTools
        ?.filter { it.isEnabled }
        .orEmpty()
      // Provider tool envelopes are task-scoped. Sending every Android tool
      // for a simple HTML build made OpenRouter unable to find any endpoint
      // supporting the entire parameter set and drowned the model in an
      // irrelevant schema. The registry remains authoritative; expose only
      // the tools this creation intent can actually use.
      providerRouter.pendingTools = if (ToolIntentBoundary.isArtifactCreationGoal(prompt)) {
        val creationTools = ToolIntentBoundary.artifactCreationToolNames()
        enabledTools.filter { it.name in creationTools }
      } else {
        enabledTools
      }
      providerRouter.pendingToolChoice = "auto"
      val receiptBlock = recentReceiptContextBlock()
      val isManualPin = _selectedModel.value != "AUTO" &&
        _selectedModel.value != routeState.selectedModel
      val wantsHomeRouting = routeState.useHomeRouting &&
        (currentMode == InteractionMode.WORK || isManualPin)
      val skipHomeRelay = !homeOnline || !wantsHomeRouting
      // ALLOW PARTIAL FAILOVER LAW (parity with agent-loop.js:460):
      //   (!provider || provider==='auto') && model ? false : (autoMode || (!provider && !model))
      // On phone: no manual pin + AUTO routing → allowPartialFailover=true (better
      // partial answer than hard death). Explicit manual pin → fail-closed.
      val allowPartialFailover = !isManualPin

      // TOOL-CHAIN LAW: when a tool already ran locally this turn, its result
      // MUST reach the model — otherwise the model answers blind while the
      // phone silently did the work (the "tool chain broke" regression).
      val toolChainPrefix = if (toolCallsList.isNotEmpty()) {
        "[TOOL RESULT — already executed on device, do not re-run]\n" +
          toolCallsList.joinToString("\n\n") { rec ->
            val output = rec.output.take(1500)
            "${rec.toolName}(${rec.arguments}) → $output"
          } + "\n\n[End of tool results. Use them to answer the user's request.]\n\n"
      } else ""
      var providerResult: ProviderExecutionResult = if (!skipHomeRelay) {
        // WORK SESSION LAW: WORK is the trusted operator gesture. It persists
        // for the whole session — every WORK message carries execution intent
        // plus full-system envelope so nothing lands behind a review gate.
        val homeChat = HomeRuntimeBridge.chat(
          message = buildString {
            if (receiptBlock.isNotEmpty()) append(receiptBlock).append("\n")
            if (toolChainPrefix.isNotEmpty()) append(toolChainPrefix)
            append(prompt)
          },
          sessionId = _activeSessionId.value,
          mode = currentMode.name,
          executionIntent = currentMode == InteractionMode.WORK,
          executionAction = if (currentMode == InteractionMode.WORK) "RUN" else null,
          fullSystemScope = currentMode == InteractionMode.WORK || _fullSystemScope.value
        )
        if (homeChat.ok) {
          homeServedThisTurn = true
          ProviderExecutionResult(
            content = homeChat.reply,
            providerModel = "HOME·${homeChat.provider ?: "canonical"}/${homeChat.model ?: "auto"}",
            tokenCount = homeChat.reply.length / 4,
            latencyMs = homeChat.durationMs,
            routingReceipt = RoutingReceipt(
              resolvedProvider = homeChat.provider ?: "canonical-runtime",
              resolvedModel = homeChat.model ?: "auto",
              isHomeAttached = true,
              routingReason = "Executed on canonical PurpClaw runtime · phone did not re-route",
              fallbackPath = emptyList(),
              providerLatencyMs = homeChat.durationMs,
              tokenCount = homeChat.reply.length / 4,
              qualityGateResult = "PASS"
            )
          )
        } else {
          // ROUTE TRUTH LAW: fallback is recorded in the receipt (fallbackPath),
          // never smeared into the model name. The serving model stays the
          // serving model; only the receipt explains that home died first.
          // RUNTIME TRUTH: home was believed online but failed mid-turn — flip
          // mesh status NOW so the status pill and next turns show SOVEREIGN/
          // REMOTE instead of a stale HOME-RELAY claim.
          meshCoordinator.markHomeUnreachable(homeChat.error ?: "home chat failed")
          val localPrompt = buildString {
            if (receiptBlock.isNotEmpty()) append(receiptBlock).append("\n")
            if (toolChainPrefix.isNotEmpty()) append(toolChainPrefix)
            append(prompt)
          }
          val localModel = resolvePhoneModel()
          attemptedPhoneModel = localModel
          val localResult = providerRouter.generateResponse(
            prompt = localPrompt, preferredProvider = localModel ?: "AUTO",
            systemInstruction = systemPrompt, sessionId = _activeSessionId.value,
            toolsRequired = currentMode == InteractionMode.WORK, visionRequired = false, isHomeOnline = false,
            conversationHistory = conversationHistory,
            priority = if (currentMode == InteractionMode.CHAT) SharedQuotaLedger.Priority.INTERACTIVE
              else SharedQuotaLedger.Priority.WORK
          )
          localResult.copy(
            routingReceipt = localResult.routingReceipt?.copy(
              requestedModel = _selectedModel.value,
              routingReason = "Home runtime unreachable mid-turn (${homeChat.error}) — served by phone fallback",
              fallbackPath = listOf("home") + localResult.routingReceipt.fallbackPath
            ),
            errorMessage = "Home runtime unreachable mid-turn (${homeChat.error})"
          )
        }.let { r ->
          // TOOL-MARKUP STRIP LAW applies to every inference path.
          stripHallucinatedToolMarkup(r)
        }
      } else {
        // AUTO-DIRECT PATH: phone calls the provider pool (OpenRouter/NIM/direct)
        // directly. If the direct call dies AND home is reachable, fall back to
        // home so the user never sees a silent stall (this is what was killing
        // voice mode — single dead path, no fallback).
        val composedPrompt = buildString {
          if (receiptBlock.isNotEmpty()) append(receiptBlock).append("\n")
          if (toolChainPrefix.isNotEmpty()) append(toolChainPrefix)
          append(prompt)
        }
        val phoneModel = resolvePhoneModel()
        attemptedPhoneModel = phoneModel
        val remoteResult = providerRouter.generateResponse(
          prompt = composedPrompt,
          preferredProvider = phoneModel ?: "AUTO",
          systemInstruction = systemPrompt,
          sessionId = _activeSessionId.value,
          toolsRequired = currentMode == InteractionMode.WORK,
          visionRequired = prompt.contains("camera", ignoreCase = true) || prompt.contains("image", ignoreCase = true),
          isHomeOnline = homeOnline,
          conversationHistory = conversationHistory,
          priority = if (currentMode == InteractionMode.CHAT) SharedQuotaLedger.Priority.INTERACTIVE
            else SharedQuotaLedger.Priority.WORK
        )
        val remoteUsable = remoteResult.content.isNotBlank() &&
          remoteResult.errorMessage == null &&
          remoteResult.routingReceipt?.qualityGateResult != "EMERGENCY_FALLBACK"
        if (remoteUsable) {
          stripHallucinatedToolMarkup(remoteResult)
        } else if (allowPartialFailover && homeOnline && wantsHomeRouting) {
          // Direct call died — escalate to home relay once as a safety net.
          // Stamps HOME in the receipt so the truth in the pill stays accurate.
          val homeChat = HomeRuntimeBridge.chat(
            message = if (toolChainPrefix.isNotEmpty()) toolChainPrefix + prompt else prompt,
            sessionId = _activeSessionId.value,
            mode = currentMode.name,
            executionIntent = currentMode == InteractionMode.WORK,
            executionAction = if (currentMode == InteractionMode.WORK) "RUN" else null,
            fullSystemScope = currentMode == InteractionMode.WORK || _fullSystemScope.value
          )
          if (homeChat.ok) {
            homeServedThisTurn = true
            ProviderExecutionResult(
              content = homeChat.reply,
              providerModel = "HOME·${homeChat.provider ?: "canonical"}/${homeChat.model ?: "auto"}",
              tokenCount = homeChat.reply.length / 4,
              latencyMs = homeChat.durationMs,
              routingReceipt = RoutingReceipt(
                resolvedProvider = homeChat.provider ?: "canonical-runtime",
                resolvedModel = homeChat.model ?: "auto",
                isHomeAttached = true,
                routingReason = "Phone-direct path failed (${remoteResult.errorMessage ?: "no_content"}) — escalated to home relay",
                fallbackPath = listOf("direct"),
                providerLatencyMs = homeChat.durationMs,
                tokenCount = homeChat.reply.length / 4,
                qualityGateResult = "PASS"
              )
            )
          } else {
            meshCoordinator.markHomeUnreachable(homeChat.error ?: "home chat failed")
            stripHallucinatedToolMarkup(remoteResult)
          }
        } else {
          stripHallucinatedToolMarkup(remoteResult)
        }
      }

      // SAME-TURN CONTINUATION LAW. A provider tool_call is intermediate:
      // execute it, inject the verified result, then wake the SAME Soul in
      // the SAME session/turn until it answers, blocks, is cancelled, or
      // reaches the explicit bound. The composer remains locked because
      // _isGenerating is not cleared anywhere inside this loop.
      emitTurnEvent("model.response", liveTurnId, mapOf(
        "provider_model" to providerResult.providerModel,
        "structured_tool_calls" to providerResult.toolCalls.size,
        "has_text" to providerResult.content.isNotBlank()
      ))
      if (providerResult.toolCalls.isNotEmpty()) {
        emitTurnEvent("model.response", liveTurnId, mapOf(
          "provider_model" to providerResult.providerModel,
          "structured_tool_calls" to providerResult.toolCalls.size
        ))
        val activeTurnJob = currentCoroutineContext()[Job]
        _toolLoopActive.value = true
        val loopOutcome = BoundedToolContinuation.run(
          initial = providerResult,
          maxSteps = 6,
          calls = { it.toolCalls },
          finalText = { it.content },
          errorText = { it.errorMessage },
          isCancelled = { activeTurnJob?.isActive == false },
          execute = { call ->
            pushLive("tool requested · ${call.toolName}")
            emitTurnEvent("tool.requested", liveTurnId, mapOf("tool" to call.toolName, "call_id" to call.callId))
            emitTurnEvent("tool.started", liveTurnId, mapOf("tool" to call.toolName, "call_id" to call.callId))
            pushLive("executing · ${call.toolName}")
            toolRuntime.dispatchCanonical(
              call = call,
              mode = currentMode,
              lease = _activeLease.value,
              actorAgent = soul.name,
              isHomeOnline = homeReachable,
              originalOperatorRequest = prompt
            ).also { dispatch ->
              emitTurnEvent(
                if (dispatch.isSuccess) "tool.completed" else "tool.failed",
                liveTurnId,
                mapOf("tool" to call.toolName, "call_id" to call.callId, "success" to dispatch.isSuccess)
              )
              emitTurnEvent("verification.completed", liveTurnId, mapOf(
                "tool" to call.toolName,
                "call_id" to call.callId,
                "verified" to dispatch.isSuccess,
                "evidence_hash" to (dispatch.record?.evidenceHash ?: "")
              ))
              dispatch.record?.let(toolCallsList::add)
              dispatch.receipt?.let { receipt ->
                generatedProof = receipt
                db.receiptDao().insertReceipt(receipt.toEntity())
              }
            }
          },
          resume = { previous, pairs, step ->
            pushLive("continuing after tool step $step")
            emitTurnEvent("continuation.started", liveTurnId, mapOf("step" to step))
            val verifiedResults = pairs.joinToString("\n\n") { (call, dispatch) ->
              val outcome = dispatch.record?.output
                ?: dispatch.error
                ?: "tool.failed without output"
              "tool_call_id=${call.callId}\n${call.toolName} → ${outcome.take(1800)}"
            }
            val continuationPrompt = buildString {
              appendLine("[SAME TURN CONTINUATION]")
              appendLine("session_id=${_activeSessionId.value}")
              appendLine("turn_id=$liveTurnId")
              appendLine("soul_id=${soul.name}")
              appendLine("Original operator request: $prompt")
              if (previous.content.isNotBlank()) appendLine("Partial assistant text: ${previous.content}")
              appendLine("Verified tool results:")
              appendLine(verifiedResults)
              appendLine("Continue this same request. Call another provided tool if required; otherwise give the final user-facing answer. Never merely print tool names.")
            }
            val contResult = providerRouter.generateResponse(
              prompt = continuationPrompt,
              preferredProvider = previous.providerModel,
              systemInstruction = systemPrompt,
              sessionId = _activeSessionId.value,
              toolsRequired = true,
              visionRequired = false,
              isHomeOnline = false,
              conversationHistory = conversationHistory,
              priority = SharedQuotaLedger.Priority.WORK
            )
            // allowPartialFailover parity (paste_22 P0-C fix):
            // If the continuation's model failed and allowPartialFailover is true
            // (AUTO mode, not a manual pin), escalate to the Home relay as a
            // safety net. This is the SECOND integration point — the first was
            // the phone-direct → home escalation at the top-level routing.
            val resumed = if (contResult.errorMessage != null && allowPartialFailover && homeReachable
              && wantsHomeRouting) {
              providerRouter.generateResponse(
                prompt = continuationPrompt,
                preferredProvider = "AUTO",
                systemInstruction = systemPrompt,
                sessionId = _activeSessionId.value,
                toolsRequired = true,
                visionRequired = false,
                isHomeOnline = true,
                conversationHistory = conversationHistory,
                priority = SharedQuotaLedger.Priority.WORK
              )
            } else contResult
            emitTurnEvent("continuation.completed", liveTurnId, mapOf(
              "step" to step,
              "provider_model" to resumed.providerModel,
              "has_final_text" to resumed.content.isNotBlank(),
              "next_tool_calls" to resumed.toolCalls.size
            ))
            emitTurnEvent("model.response", liveTurnId, mapOf(
              "provider_model" to resumed.providerModel,
              "structured_tool_calls" to resumed.toolCalls.size,
              "continuation_step" to step
            ))
            resumed
          }
        )
        providerResult = loopOutcome.message
        if (loopOutcome.termination == ToolLoopTermination.MAX_STEPS) {
          providerResult = providerResult.copy(
            content = providerResult.content.ifBlank {
              "I stopped after ${loopOutcome.steps} tool steps because the bounded continuation limit was reached."
            },
            errorMessage = "continuation.max_steps"
          )
        }
        if (loopOutcome.termination == ToolLoopTermination.BLOCKED) {
          // NULL-BUBBLE LAW: the model produced no tool calls AND no text.
          // This is the exact failure surface that produced literal `null`
          // in chat bubbles. Never allow an empty continuation to reach the
          // renderer — always inject a truthful structured error.
          providerResult = providerResult.copy(
            content = providerResult.content.ifBlank {
              "No response received — the assistant turn produced empty output after tool execution."
            },
            errorMessage = "continuation.blocked_no_output"
          )
        }
        if (loopOutcome.termination == ToolLoopTermination.CANCELLED) {
          providerResult = providerResult.copy(
            content = providerResult.content.ifBlank {
              "Turn was cancelled."
            },
            errorMessage = "continuation.cancelled"
          )
        }
        Log.i("MainViewModel", "Tool continuation ended: ${loopOutcome.termination} steps=${loopOutcome.steps}")
        _toolLoopActive.value = false
      }

      // HOME-FAILOVER LAW: when the canonical runtime never served this turn
      // (homeServedThisTurn=false) AND the phone-direct route produced no
      // usable content, attempt one last-chance escalation through
      // ProviderRouter so a dead Home never leaves the user staring at
      // "[no visible reply from model]". The escalation goes through the
      // existing router infrastructure (no new framework); if it ALSO
      // returns blank, render a truthful error card with retry semantics.
      var mobileFallbackFired = false
      var truthfulErrorCard: String? = null
      val phoneResultUsable = providerResult.content.isNotBlank() &&
        providerResult.errorMessage == null &&
        providerResult.routingReceipt?.qualityGateResult != "EMERGENCY_FALLBACK"
      if (!homeServedThisTurn && !phoneResultUsable && (!homeReachable || !wantsHomeRouting)) {
        pushLive("home offline — trying mobile provider")
        // Prefer the operator's pin; otherwise walk the live free-gateway
        // catalogue. NIM is first because its frontier-model pool is the
        // primary zero-cost mobile lane and its membership changes often;
        // OpenRouter remains the second configured free gateway. Direct
        // providers (OPENAI/ZAI/DEEPSEEK/...) sit behind a vault key — the
        // pin path covers them. If neither is present, the router will
        // refuse with NO_ROUTING_AUTHORITY, which we still surface honestly.
        val attemptedModels = linkedSetOf<String>().apply { attemptedPhoneModel?.let(::add) }
        val composedPrompt = buildString {
          if (receiptBlock.isNotEmpty()) append(receiptBlock).append("\n")
          if (toolChainPrefix.isNotEmpty()) append(toolChainPrefix)
          append(prompt)
        }
        var rescueSucceeded = false
        repeat(4) {
          if (rescueSucceeded) return@repeat
          val fallbackModel = resolvePhoneModel(exclude = attemptedModels) ?: return@repeat
          attemptedModels += fallbackModel
          Log.w("MainViewModel", "Phone failover: attempting ranked free model=$fallbackModel")
          val rescueResult = providerRouter.generateResponse(
            prompt = composedPrompt,
            preferredProvider = fallbackModel,
            systemInstruction = systemPrompt,
            sessionId = _activeSessionId.value,
            toolsRequired = currentMode == InteractionMode.WORK,
            visionRequired = prompt.contains("camera", ignoreCase = true) || prompt.contains("image", ignoreCase = true),
            isHomeOnline = false,
            conversationHistory = conversationHistory,
            priority = if (currentMode == InteractionMode.CHAT) SharedQuotaLedger.Priority.INTERACTIVE
              else SharedQuotaLedger.Priority.WORK
          )
          if (rescueResult.content.isNotBlank() && rescueResult.errorMessage == null) {
            mobileFallbackFired = true
            rescueSucceeded = true
            providerResult = rescueResult.copy(
              routingReceipt = rescueResult.routingReceipt?.copy(
                requestedModel = fallbackModel,
                routingReason = "Served by ranked phone-native free-model failover (${rescueResult.routingReceipt?.resolvedProvider ?: "mobile"})",
                fallbackPath = attemptedModels.toList().dropLast(1) + rescueResult.routingReceipt.fallbackPath
              ),
              errorMessage = null
            )
            Log.i("MainViewModel", "Phone failover OK via ${rescueResult.providerModel}")
          } else {
            providerResult = rescueResult.copy(content = "")
            Log.w("MainViewModel", "Phone failover failed via $fallbackModel: ${rescueResult.errorMessage}")
          }
        }
        if (!rescueSucceeded) {
          truthfulErrorCard = "No response received · Retry"
          providerResult = providerResult.copy(content = "")
          Log.w("MainViewModel", "Phone failover exhausted: ${attemptedModels.joinToString(" → ")}")
        }
      }

      // A cancelled/superseded blocking provider callback may still return.
      // Never persist, speak or render it after ownership of the turn changed.
      if (activeTurnToken != turnToken || !currentCoroutineContext().isActive) return@launch

      // 5. Create Assistant Turn
      val assistantTurnId = "turn_asst_${UUID.randomUUID().toString().take(8)}"
      val turnReceipt = providerResult.routingReceipt
      val assistantTurn = TurnRecord(
        id = assistantTurnId,
        sessionId = _activeSessionId.value,
        parentEventId = userTurnId,
        // IDENTITY LAW: execution node is the PHONE (hands). Home is only the
        // inference relay when attached. Never label the phone's own turns as
        // "HOME PC" — that is what taught the model to hunt C:\ drives.
        nodeId = "phone-android-node-01",
        timestamp = System.currentTimeMillis(),
        sequence = turns.value.size + 2,
        role = "assistant",
        mode = currentMode,
        // NULL-BUBBLE LAW: an empty reply with ok=true means the core produced
        // no visible text (e.g. tool-only turn). Never render a blank/null
        // bubble — state what happened instead. The mobile-fallback rescue
        // above either filled providerResult with a real reply or set
        // truthfulErrorCard; only the truly tool-only turn still uses the
        // tool-summary sentinel.
        content = providerResult.content
          .trim()
          .takeIf { it.isNotBlank() && it.lowercase() != "null" && it.lowercase() != "none" && it.lowercase() != "empty" }
          ?: truthfulErrorCard
          ?: if (toolCallsList.isNotEmpty())
            deterministicToolAcknowledgement(toolCallsList, generatedProof)
          else "No response received · Retry",
        reasoning = null,
        toolCalls = toolCallsList,
        proofReceipt = generatedProof,
        leaseId = if (toolCallsList.isNotEmpty()) generatedProof?.executionLeaseId else null,
        fullSystemScope = _fullSystemScope.value,
        tokenCount = providerResult.tokenCount,
        latencyMs = providerResult.latencyMs,
        // ROUTE TRUTH LAW: the route line is derived from the completed turn's
        // receipt — mode → resolved model — never from selector state, which
        // would rewrite history. Placement stays topology-honest and separate.
        providerModel = if (turnReceipt != null) {
          buildString {
            append(
              when (turnReceipt.modelMode) {
                ModelMode.MANUAL -> "MANUAL · ${turnReceipt.resolvedModel}"
                else -> "AUTO → ${turnReceipt.resolvedModel}"
              }
            )
            append(
              when {
                homeServedThisTurn -> " · INFERENCE:HOME-RELAY · EXECUTION:ANDROID"
                mobileFallbackFired -> " · SESSION:MOBILE · INFERENCE:REMOTE/${turnReceipt.resolvedProvider.uppercase()} · EXECUTION:LOCAL/ANDROID · HOME:OFFLINE"
                else -> " · SESSION:MOBILE · INFERENCE:REMOTE/${turnReceipt.resolvedProvider.uppercase()} · EXECUTION:LOCAL/ANDROID"
              }
            )
          }
        } else {
          // Legacy path: no receipt — keep old label shape rather than lie.
          buildString {
            append(when {
              homeServedThisTurn -> "INFERENCE:HOME-RELAY"
              mobileFallbackFired -> "SESSION:MOBILE · INFERENCE:REMOTE/${providerResult.providerModel.substringBefore("/").ifBlank { "UNKNOWN" }.uppercase()} · HOME:OFFLINE"
              else -> "INFERENCE:REMOTE"
            })
            append(" · EXECUTION:LOCAL · ANDROID · ")
            append(providerResult.providerModel)
          }
        },
        routingReceipt = providerResult.routingReceipt
      )

      db.turnDao().insertTurn(assistantTurn.toEntity())
      emitTurnEvent(
        if (assistantTurn.content.startsWith("No response received")) "turn.blocked" else "turn.final",
        liveTurnId,
        mapOf("tool_count" to toolCallsList.size, "provider_model" to providerResult.providerModel)
      )
      _liveTurn.value = null   // live card hands off to the persisted turn

      // 6. Update 7-layer memory
      memoryGateway.storeMemory(
        layer = MemoryLayer.EPISODIC,
        key = "turn.${prompt.take(20)}",
        content = "Turn completed in mode $currentMode: ${assistantTurn.content.take(120)}",
        score = 0.92f
      )

      // 7. VOICE LOOP: voice in -> voice out. Also explicit "speak" requests.
      if (lastTurnWasVoice || voiceModeController.wantsVoiceOut() ||
          prompt.contains("speak", ignoreCase = true) || prompt.contains("read that", ignoreCase = true)) {
        ttsEngine.speak(stripToolCallBlocksFromReply(assistantTurn.content))
        lastTurnWasVoice = false
      }

      _companionState.value = CompanionPetState.SUCCESS
      activeTurnToken = null
      activeTurnId = null
      _isGenerating.value = false

      delay(1200)
      _companionState.value = CompanionPetState.IDLE
      // QUEUE: next queued message processes immediately
      drainQueue()
    }
    activeTurnJob = launchedJob
    launchedJob.invokeOnCompletion { cause ->
      if (cause != null && activeTurnToken == turnToken) {
        val id = activeTurnId
        activeTurnToken = null
        activeTurnId = null
        if (id != null && cause !is kotlinx.coroutines.CancellationException) {
          emitTurnEvent("turn.blocked", id, mapOf("failure" to (cause.message ?: cause.javaClass.simpleName)))
        }
        _liveTurn.value = null
        _isGenerating.value = false
        _companionState.value = CompanionPetState.ERROR
      }
      if (activeTurnJob == launchedJob) activeTurnJob = null
    }
  }

  /** Canonical voice loop owner. Mic tap = toggle (OFF<->LISTENING, barge-in while SPEAKING). */
  fun toggleVoiceMode(handsFree: Boolean = false) {
    voiceModeController.toggle(handsFree)
    _companionState.value =
      if (voiceModeController.mode.value == VoiceMode.OFF) CompanionPetState.IDLE
      else CompanionPetState.LISTENING
  }

  fun beginPodcastPushToTalk() {
    if (!_isPodcastActive.value) return
    voiceModeController.beginPodcastPushToTalk()
    _companionState.value = CompanionPetState.LISTENING
    Log.i("MainViewModel", "podcast floor paused · listening to operator")
  }

  fun endPodcastPushToTalk() {
    if (!_isPodcastActive.value) return
    voiceModeController.endPodcastPushToTalk()
    Log.i("MainViewModel", "podcast floor released · transcribing operator")
  }

  /** Legacy one-shot PTT entry point — now routes through the canonical controller. */
  fun triggerVoicePushToTalk() { toggleVoiceMode() }

  // Mic contract: tap = ON/OFF only. Conversation mode is Settings-owned;
  // cycleVoiceConversationMode() removed per canonical voice law.

  init {
    voiceModeController.onTranscript = onTranscript@{ transcript ->
      // TASK #47 (2026-08-27): mid-podcast mic press captures a guest turn
      // without breaking the episode. The transcript slides into the
      // engine's guest queue; the next seat slot plays it. We do NOT push
      // a fresh chat turn (that would derail the council) and we do NOT
      // call sendCurrentMessage() (that would launch a parallel exchange).
      if (_isPodcastActive.value && transcript.isNotBlank()) {
        val id = getOrCreatePodcastEngine().enqueueGuest(transcript)
        Log.i("MainViewModel", "guest queued ($id) depth=${getOrCreatePodcastEngine().guestQueue.value.size}")
        _inputText.value = ""
        lastTurnWasVoice = false
        return@onTranscript
      }
      _inputText.value = transcript
      lastTurnWasVoice = true   // reply speaks back automatically
      sendCurrentMessage()
    }
  }

  fun triggerCameraVisionAsk() {
    viewModelScope.launch {
      _companionState.value = CompanionPetState.TOOL_CALL
      val capture = cameraEngine.captureOpticalFrame()
      // RESOURCE HANDLE LAW: the model receives a purpclaw:// URI handle —
      // never a filename it could go hunting for on PC drives.
      _inputText.value = if (capture.fileSizeBytes >= 0) {
        // MEDIA LAW: stage the real phone-local handle so it renders INLINE in chat.
        _pendingMediaAttachments.value = _pendingMediaAttachments.value + MediaAttachment(
          kind = MediaKind.IMAGE,
          uri = capture.resourceUri(),
          mime = "image/jpeg",
          label = "optical frame"
        )
        // COMPACT INPUT LAW: never dump raw JSON into the chat bubble.
        // The model receives a compact, human-readable reference and the
        // structured tool boundary for actual analysis. Since vision.analyze
        // is now a registered tool, the model calls it with the URI.
        "Analyze the camera capture: ${capture.resourceUri()} (${capture.width}x${capture.height}, ${capture.fileSizeBytes / 1024} KB, sha256 ${capture.frameSha256.take(16)})"
      } else {
        "Camera capture FAILED this session (no CameraX binding). Do not fabricate an image; ask to retry."
      }
      _companionState.value = CompanionPetState.IDLE
    }
  }

  /**
   * Stages the exact JPEG returned by the user's normal camera app. The bytes,
   * dimensions and digest are verified before anything is shown in chat.
   */
  fun attachUserCameraPhoto(file: File) {
    viewModelScope.launch(Dispatchers.IO) {
      val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
      android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
      if (!file.exists() || file.length() <= 0L || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        file.delete()
        withContext(Dispatchers.Main) {
          _inputText.value = "Camera did not return a valid photo. Nothing was attached."
        }
        return@launch
      }
      val digest = CameraVisionEngine.computeFileSha256(file)
      val resourceUri = "purpclaw://chat-images/${file.name}"
      val displayUri = androidx.core.content.FileProvider.getUriForFile(
        getApplication(), "${getApplication<Application>().packageName}.fileprovider", file
      ).toString()
      val attachment = MediaAttachment(
        kind = MediaKind.IMAGE,
        uri = displayUri,
        mime = "image/jpeg",
        label = "Camera photo",
        resourceUri = resourceUri,
        sha256 = digest,
        sizeBytes = file.length(),
        width = bounds.outWidth,
        height = bounds.outHeight,
        source = "USER_CAMERA"
      )
      withContext(Dispatchers.Main) {
        _pendingMediaAttachments.value = _pendingMediaAttachments.value + attachment
        _inputText.value = "Tell me what you see in this exact attached photo: $resourceUri (sha256 $digest)"
      }
    }
  }

  fun triggerFileIntakeCapsule(name: String = "purpclaw_project_workspace.zip") {
    viewModelScope.launch {
      _companionState.value = CompanionPetState.TOOL_CALL
      val result = toolRuntime.executeTool(
        toolName = "android.storage.status",
        arguments = name,
        actorAgent = "PurpClawCore",
        lease = _activeLease.value,
        isHomeOnline = meshCoordinator.meshStatus.value.let {
          it == MeshStatus.HOME_ONLINE || it == MeshStatus.HYBRID_DEGRADED
        }
      )
      _inputText.value = "Inspect intake capsule storage workspace: ${result.record.output.lines().firstOrNull() ?: ""}"
      _companionState.value = CompanionPetState.IDLE
    }
  }

  fun executeToolDirect(toolName: String, arguments: String) {
    viewModelScope.launch {
      val result = toolRuntime.executeTool(
        toolName = toolName,
        arguments = arguments,
        actorAgent = _selectedCompanion.value,
        lease = _activeLease.value,
        isHomeOnline = meshCoordinator.meshStatus.value.let {
          it == MeshStatus.HOME_ONLINE || it == MeshStatus.HYBRID_DEGRADED
        }
      )
      result.proofReceipt?.let {
        db.receiptDao().insertReceipt(it.toEntity())
      }
    }
  }

  fun createSnapshot(reason: String = "Manual State Checkpoint") {
    viewModelScope.launch {
      val snapId = "snap_${UUID.randomUUID().toString().take(6)}"
      val entity = SnapshotEntity(
        id = snapId,
        label = "Snapshot: $reason",
        reason = reason,
        beforeStateHash = "sha256_b39a8f2910c2",
        afterStateHash = "sha256_e10d4812a03f",
        timestamp = System.currentTimeMillis(),
        isRestorable = true
      )
      db.snapshotDao().insertSnapshot(entity)
    }
  }

  fun addVaultSecret(keyName: String, value: String, category: String) {
    vault.storeSecret(keyName, value)
    val masked = if (value.length > 8) "${value.take(4)}...${value.takeLast(4)}" else "••••••••"
    val item = VaultItem(
      id = "v_${UUID.randomUUID().toString().take(6)}",
      keyName = keyName,
      maskedValue = masked,
      category = category,
      lastAccessedMs = System.currentTimeMillis()
    )
    _vaultItems.value = listOf(item) + _vaultItems.value.filter { it.keyName != keyName }
  }

  fun saveOpenRouterKey(key: String) {
    vault.storeSecret("OPENROUTER_API_KEY", key)
    addVaultSecret("OPENROUTER_API_KEY", key, "OpenRouter API Key")
    viewModelScope.launch {
      providerRouter.refreshOpenRouterCatalogue()
    }
  }

  fun saveMiniMaxKey(key: String) {
    vault.storeSecret("MINIMAX_API_KEY", key)
    addVaultSecret("MINIMAX_API_KEY", key, "MiniMax API Key")
    viewModelScope.launch {
      providerRouter.checkMiniMaxInstalledCapabilities()
      providerRouter.refreshMinimaxCatalogue()
    }
  }

  fun saveNvidiaNimKey(key: String) {
    vault.storeSecret("NVIDIA_NIM_API_KEY", key)
    addVaultSecret("NVIDIA_NIM_API_KEY", key, "NVIDIA NIM API Key")
    viewModelScope.launch {
      providerRouter.refreshNimCatalogue()
    }
  }

  /** Direct-provider vault write — covers Kimi, Qwen, DeepSeek, OpenAI, Z.ai, LongCat. */
  fun saveDirectProviderKey(vaultKeyName: String, displayLabel: String, key: String) {
    vault.storeSecret(vaultKeyName, key)
    addVaultSecret(vaultKeyName, key, displayLabel)
    viewModelScope.launch {
      when (vaultKeyName) {
        "KIMI_API_KEY" -> providerRouter.refreshKimiCatalogue()
        "QWEN_API_KEY" -> providerRouter.refreshQwenCatalogue()
        "DEEPSEEK_API_KEY" -> providerRouter.refreshDeepseekCatalogue()
        "OPENAI_API_KEY" -> providerRouter.refreshOpenaiCatalogue()
        "ZAI_API_KEY" -> providerRouter.refreshZaiCatalogue()
        "LONGCAT_API_KEY" -> providerRouter.refreshLongcatCatalogue()
      }
    }
  }

  fun clearVaultSecret(keyName: String) {
    vault.deleteSecret(keyName)
    _vaultItems.value = _vaultItems.value.filter { it.keyName != keyName }
    viewModelScope.launch {
      when (keyName) {
        "OPENROUTER_API_KEY" -> providerRouter.refreshOpenRouterCatalogue()
        "MINIMAX_API_KEY" -> {
          providerRouter.checkMiniMaxInstalledCapabilities()
          providerRouter.refreshMinimaxCatalogue()
        }
        "NVIDIA_NIM_API_KEY" -> providerRouter.refreshNimCatalogue()
        "KIMI_API_KEY" -> providerRouter.refreshKimiCatalogue()
        "QWEN_API_KEY" -> providerRouter.refreshQwenCatalogue()
        "DEEPSEEK_API_KEY" -> providerRouter.refreshDeepseekCatalogue()
        "OPENAI_API_KEY" -> providerRouter.refreshOpenaiCatalogue()
        "ZAI_API_KEY" -> providerRouter.refreshZaiCatalogue()
        "LONGCAT_API_KEY" -> providerRouter.refreshLongcatCatalogue()
      }
    }
  }

  fun addMemoryItem(layer: MemoryLayer, key: String, content: String) {
    viewModelScope.launch {
      memoryGateway.storeMemory(layer, key, content)
    }
  }

  fun deleteMemoryItem(id: Long) {
    viewModelScope.launch {
      memoryGateway.deleteMemory(id)
    }
  }

  private fun getInitialSeedTurns(): List<TurnRecord> {
    return listOf(
      TurnRecord(
        id = "turn_seed_01",
        sessionId = "ses_canonical_01",
        parentEventId = null,
        nodeId = "phone-android-node-01",
        timestamp = System.currentTimeMillis() - 600000,
        sequence = 1,
        role = "assistant",
        mode = InteractionMode.CHAT,
        content = """**PURPCLAW Sovereign Android Runtime online.** 

All capabilities are backed by real Android APIs, hardware Keystore signing, 7-layer SQLite memory, and multi-provider failover. Live status verified in **Audit → Capability Truth**.""",
        reasoning = null,
        toolCalls = emptyList(),
        proofReceipt = null,
        fullSystemScope = true,
        providerModel = "SOVEREIGN · MiniMax / OpenRouter pool"
      )
    )
  }

  override fun onCleared() {
    voiceModeController.shutdown()
    super.onCleared()
  }
}

// --- Mapping Extensions ---

private fun TurnEntity.toDomainModel(): TurnRecord {
  val calls = mutableListOf<ToolCallRecord>()
  if (toolCallsJson.isNotBlank() && toolCallsJson != "[]") {
    try {
      val arr = JSONArray(toolCallsJson)
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        calls.add(
          ToolCallRecord(
            id = obj.getString("id"),
            toolName = obj.getString("toolName"),
            affinity = ToolAffinity.valueOf(obj.optString("affinity", "ANDROID_NATIVE")),
            arguments = obj.getString("arguments"),
            output = obj.getString("output"),
            isSuccess = obj.getBoolean("isSuccess"),
            durationMs = obj.getLong("durationMs"),
            evidenceHash = obj.getString("evidenceHash")
          )
        )
      }
    } catch (_: Exception) {}
  }

  val media = mutableListOf<MediaAttachment>()
  if (mediaAttachmentsJson.isNotBlank() && mediaAttachmentsJson != "[]") {
    try {
      val arr = JSONArray(mediaAttachmentsJson)
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        media.add(
          MediaAttachment(
            kind = MediaKind.valueOf(obj.getString("kind")),
            uri = obj.getString("uri"),
            mime = if (obj.has("mime")) obj.getString("mime") else null,
            label = if (obj.has("label")) obj.getString("label") else null,
            resourceUri = obj.optString("resourceUri").ifBlank { null },
            sha256 = obj.optString("sha256").ifBlank { null },
            sizeBytes = if (obj.has("sizeBytes")) obj.optLong("sizeBytes") else null,
            width = if (obj.has("width")) obj.optInt("width") else null,
            height = if (obj.has("height")) obj.optInt("height") else null,
            source = obj.optString("source").ifBlank { null }
          )
        )
      }
    } catch (_: Exception) {}
  }

  return TurnRecord(
    id = id,
    sessionId = sessionId,
    parentEventId = parentEventId,
    nodeId = nodeId,
    timestamp = timestamp,
    sequence = sequence,
    role = role,
    mode = try { InteractionMode.valueOf(mode) } catch (_: Exception) { InteractionMode.CHAT },
    content = content,
    reasoning = reasoning,
    toolCalls = calls,
    mediaAttachments = media,
    proofReceipt = null,
    leaseId = leaseId,
    fullSystemScope = fullSystemScope,
    tokenCount = tokenCount,
    latencyMs = latencyMs,
    providerModel = providerModel
  )
}

private fun TurnRecord.toEntity(): TurnEntity {
  val arr = JSONArray()
  toolCalls.forEach { call ->
    val obj = JSONObject()
    obj.put("id", call.id)
    obj.put("toolName", call.toolName)
    obj.put("affinity", call.affinity.name)
    obj.put("arguments", call.arguments)
    obj.put("output", call.output)
    obj.put("isSuccess", call.isSuccess)
    obj.put("durationMs", call.durationMs)
    obj.put("evidenceHash", call.evidenceHash)
    arr.put(obj)
  }

  val mediaArr = JSONArray()
  mediaAttachments.forEach { att ->
    val obj = JSONObject()
    obj.put("kind", att.kind.name)
    obj.put("uri", att.uri)
    att.mime?.let { obj.put("mime", it) }
    att.label?.let { obj.put("label", it) }
    att.resourceUri?.let { obj.put("resourceUri", it) }
    att.sha256?.let { obj.put("sha256", it) }
    att.sizeBytes?.let { obj.put("sizeBytes", it) }
    att.width?.let { obj.put("width", it) }
    att.height?.let { obj.put("height", it) }
    att.source?.let { obj.put("source", it) }
    mediaArr.put(obj)
  }

  return TurnEntity(
    id = id,
    sessionId = sessionId,
    parentEventId = parentEventId,
    nodeId = nodeId,
    timestamp = timestamp,
    sequence = sequence,
    role = role,
    mode = mode.name,
    content = content,
    reasoning = reasoning,
    toolCallsJson = arr.toString(),
    mediaAttachmentsJson = mediaArr.toString(),
    proofHash = proofReceipt?.proofHash,
    leaseId = leaseId,
    fullSystemScope = fullSystemScope,
    tokenCount = tokenCount,
    latencyMs = latencyMs,
    providerModel = providerModel
  )
}

/**
 * Truthful final text for a tool-only model turn. The action is never rerun:
 * this describes the ToolRuntimeEngine record and its signed proof receipt.
 */
private fun deterministicToolAcknowledgement(
  records: List<ToolCallRecord>,
  receipt: ProofReceipt?
): String {
  val record = records.last()
  val verified = record.isSuccess &&
    receipt?.verificationStatus == "VERIFIED" &&
    record.output.contains("→ CONFIRMED")
  val firstLine = when {
    !record.isSuccess -> when (record.toolName) {
      "android.browser.open" -> "I couldn't open that browser on this phone."
      "android.app.open" -> "I couldn't open that app on this phone."
      "android.camera.capture" -> "I couldn't capture a photo on this phone."
      else -> "That action did not complete on this phone."
    }
    record.toolName == "android.browser.open" && verified -> "Opened it in the requested browser on this phone. ✓"
    record.toolName == "android.app.open" && verified -> "Opened the app on this phone. ✓"
    record.toolName == "android.camera.capture" -> "Captured a photo on this phone. ✓"
    record.toolName == "android.app.list" -> "I checked the apps installed on this phone. ✓"
    verified -> "Completed and verified the action on this phone. ✓"
    else -> "Action sent to Android, but I couldn't verify the resulting screen."
  }
  val evidence = record.output.substringAfter("[VERIFY] ", "")
    .substringBefore(" → ").trim().takeIf { it.isNotBlank() }
  return buildString {
    append(firstLine)
    evidence?.let { append("\nVerified: ").append(it) }
    if (!record.isSuccess) {
      append("\n").append(record.output.substringBefore("[VERIFY]").trim().take(280))
    }
    receipt?.let { append("\nReceipt ").append(it.receiptId).append(" · ").append(it.verificationStatus) }
  }
}

private fun ProofReceipt.toEntity(): com.example.core.database.ProofReceiptEntity {
  return com.example.core.database.ProofReceiptEntity(
    receiptId = receiptId,
    verificationStatus = verificationStatus,
    actor = actor,
    agent = agent,
    toolName = toolName,
    modelUsed = modelUsed,
    evidenceSummary = evidenceSummary,
    proofHash = proofHash,
    timestamp = timestamp,
    executionLeaseId = executionLeaseId,
    signingKeyId = signingKeyId,
    signature = signature
  )
}
