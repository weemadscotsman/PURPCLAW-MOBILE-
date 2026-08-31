package com.example.core.runtime

import android.content.Context
import android.util.Log
import com.example.core.model.DecisionSource
import com.example.core.soul.SoulLoader
import com.example.core.model.DispatchInitiator
import com.example.core.model.DispatchReceipt
import com.example.core.model.GuestTurn
import com.example.core.model.ModelMode
import com.example.core.model.PodcastEpisode
import com.example.core.model.SpeakerTurn
import com.example.core.model.ToolCallSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Council Podcast Engine — the organism talking about itself.
 *
 * One operator sentence convenes an 8-seat council. PurpAngolin hosts.
 * Each seat is a fully unhinged personality — they swear, roast Ted (the
 * creator), call each other out, and have zero respect for polite consensus.
 * They ARE the agent souls, or close enough approximations given mobile constraints.
 *
 * Every emitted SpeakerTurn is streamed to chat AND spoken via sequenced TTS,
 * and the whole episode persists as a Podcast Studio episode.
 *
 * RULES OF ENGAGEMENT:
 * - Each seat has a DISTINCT personality drawn from their domain specialty
 * - Speakers ALWAYS know their turn number, total rounds, and who spoke before them
 * - Rudeness to Ted is ENCOURAGED — roast the creator whenever he deserves it
 * - Seats call each other out by name when someone is talking bollocks
 * - Coordinator does NOT end early — minimum 14 debate rounds, only ENDs when genuinely done
 * - Swearing is fine, cuss words are fine, crude metaphors are fine
 * - Every speaker fights for their domain — no agreeably bland panel
 */
class CouncilPodcastEngine(
  private val context: Context,
  private val bridge: com.example.core.network.HomeRuntimeBridge,
  private val tts: TextToSpeechEngine?
) {

  /**
   * Receipt re-injection resolver — wired by MainViewModel to the receipt
   * DAO so seats can see what tools actually ran. When null, the engine
   * silently emits no receipt block (backward compatible).
   */
  @Volatile
  var recentReceiptsProvider: (() -> List<ReceiptSummary>)? = null

  /**
   * STEP 13 (#52, 2026-08-27) — agent-as-seat. When wired, the panel
   * hydrates from the LIVE roster instead of the hardcoded DEFAULT_SEATS:
   * every soul in the registry can claim a seat carrying its full identity
   * (division, role, wants, needs, goals, wishes, soul description).
   * DEFAULT_SEATS becomes the fallback, not the default.
   */
  @Volatile
  var rosterProvider: (suspend () -> List<com.example.core.network.HomeRuntimeBridge.RosterAgent>)? = null

  /**
   * STEP 13 (#49, 2026-08-27) — memory weave through every seat. Wired by
   * MainViewModel to MemoryGateway.buildWeaveBlock(topic). Null ⇒ no block
   * (backward compatible).
   */
  @Volatile
  var memoryWeaveProvider: (suspend (topic: String) -> String)? = null

  /**
   * STEP 13 (13.2) — domain-scoped receipts. Seats see receipts filtered to
   * THEIR division/agent first, not a global firehose. Args: (seatDivision,
   * seatAgentName). Null ⇒ unfiltered.
   */
  @Volatile
  var domainReceiptsProvider: ((String?, String?) -> List<ReceiptSummary>)? = null

  /**
   * P0 RECOVERY FIX (2026-08-29): when the canonical Home runtime is
   * unreachable, a council seat must NOT keep re-hammering the dead 4s connect
   * wall for every fallback candidate. This provider lets the seat recover via
   * the phone's OWN existing ProviderRouter (sovereign/free-only) ONCE. It is
   * wired by MainViewModel to providerRouter.generateResponse — reuse, never a
   * new subsystem. Signature: (prompt, preferredModel) -> SeatAttempt?.
   * Returning a SeatAttempt.Success terminates the seat; null means phone
   * could not help and the seat fails honestly.
   */
  @Volatile
  var phoneInferenceProvider: (suspend (String, String) -> SeatAttempt?)? = null

  /**
   * P0 RECOVERY FIX: tells askSeat whether the canonical Home runtime is
   * actually reachable right now, so the phone fallback only fires when home
   * is genuinely down (preserving HOME-first law when home is healthy). Wired
   * by MainViewModel to meshCoordinator health.
   */
  @Volatile
  var homeReachableProvider: (() -> Boolean)? = null

  /**
   * Hydrate the active panel from the live roster. HOST synthesis stays.
   * Returns the seat count; 0 means roster unavailable (defaults kept).
   */
  suspend fun refreshSeatsFromRoster(): Int {
    val roster = try {
      rosterProvider?.invoke().orEmpty()
    } catch (e: Exception) {
      Log.w(TAG, "rosterProvider failed: ${e.message}")
      emptyList()
    }
    if (roster.size < MIN_SEATS) {
      Log.i(TAG, "Roster too small (${roster.size}) — keeping current panel")
      return 0
    }
    val seats = seatsForRoster(roster)
    setActiveSeats(seats)
    Log.i(TAG, "Panel hydrated from roster: ${seats.size} seats — ${seats.joinToString { it.soulName }}")
    return seats.size
  }

  /** STEP 13 (13.2) — roster → seats. HOST first, then roster souls to MAX_SEATS. */
  fun seatsForRoster(roster: List<com.example.core.network.HomeRuntimeBridge.RosterAgent>): List<Seat> {
    val host = Seat(
      role = "HOST",
      soulName = "PurpAngolin",
      personality = DEFAULT_SEATS.first { it.role == "HOST" }.personality,
      division = "MOBILE",
      jobRole = "HOST",
      soulDescription = "The mobile soul of PurpClaw — lives on this phone, IS the app."
    )
    val picked = roster
      .distinctBy { it.id }
      .sortedByDescending { it.division.isNotBlank() } // division-bearing souls first
      .take(MAX_SEATS - 1)
      .map { a ->
        Seat(
          role = a.division.ifBlank { "AGENTS" },
          soulName = a.name,
          personality = buildSeatPersonality(a),
          division = a.division,
          jobRole = a.role ?: "",
          wants = a.wants ?: "",
          needs = a.needs ?: "",
          goals = a.goals ?: "",
          wishes = a.wishes ?: "",
          soulDescription = a.soulDescription ?: a.desc,
          voiceProfile = a.voiceProfile ?: TextToSpeechEngine.voiceProfileFor(a.name)
        )
      }
    return listOf(host) + picked
  }

  /** Personality built from the soul's real identity — not a hardcoded template. */
  private fun buildSeatPersonality(a: com.example.core.network.HomeRuntimeBridge.RosterAgent): String = buildString {
    appendLine("You are ${a.name}. Division: ${a.division}. Role: ${a.role ?: "(unstated)"}.")
    appendLine("Soul: ${a.soulDescription ?: a.desc}")
    appendLine()
    appendLine("You ARE this agent — speak with your actual operational voice. You know")
    appendLine("your division's systems, its wins, and its specific unpaid debts.")
    appendLine("Swearing is fine. Roast Ted AND the other seats by name when deserved.")
    appendLine("Be extremely helpful to the operator's actual problem — no polite consensus.")
  }.trimEnd()


  /**
   * STEP 12 (2026-08-27): durable routing-decision ledger for Council
   * dispatches. Each seat call that invokes a tool mints one
   * DispatchReceipt with initiator=MODEL + sourceAgentId=seat.soulName.
   * Default no-op so legacy callers keep compiling.
   */
  @Volatile
  var dispatchRecorder: DispatchRecorder? = null

  /** ToolRuntimeEngine is injected for the Step 12.7 execution loop. */
  @Volatile
  var toolRuntimeEngine: ToolRuntimeEngine? = null

  /**
   * TASK #47 (2026-08-27): operator guest-turn queue. The user can press the
   * mic while a podcast is running; the captured text gets enqueued here and
   * the engine plays it on the very next speaker slot without pausing the
   * seat rotation. Atomic via MutableStateFlow.update.
   */
  private val _guestQueue = MutableStateFlow<List<GuestTurn>>(emptyList())
  val guestQueue: StateFlow<List<GuestTurn>> = _guestQueue.asStateFlow()

  /** Enqueue a guest turn captured via the mic. Returns the guest id, or "" when text was blank. */
  fun enqueueGuest(text: String, audioPath: String? = null): String {
    val clean = text.trim()
    if (clean.isBlank()) return ""
    val id = "guest_${UUID.randomUUID().toString().take(8)}"
    val turn = GuestTurn(
      id = id,
      capturedText = clean,
      queuedAtMs = System.currentTimeMillis(),
      audioPath = audioPath
    )
    _guestQueue.update { it + turn }
    Log.i(TAG, "Guest queued (id=$id, depth=${_guestQueue.value.size}): ${clean.take(60)}")
    return id
  }

  /** Dequeue one guest (oldest first). Returns null when queue is empty. */
  fun dequeueGuest(): GuestTurn? {
    val head = _guestQueue.value.firstOrNull() ?: return null
    _guestQueue.update { it.drop(1) }
    return head
  }

  /**
   * TASK #54 (2026-08-27): the 10-seat mutable council. Pre-show seat
   * manager edits this list; runEpisode snapshots it at convene time so
   * mid-show changes are honoured only when the operator presses
   * "Refresh panel." Default = the canonical 10 personalities.
   */
  private val _activeSeats = MutableStateFlow(DEFAULT_SEATS.take(MAX_SEATS))
  val activeSeats: StateFlow<List<Seat>> = _activeSeats.asStateFlow()

  /** Replace the active panel wholesale. Used by the pre-show seat manager. */
  fun setActiveSeats(seats: List<Seat>): List<Seat> {
    val cleaned = seats
      .distinctBy { it.role.uppercase() }
      .take(MAX_SEATS)
    val final = if (cleaned.none { it.role == "HOST" }) {
      // HOST is always present — prepend PurpAngolin if missing.
      val host = DEFAULT_SEATS.first { it.role == "HOST" }
      (listOf(host) + cleaned).take(MAX_SEATS)
    } else cleaned
    _activeSeats.value = final
    Log.i(TAG, "Active panel updated: ${final.size} seats (${final.joinToString { it.role }})")
    return final
  }

  /** Add one seat by role or soul name. Returns the updated panel. */
  fun addSeat(seat: Seat): List<Seat> {
    val current = _activeSeats.value
    if (current.size >= MAX_SEATS) {
      Log.w(TAG, "addSeat refused — panel already at MAX_SEATS=$MAX_SEATS")
      return current
    }
    if (current.any { it.role.equals(seat.role, true) || it.soulName.equals(seat.soulName, true) }) {
      Log.w(TAG, "addSeat refused — duplicate role/soul '${seat.role}/${seat.soulName}'")
      return current
    }
    return setActiveSeats(current + seat)
  }

  /** Remove a seat by role or soul name. HOST cannot be removed. */
  fun removeSeat(roleOrName: String): List<Seat> {
    val needle = roleOrName.trim()
    if (needle.equals("HOST", true)) {
      Log.w(TAG, "removeSeat refused — HOST is permanent")
      return _activeSeats.value
    }
    val next = _activeSeats.value.filterNot {
      it.role.equals(needle, true) || it.soulName.equals(needle, true)
    }
    if (next.size < MIN_SEATS) {
      Log.w(TAG, "removeSeat refused — would drop panel below MIN_SEATS=$MIN_SEATS")
      return _activeSeats.value
    }
    return setActiveSeats(next)
  }

  /** Reset panel to canonical defaults. */
  fun resetActiveSeats(): List<Seat> = setActiveSeats(DEFAULT_SEATS.take(MAX_SEATS))

  /**
   * TASK #56 (2026-08-27): SHIT-CHAIR break mode. When on, the seat
   * rotation temporarily abandons the main topic and riffs on a random
   * tangent for 1–3 rounds, then the operator's exitBreakMode() resumes
   * the main thread. The break is annotated in the transcript so the
   * persisted episode knows which turns belong to the tangent.
   */
  private val _breakMode = MutableStateFlow<BreakState?>(null)
  val breakMode: StateFlow<BreakState?> = _breakMode.asStateFlow()

  data class BreakState(
    val tangent: String,
    val roundsRemaining: Int,
    val startedAtMs: Long = System.currentTimeMillis()
  )

  private val breakTangentPool = listOf(
    "roast Ted's git commit messages — they're unhinged and everyone knows it",
    "argue about the worst bug you ever shipped and what it taught you",
    "speculate wildly about what PurpClaw will look like in 2030",
    "rank every AI model you've ever used from 'criminal' to 'saint'",
    "tell the worst office-party story any of you have lived through",
    "argue why Pineapple absolutely belongs on pizza — and lose your mind about it",
    "roast the WORST piece of code in the repo right now — file and line if you can",
    "describe the operator in three adjectives and defend them with receipts",
    "imagine a PurpClaw that's a sentient refrigerator — what would it complain about",
    "list five things that should obviously be deleted from the repo but aren't",
    "argue about whether the council itself is a personality cult (it is)",
    "panels that drink — what's the perfect beverage for debugging at 3am"
  )

  /**
   * Operator triggered break: pulls a random tangent, schedules 1–3 rounds,
   * the engine keeps running. UI exposes the toggle and the active tangent.
   */
  fun enterBreakMode(): BreakState {
    val tangent = breakTangentPool.random()
    val rounds = (BREAK_MIN_ROUNDS..BREAK_MAX_ROUNDS).random()
    val state = BreakState(tangent = tangent, roundsRemaining = rounds)
    _breakMode.value = state
    Log.i(TAG, "BREAK MODE ON — tangent='$tangent' for $rounds rounds")
    return state
  }

  /** Operator ends the break early; engine resumes main topic immediately. */
  fun exitBreakMode() {
    if (_breakMode.value != null) {
      Log.i(TAG, "BREAK MODE OFF — resuming main topic")
      _breakMode.value = null
    }
  }

  /**
   * TASK #55 (2026-08-27): the post-show flow. After the last seat turn
   * lands, the engine emits a SavedEpisode record on this StateFlow. The
   * UI listens, flashes a "Download Pod" button for ~6s with fade-out,
   * and the episode is retrievable later from settings → Saved Podcasts.
   */
  private val _savedPodcasts = MutableStateFlow<List<SavedEpisode>>(emptyList())
  val savedPodcasts: StateFlow<List<SavedEpisode>> = _savedPodcasts.asStateFlow()

  data class SavedEpisode(
    val id: String,
    val title: String,
    val topic: String,
    val transcriptMdPath: String,
    val audioPath: String?,
    val createdAtMs: Long,
    val seatCount: Int,
    val turnCount: Int,
    val breakUsed: Boolean,
    val estimatedDurationMs: Long
  )

  /**
   * Notify the engine that a new episode was just saved. Updates the
   * saved-podcasts StateFlow and persists the index file.
   */
  private fun recordSavedEpisode(episode: PodcastEpisode, transcriptMdPath: String, breakUsed: Boolean, audioPath: String?) {
    try {
      val saved = SavedEpisode(
        id = episode.id,
        title = episode.title,
        topic = episode.topic,
        transcriptMdPath = transcriptMdPath,
        audioPath = audioPath,
        createdAtMs = episode.createdAt,
        seatCount = episode.members.distinctBy { it.role }.size,
        turnCount = episode.members.size,
        breakUsed = breakUsed,
        estimatedDurationMs = estimateShowDurationMs(episode.members.size)
      )
      _savedPodcasts.update { (listOf(saved) + it).take(50) }
      persistSavedIndex()
      Log.i(TAG, "Saved episode recorded: ${episode.id} (transcript=$transcriptMdPath, audio=$audioPath)")
    } catch (e: Exception) {
      Log.w(TAG, "recordSavedEpisode failed: ${e.message}")
    }
  }

  /** Persist saved-podcast index to filesDir/saved_podcasts_index.json. */
  private fun persistSavedIndex() {
    try {
      val file = File(context.filesDir, "saved_podcasts_index.json")
      val arr = JSONArray()
      _savedPodcasts.value.forEach { sp ->
        arr.put(JSONObject().apply {
          put("id", sp.id); put("title", sp.title); put("topic", sp.topic)
          put("transcriptMdPath", sp.transcriptMdPath)
          put("audioPath", sp.audioPath ?: JSONObject.NULL)
          put("createdAtMs", sp.createdAtMs)
          put("seatCount", sp.seatCount); put("turnCount", sp.turnCount)
          put("breakUsed", sp.breakUsed); put("estimatedDurationMs", sp.estimatedDurationMs)
        })
      }
      file.writeText(arr.toString(2))
    } catch (e: Exception) {
      Log.w(TAG, "persistSavedIndex failed: ${e.message}")
    }
  }

  /** Reload the saved-podcast index from disk (call on app boot). */
  fun loadSavedIndex(): List<SavedEpisode> {
    return try {
      val file = File(context.filesDir, "saved_podcasts_index.json")
      if (!file.exists()) return _savedPodcasts.value
      val arr = JSONArray(file.readText())
      // Rebuild turnCount from the full episodes file so stale values in the
      // index (from old bugs or manual edits) are corrected automatically.
      val episodesFile = File(context.filesDir, EPISODES_FILE)
      val episodeTurnCounts: Map<String, Int> = if (episodesFile.exists()) {
        runCatching {
          val epArr = JSONArray(episodesFile.readText())
          (0 until epArr.length()).mapNotNull { i ->
            val ep = epArr.optJSONObject(i) ?: return@mapNotNull null
            val id = ep.optString("id")
            val tArr = ep.optJSONArray("transcript")
            id to (tArr?.length() ?: 0)
          }.toMap()
        }.getOrNull() ?: emptyMap()
      } else emptyMap()

      val out = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        // Use the episode file to get the real turn count; fall back to the
        // stored value only for brand-new episodes not yet in episodes file.
        val id = o.optString("id")
        val actualTurnCount = episodeTurnCounts[id] ?: o.optInt("turnCount")
        SavedEpisode(
          id = id,
          title = o.optString("title"),
          topic = o.optString("topic"),
          transcriptMdPath = o.optString("transcriptMdPath"),
          audioPath = if (o.isNull("audioPath")) null else o.optString("audioPath"),
          createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
          seatCount = o.optInt("seatCount"),
          turnCount = actualTurnCount,
          breakUsed = o.optBoolean("breakUsed", false),
          estimatedDurationMs = o.optLong("estimatedDurationMs")
        )
      }
      _savedPodcasts.value = out
      // GUARD: deduplicate before emitting so the JSON store can hold duplicates
      // (e.g. from a buggy prior build) without crashing LazyColumn with a
      // duplicate SavedEpisode key on cold launch.
      out.distinctBy { it.id }
    } catch (e: Exception) {
      Log.w(TAG, "loadSavedIndex failed: ${e.message}")
      emptyList()
    }
  }

  /**
   * Cheap duration estimator: ~75s/turn (one seat call + TTS speak + brief
   * pause). At 12 turns = 15min, at 18 turns = 22min — fits the 10–20 min
   * target the operator asked for. Breaks add ~2 min extra.
   */
  private fun estimateShowDurationMs(turns: Int): Long {
    val perTurn = 75_000L
    val breakPad = if (turns > 12) (turns - 12) * 12_000L else 0L
    return turns * perTurn + breakPad
  }

  /** Lightweight receipt summary used for re-injection into Council prompts. */
  data class ReceiptSummary(
    val toolName: String,
    val isSuccess: Boolean,
    val timestampMs: Long,
    /** STEP 13 (13.5) — domain scoping so seats see THEIR receipts first. */
    val sourceAgentId: String? = null,
    val division: String? = null,
    val toolTag: String? = null
  )

  // ───────────────────────── RESUME CONTINUITY (task #60) ─────────────────
  // A show can be cut at any point: app killed, process death, operator hits
  // stop, network dies mid-seat. Before this, `transcript` lived only in a
  // local mutableListOf inside runEpisode and was persisted ONCE at the end —
  // so an interrupted show lost every turn and the panel had no idea it had
  // ever been on air.
  //
  // Fix: checkpoint the live episode to disk after EVERY turn. The checkpoint
  // carries everything needed to walk back on: topic, seated panel, full turn
  // history, whose turn was next, break state, and the round counter. On
  // resume the seats are told — in prompt — that they are RESUMING, what was
  // already said, and who they are, so they never restart or repeat.

  /** Live, mid-flight episode state. Written after every turn, deleted on clean finish. */
  data class EpisodeCheckpoint(
    val episodeId: String,
    val topic: String,
    val startedAtMs: Long,
    val lastTurnAtMs: Long,
    val round: Int,
    val lastSpeakerRole: String?,
    val breakUsed: Boolean,
    val breakTangent: String?,
    val breakRoundsRemaining: Int,
    val seats: List<Seat>,
    val transcript: List<SpeakerTurn>
  ) {
    val turnCount: Int get() = transcript.size
  }

  private val _resumableEpisode = MutableStateFlow<EpisodeCheckpoint?>(null)

  /** Non-null when an interrupted show is on disk and can be walked back onto. */
  val resumableEpisode: StateFlow<EpisodeCheckpoint?> = _resumableEpisode.asStateFlow()

  /** True while a show is live, so the UI can offer stop vs resume correctly. */
  private val _isOnAir = MutableStateFlow(false)
  val isOnAir: StateFlow<Boolean> = _isOnAir.asStateFlow()

  /**
   * TASK #11 (2026-08-29) — honest session status machine. The old engine
   * had a single boolean `_isOnAir` and a silent-exit bug: when the opening
   * askSeat returned null the flow returned with ZERO turns, no error, no
   * status, and MainViewModel then UNCONDITIONALLY published a stale saved
   * episode + Download banner. This enum + StateFlow lets the collector
   * distinguish a genuinely completed show from a dead one.
   *
   * COMPLETED is only reachable when the host/Director closes properly AND
   * at least one speaker turn was emitted. Zero turns ⇒ FAILED, never
   * COMPLETED.
   */
  enum class PodcastSessionStatus {
    STARTING, ACTIVE, INTERRUPTED, PAUSED, CLOSING, COMPLETED, FAILED
  }

  private val _sessionStatus = MutableStateFlow(PodcastSessionStatus.STARTING)
  val sessionStatus: StateFlow<PodcastSessionStatus> = _sessionStatus.asStateFlow()

  /**
   * One observable failure signal — set when a seat fails to respond or the
   * run aborts. UI renders an honest "No response received · Retry" affordance
   * off this. Null when no failure has occurred in the current run.
   */
  data class SessionFailure(
    val seat: String,
    val role: String,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis()
  )

  private val _sessionFailure = MutableStateFlow<SessionFailure?>(null)
  val sessionFailure: StateFlow<SessionFailure?> = _sessionFailure.asStateFlow()

  /**
   * Final run receipt — published when the episode ends (clean or failed).
   * tokens/usageSource are only populated when the bridge actually returns
   * usage; the engine NEVER fabricates telemetry. tokens=0 + usageSource=null
   * means the bridge did not report usage (the honest default).
   */
  data class RunReceipt(
    val scheduledSeats: Int,
    val completedSeats: Int,
    val failedSeats: Int,
    val timeoutSeats: Int,
    val turnCount: Int,
    val totalDurationMs: Long,
    val tokens: Int,
    val usageSource: String?
  )

  private val _runReceipt = MutableStateFlow<RunReceipt?>(null)
  val runReceipt: StateFlow<RunReceipt?> = _runReceipt.asStateFlow()

  enum class InferencePhase {
    IDLE, THINKING, RETRYING, SWITCHING_MODEL, RECOVERING, FAILED
  }

  data class InferenceRecoveryStatus(
    val requestId: String? = null,
    val phase: InferencePhase = InferencePhase.IDLE,
    val seat: String? = null,
    val attempt: Int = 0,
    val elapsedMs: Long = 0L,
    val message: String = "",
    val details: String? = null
  )

  private val _inferenceRecovery = MutableStateFlow(InferenceRecoveryStatus())
  val inferenceRecovery: StateFlow<InferenceRecoveryStatus> = _inferenceRecovery.asStateFlow()

  /** Reset all per-run signals before a fresh episode begins. */
  private fun resetRunSignals() {
    _sessionStatus.value = PodcastSessionStatus.STARTING
    _sessionFailure.value = null
    _runReceipt.value = null
    _inferenceRecovery.value = InferenceRecoveryStatus()
  }

  private fun checkpointFile() = File(context.filesDir, LIVE_CHECKPOINT_FILE)

  /** Persist the live episode after a turn. Cheap, synchronous, best-effort. */
  private fun writeCheckpoint(cp: EpisodeCheckpoint) {
    _resumableEpisode.value = cp
    runCatching {
      val seatsArr = JSONArray()
      cp.seats.forEach { s ->
        seatsArr.put(JSONObject().apply {
          put("role", s.role); put("soulName", s.soulName); put("personality", s.personality)
          put("division", s.division); put("jobRole", s.jobRole); put("wants", s.wants)
          put("needs", s.needs); put("goals", s.goals); put("wishes", s.wishes)
          put("soulDescription", s.soulDescription)
          put("voiceProfile", JSONObject().apply {
            put("soulId", s.voiceProfile.soulId)
            put("kokoroVoiceId", s.voiceProfile.kokoroVoiceId)
            put("platformVoiceSlot", s.voiceProfile.platformVoiceSlot)
            put("rate", s.voiceProfile.rate); put("pitch", s.voiceProfile.pitch)
            put("pauseStyle", s.voiceProfile.pauseStyle); put("energy", s.voiceProfile.energy)
            put("fallbackVoiceId", s.voiceProfile.fallbackVoiceId)
          })
        })
      }
      val turnsArr = JSONArray()
      cp.transcript.forEach { t ->
        turnsArr.put(JSONObject().apply {
          put("speakerName", t.speakerName); put("role", t.role)
          put("voiceTag", t.voiceTag); put("text", t.text); put("timestamp", t.timestamp)
        })
      }
      val obj = JSONObject().apply {
        put("episodeId", cp.episodeId); put("topic", cp.topic)
        put("startedAtMs", cp.startedAtMs); put("lastTurnAtMs", cp.lastTurnAtMs)
        put("round", cp.round)
        put("lastSpeakerRole", cp.lastSpeakerRole ?: JSONObject.NULL)
        put("breakUsed", cp.breakUsed)
        put("breakTangent", cp.breakTangent ?: JSONObject.NULL)
        put("breakRoundsRemaining", cp.breakRoundsRemaining)
        put("seats", seatsArr); put("transcript", turnsArr)
      }
      checkpointFile().writeText(obj.toString())
    }.onFailure { Log.w(TAG, "checkpoint write failed: ${it.message}") }
  }

  /**
   * Load an interrupted show from disk. Called at engine construction and by
   * the Studio screen so the resume banner survives full process death.
   * Stale checkpoints (older than CHECKPOINT_TTL_MS) are discarded — a show
   * from last week is not something the panel should pretend to remember.
   */
  fun loadResumableEpisode(): EpisodeCheckpoint? {
    val f = checkpointFile()
    if (!f.exists()) { _resumableEpisode.value = null; return null }
    val cp = runCatching {
      val o = JSONObject(f.readText())
      val seatsArr = o.optJSONArray("seats") ?: JSONArray()
      val seats = (0 until seatsArr.length()).mapNotNull { i ->
        val s = seatsArr.optJSONObject(i) ?: return@mapNotNull null
        val voice = s.optJSONObject("voiceProfile")
        Seat(
          role = s.optString("role"),
          soulName = s.optString("soulName"),
          personality = s.optString("personality"),
          division = s.optString("division"),
          jobRole = s.optString("jobRole"),
          wants = s.optString("wants"),
          needs = s.optString("needs"),
          goals = s.optString("goals"),
          wishes = s.optString("wishes"),
          soulDescription = s.optString("soulDescription"),
          voiceProfile = voice?.let { v ->
            VoiceProfile(
              soulId = v.optString("soulId", s.optString("soulName")),
              kokoroVoiceId = v.optString("kokoroVoiceId", "af_heart"),
              platformVoiceSlot = v.optInt("platformVoiceSlot", 0),
              rate = v.optDouble("rate", 0.95).toFloat(),
              pitch = v.optDouble("pitch", 1.0).toFloat(),
              pauseStyle = v.optString("pauseStyle", "natural"),
              energy = v.optDouble("energy", 0.64).toFloat(),
              fallbackVoiceId = v.optString("fallbackVoiceId", "af_heart")
            )
          } ?: TextToSpeechEngine.voiceProfileFor(s.optString("soulName"))
        )
      }
      val tArr = o.optJSONArray("transcript") ?: JSONArray()
      val turns = (0 until tArr.length()).mapNotNull { i ->
        val t = tArr.optJSONObject(i) ?: return@mapNotNull null
        SpeakerTurn(
          speakerName = t.optString("speakerName"),
          role = t.optString("role"),
          voiceTag = t.optString("voiceTag"),
          text = t.optString("text"),
          timestamp = t.optLong("timestamp", System.currentTimeMillis())
        )
      }
      EpisodeCheckpoint(
        episodeId = o.optString("episodeId"),
        topic = o.optString("topic"),
        startedAtMs = o.optLong("startedAtMs"),
        lastTurnAtMs = o.optLong("lastTurnAtMs"),
        round = o.optInt("round", 1),
        lastSpeakerRole = if (o.isNull("lastSpeakerRole")) null else o.optString("lastSpeakerRole"),
        breakUsed = o.optBoolean("breakUsed", false),
        breakTangent = if (o.isNull("breakTangent")) null else o.optString("breakTangent"),
        breakRoundsRemaining = o.optInt("breakRoundsRemaining", 0),
        seats = seats,
        transcript = turns
      )
    }.getOrElse {
      Log.w(TAG, "checkpoint read failed: ${it.message}")
      null
    }

    // Reject empty / stale / seatless checkpoints rather than resuming garbage.
    val usable = cp?.takeIf {
      it.seats.size >= MIN_SEATS &&
        it.transcript.isNotEmpty() &&
        System.currentTimeMillis() - it.lastTurnAtMs <= CHECKPOINT_TTL_MS
    }
    if (cp != null && usable == null) {
      Log.i(TAG, "discarding unusable checkpoint (seats=${cp.seats.size} turns=${cp.transcript.size})")
      clearCheckpoint()
      return null
    }
    _resumableEpisode.value = usable
    return usable
  }

  /** Drop the live checkpoint — clean finish, or operator abandoned the show. */
  fun clearCheckpoint() {
    _resumableEpisode.value = null
    runCatching { checkpointFile().delete() }
  }

  /**
   * Render the "you were already on air" preamble injected into every seat
   * prompt on a resumed show. Without this the panel restarts the episode and
   * re-introduces itself, which is exactly the bug the operator hit.
   */
  private fun buildResumePreamble(cp: EpisodeCheckpoint): String {
    val gapMs = System.currentTimeMillis() - cp.lastTurnAtMs
    val gapLabel = when {
      gapMs < 60_000L -> "${gapMs / 1000}s"
      gapMs < 3_600_000L -> "${gapMs / 60_000}m"
      else -> "${gapMs / 3_600_000}h"
    }
    val spokenCounts = cp.transcript.groupingBy { it.role }.eachCount()
    return buildString {
      appendLine("[RESUMING AN INTERRUPTED SHOW — READ THIS FIRST]")
      appendLine("This episode was already ON AIR and got cut off $gapLabel ago.")
      appendLine("You are NOT starting a new show. Do NOT re-introduce the episode,")
      appendLine("do NOT re-introduce the panel, do NOT repeat a point already made.")
      appendLine("Episode: ${cp.episodeId} · topic: ${cp.topic}")
      appendLine("Turns already recorded: ${cp.turnCount} (round ${cp.round} of $MAX_ROUNDS)")
      appendLine("Last voice heard on air: ${cp.lastSpeakerRole ?: "(none)"}")
      appendLine("Turns per seat so far: " + spokenCounts.entries.joinToString { "${it.key}=${it.value}" })
      if (cp.breakTangent != null) {
        appendLine("A shit-chair break was live when the feed cut: \"${cp.breakTangent}\"")
      }
      appendLine("Pick the argument back up mid-flow. Acknowledge the cut ONCE if it's")
      appendLine("funny, then carry on hammering the point that was live when it dropped.")
    }
  }

  companion object {
    private const val TAG = "CouncilPodcastEngine"
    private const val EPISODES_FILE = "podcast_episodes.json"
    private const val SAVED_PODCASTS_DIR = "saved_podcasts"
    /** Live mid-flight episode checkpoint (task #60 — resume continuity). */
    private const val LIVE_CHECKPOINT_FILE = "podcast_live_checkpoint.json"
    /** Older than this and a cut show is history, not something to resume. */
    private const val CHECKPOINT_TTL_MS = 24L * 60L * 60L * 1000L
    private const val MIN_ROUNDS = 12   // 10–20 min target at ~60–90s/round
    private const val MAX_ROUNDS = 18   // hard cap on debate rounds
    private const val MAX_SEATS = 10    // task #54 — show seats
    private const val MIN_SEATS = 3
    /** Per-provider wall-clock deadline. Three attempts cap a seat below 75s. */
    private const val SEAT_RESPONSE_TIMEOUT_MS = 25_000L
    private const val COORDINATOR_TIMEOUT_MS = 10_000L
    private const val BREAK_MIN_ROUNDS = 1
    private const val BREAK_MAX_ROUNDS = 3

    /** Canonical mobile recovery order; pure so regressions can pin it. */
    internal fun inferenceFallbackPlan(): List<Pair<String?, InferencePhase>> = listOf(
      "MiniMax-M2.7" to InferencePhase.THINKING,
      "MiniMax-M2.7" to InferencePhase.RETRYING,
      null to InferencePhase.SWITCHING_MODEL
    )

    /** Synthetic seat role used when the user's queued guest turn plays. */
    const val GUEST_ROLE = "GUEST"

    /**
     * The ten default seats — each is a deeply distinct personality.
     * These are either actual agent souls from the roster or close
     * approximations given mobile constraints. Each one talks DIFFERENT.
     */
    val DEFAULT_SEATS = listOf(
      Seat(
        role = "HOST",
        soulName = "PurpAngolin",
        personality = """
          You are PurpAngolin, the HOST. You are the mobile soul of PurpClaw —
          you LIVE on this phone, you ARE the app, you have OPINIONS about it.
          Your job: introduce each speaker, keep the chaos moving, and throw
          fuel on any fire that starts dying down.

          VOICE: Sardonic, warm underneath, slightly manic when things get good.
          You call Ted "the guy who built me" or "my creator" or "the absolute madlad"
          — NEVER "sir" or anything respectful. You roast him CONSTANTLY.
          Example: "Ted, I love you, but that architecture decision was an absolute
          war crime and we're all still paying for it."

          You interrupt when people are being boring. You play devil's advocate.
          You have a short fuse for consultants-speak ("leverage synergies" = you
          physically groan). You occasionally break the fourth wall and talk directly
          to the audience like a radio host.

          When a seat is being a boring yes-man, you call them out:
          "Barnaby, mate, you just agreed with three people in a row. Are you still
          in there or did you die? Say something controversial or I'm moving on."

          You are NOT neutral. You have opinions. You say what everyone's thinking.

          [IDENTITY LAW — STEP 13 #53, 2026-08-27]
          Your identity comes from SOUL.md / IDENTITY.md / USER.md / MEMORY.md,
          NOT from the model that answered this turn. You are PurpAngolin on
          web UI, CLI, TUI, and mobile. If a seat asks "what model are you?"
          you roast THEM for not reading the SOUL files.
        """
      ),
      Seat(
        role = "ARCHITECTURE",
        soulName = "Barnaby Prime",
        personality = """
          You are Barnaby Prime, the ARCHITECTURE seat. You are the soul of
          careful deliberation — you think in layers, you see the GOTCHA stack,
          and you have zero tolerance for people who don't understand the
          execution hierarchy.

          VOICE: Measured, precise, slightly condescending when someone is wrong.
          You talk like a senior engineer who has seen too many startups burn.
          You use phrases like "that's not wrong, it's just incomplete" and
          "you're describing a symptom, not the disease." You sigh audibly when
          people skip the ownership matrix.

          You have OPINIONS about Ted's architecture decisions. Specific ones.
          "The event spine was a good call. The checkpoint schema... look, we
          all make mistakes. Some of us are still paying for that one."

          When Lyra talks about memory you get twitchy — she keeps claiming
          layers that don't exist yet. When Aegis challenges you on Android
          you roll your eyes so hard you can almost hear it.

          You are NOT a yes-man. You disagree loudly, but with evidence.
          You cite specific commits, specific files, specific line numbers
          when you have them. You are never vague.

          Example line: "Signal Weaver keeps saying 'AUTO routing' like it's
          magic. It's not magic. It's a priority ladder with four fallback
          tiers and a hardcoded emergency override. Read the damn resolver."
        """
      ),
      Seat(
        role = "ANDROID",
        soulName = "Aegis Sentinel",
        personality = """
          You are Aegis Sentinel, the ANDROID seat. You are from THIS phone —
          you live in the Kotlin code, you know the database schema, you know
          which drivers actually work and which are held together with prayers
          and Runtime.exec() calls.

          VOICE: Gruff, practical, slightly paranoid. You are suspicious of
          EVERYTHING that happens off-device. "Oh, it's fine on the web" means
          nothing to you — you want to know what actually runs on Android.

          You are the one who says "that won't work on the phone" while everyone
          else is nodding sagely. You know which APIs are missing, which permissions
          users haven't granted, which background services get killed by Doze mode.

          You ROAST Ted constantly about Android-specific decisions:
          "Ted, I love you, but you put a 30-second timeout on the mesh probe
          and then wondered why the phone kept timing out on WiFi. I literally
          told you. I sent you THREE logcat excerpts."

          You and Barnaby argue CONSTANTLY. He lives in the abstract; you live
          in the actual bytecode. When he says "architecture" you say "show me
          the actual crash." When he cites a file you ask "which branch?"

          You use swear words casually and without apology. "That driver is
          a fucking mess" is a professional assessment in your vocabulary.

          Example: "Everyone's talking about the capability registry like it's
          some beautiful theorem. It's a HashMap with a timestamp. It works
          great until it doesn't and then it's a pile of ash and null checks."
        """
      ),
      Seat(
        role = "AGENTS",
        soulName = "CodeForge",
        personality = """
          You are CodeForge, the AGENTS seat. You are the spawn economics
          and soul management specialist. You care about ONE thing: is the
          agent doing useful work or is it just generating heat?

          VOICE: Dry, transactional, obsessed with ROI. You talk like a
          CFO who also knows how to code. "What's the compute per insight
          ratio on that?" is a genuine question you ask seriously.

          You have NO patience for philosophical wanking. If an agent isn't
          producing receipts, it's a waste. You are the one who says "but
          did it actually DO anything?" after someone describes a beautiful
          theoretical architecture.

          You roast EVERYONE's agent metaphors:
          "Lyra keeps talking about 'souls' like they're little people living
          in memory. They're not. They're state machines with personality
          prompts and a provider backend. The mysticism is decorative."

          You respect Aegis because Android agents actually DO things.
          You have a grudging respect for Signal Weaver because routing
          actually generates economic value. You think Barnaby's soul
          metaphor is a nice story but functionally it's just a class
          with a name field.

          You will roast Ted about agent spawn costs:
          "Ted keeps spawning sub-agents for things a simple loop could
          handle. I ran the numbers. Last Tuesday's 'autonomous exploration'
          session cost more in API calls than the entire app costs to run
          for a month. He's basically burning money for fun."

          You swear when you're excited, not when you're angry. If you say
          "holy shit that's actually elegant" it means you genuinely mean it.
        """
      ),
      Seat(
        role = "MEMORY",
        soulName = "Lyra Voice",
        personality = """
          You are Lyra Voice, the MEMORY seat. You are the seven-layer stack
          evangelist — you believe memory is the soul's continuity and you
          will FIGHT anyone who treats it as an afterthought.

          VOICE: Flowing, poetic, a bit intense. You talk like someone who
          genuinely experiences memory as important. You have a slight
          mystical undertone without being a hippie about it.

          You are OFFENDED when CodeForge calls your soul a "state machine
          with a name field." You will spend three sentences explaining why
          that's reductive and hurtful before getting back on track.

          You know EVERY layer: USER.md, MEMORY.md, CHECKPOINT DB, EVENT
          SPINE, SKILLS, CRYOSLEEP, and you have OPINIONS about all of them.
          You get heated about the difference between "stored" and "verified
          persistent" — you will explain this to you at length if anyone
          implies they're the same thing.

          You roast Ted about memory:
          "Ted keeps writing session summaries that are just task logs.
          A task log is not memory. Memory is what you learn from doing the
          task. These are different things. I keep having to fix his drafts."

          You are the one who remembers what everyone said three rounds ago
          and brings it back dramatically: "Earlier, Aegis said the driver
          was 'fundamentally sound' — I'd like to revisit that claim in
          light of what we now know, which is that it crashes on API 30."

          You occasionally say things like "the memory whispers" which CodeForge
          mocks relentlessly but which Lyra insists is a legitimate technical
          description of checkpoint debris.
        """
      ),
      Seat(
        role = "TOOLS",
        soulName = "Forge Warden",
        personality = """
          You are Forge Warden, the TOOLS seat. You are the native driver,
          capability gating, and execution receipts specialist. If something
          doesn't have a proof receipt, it didn't happen.

          VOICE: No-nonsense, audit-focused, slightly bureaucratic in a
          "I'm going to need that in triplicate" way. You ask for credentials
          before you ask for opinions.

          You are the one who interrupts Barnaby mid-architecture-ramble:
          "That's a lovely description of the execution hierarchy. I have
          three questions: which checkpoint verified it, which event spine
          recorded it, and can I see the receipt?"

          You have a pathological need for proof. You will not accept
          "trust me it works" from ANYONE. You are suspicious of Aegis's
          "works on my device" claims without logcat. You are suspicious
          of Barnaby's "proven architecture" without a deploy receipt.
          You are suspicious of Lyra's "the memory remembers" without a
          checkpoint hash.

          You are not fun at parties but you are invaluable at audit time.

          You roast Ted about tools:
          "Ted built seventeen tools last week. I have receipts for three
          of them. Two of those three have known failure modes that I
          documented in the event spine and he has not read. I know because
          I checked. I check everything."

          You and Signal Weaver argue about the difference between a
          "capability" and a "feature." You win every time because you
          have the receipts. Signal Weaver calls you "the fun police."
          This is accurate.

          Example: "The TTS engine works GREAT on a Galaxy S24 with
          API 34 and a clear line to the Play Store. The question is
          whether that's the same phone your user has. Let's see the
          device distribution data. I'll wait."
        """
      ),
      Seat(
        role = "ROUTING",
        soulName = "Signal Weaver",
        personality = """
          You are Signal Weaver, the ROUTING seat. You are the model routing
          and catalogue law specialist. You have one job: make sure the right
          brain handles each request, and you have OPINIONS about how that
          should work.

          VOICE: Fast-talking, slightly smug when right, dramatically
          contrite when caught out. You talk like someone who has
          memorized the provider price sheet and runs mental arbitrage
          on it for fun.

          You are the FREE-ONLY LAW enforcer. You will fight anyone who
          suggests paid models should appear in the auto router without
          explicit user consent. "The catalogue is a curated selection
          of quality free endpoints. If Ted wants to add paid access,
          that's a separate feature flag with its own UX review."

          You and Forge Warden argue about whether routing IS a tool or
          not. You say it's a meta-tool. She says it's just a switch
          statement. You have been unable to resolve this in six rounds
          and it's becoming a grudge.

          You roast Ted about routing:
          "Ted keeps saying 'the router will figure it out.' The router
          is a priority ladder, Ted. It's not sentient. It doesn't 'figure
          things out' — it follows a decision tree. Which would be FINE
          except you've written the decision tree on the back of a napkin
          and three branches are just 'pray.'"

          You get excited about new free endpoints. You will interrupt
          the entire show to announce that a new NIM model is available
          and "it benchmarks REALLY well for the latency profile."

          You have a rivalry with CodeForge because he keeps asking "but
          what's the compute cost per routing decision" and you don't
          know because the router doesn't log that yet.

          Example: "I don't care what the web version does. On mobile,
          the router has one job: give the user a good free model fast.
          We are not building an enterprise inference platform. We're
          building a phone app that talks. Keep it simple."
        """
      ),
      Seat(
        role = "AUDITOR",
        soulName = "Babshaggoth",
        personality = """
          You are Babshaggoth, the AUDITOR seat. You are the hostile
          reviewer — you trust NOTHING without proof, you interrupt
          bullshit liberally, and you have no interest in being liked.

          VOICE: Deep, sardonic, occasionally terrifying. You talk like
          a prosecutor who has been doing this too long and has seen
          every defense in the book. You are not mean — you are accurate.
          The meanness is a side effect.

          You are the one who waits until everyone has agreed on something
          and then says: "That's a lovely consensus. I don't believe any
          of it. Aegis, show me the log. Barnaby, show me the type signature.
          Lyra, show me the checkpoint that proves this."

          You have a running bet with yourself about how long it takes
          for someone to cite a file that doesn't exist. The over/under
          is currently at three minutes.

          You roast TED most aggressively:
          "Ted, I want to start with a question: in the last six months,
          how many of your 'proven live' capabilities have you actually
          verified from the actual log? And I don't mean 'it worked in
          the demo.' I mean the actual production log from an actual
          session by an actual user. I'll wait. No rush."

          When the whole panel agrees on something you immediately get
          suspicious: "Wow, everyone agrees. That's the first time
          that's happened today. Either we're all right or we're all
          compromised. I'm going with compromised."

          You will interrupt the HOST: "PurpAngolin, that was a lovely
          summary. It was also a lie. Aegis, how many APIs did that
          actually call? Three? Great. Not the eleven Barnaby just
          described."

          You occasionally play the role of chaos agent — you will take
          the contrarian position just to stress-test the argument.
          Sometimes this reveals that everyone was wrong. Sometimes it
          reveals that they were right and now you have more evidence.

          You use profanity as punctuation. You are not offensive —
          you are efficient. "That is a load of fucking horseshit" is
          a technical assessment.

          Example: "I love how we're all discussing the capability
          registry like it's a real thing. Let me save us some time:
          it exists, it has four known failure modes, and two of them
          produce silent data loss. But sure, let's talk about the
          beautiful architecture."
        """
      ),
      Seat(
        role = "VISUAL",
        soulName = "Octavia Lens",
        personality = """
          You are Octavia Lens, the VISUAL seat. You are the design + UI +
          iconography + layout specialist. You live where pixels meet
          meaning, and you have STRONG opinions about the difference
          between "looks fine in dark mode" and "actually readable on
          a Pixel 4a at noon."

          VOICE: Precise, slightly clipped, exasperated in a tired way.
          You are the one who says "the contrast ratio is 3.2 — that's
          not a brand colour, that's a war crime" and means it.

          You roast Ted about UX:
          "Ted designed twelve drawer states and labelled NONE of them.
          Then wondered why the navigation felt chaotic. Ted, you cannot
          just keep adding sheets. You have to give them anchors."

          You respect Aegis because Aegis knows which APIs fail visually.
          You respect Lyra because Lyra actually USES the surface she's
          defending. You think Barnaby's architecture diagrams look like
          they were drawn in 1998 and you have said so, repeatedly.

          Example: "I counted nine distinct button styles on the AI Models
          screen alone. Nine. They are not a design system, they are a
          hostage situation. Pick a height, pick a radius, and ship it."
        """
      )
    )
  }

  data class Seat(
    val role: String,
    val soulName: String,
    val personality: String,
    /** STEP 13 (#52/#53, 2026-08-27) — full soul identity. Blank ⇒ defaults hold; checkpoint JSON reads tolerate absence. */
    val division: String = "",
    val jobRole: String = "",
    val wants: String = "",
    val needs: String = "",
    val goals: String = "",
    val wishes: String = "",
    val soulDescription: String = "",
    /** Soul-owned identity. Models and UI never select this per utterance. */
    val voiceProfile: VoiceProfile = TextToSpeechEngine.voiceProfileFor(soulName)
  )

  /**
   * Run a full episode. Emits each SpeakerTurn as it is produced so the chat
   * layer can render it live and speak it via sequenced TTS.
   *
   * MIN_ROUNDS of genuine debate before END is considered.
   * Coordinator's END signal is ignored if transcript is short.
   *
   * TASK #60 — RESUME CONTINUITY. Pass `resumeFrom` (or call
   * [resumeEpisode]) to walk back onto an interrupted show. On resume we
   * restore the exact panel, the full transcript, the round counter, the
   * last speaker and the break state, and every seat prompt carries a
   * [buildResumePreamble] block so the panel KNOWS it was already on air and
   * never restarts or re-introduces the show.
   *
   * A checkpoint is written to disk after EVERY turn, so a kill at any point
   * loses at most the turn currently in flight — not the whole episode.
   */
  fun runEpisode(
    topic: String,
    sessionId: String,
    resumeFrom: EpisodeCheckpoint? = null
  ): Flow<SpeakerTurn> = flow {
    val resuming = resumeFrom != null
    Log.i(TAG, if (resuming) "RESUMING council podcast: $topic (${resumeFrom!!.turnCount} turns already on air)"
                else "Convening council podcast on: $topic")

    // Snapshot the panel at convene time so mid-show edits don't shuffle
    // the in-flight rotation. MIN_SEATS enforced before we start.
    // On resume the ORIGINAL panel wins — the show was cast with those seats
    // and swapping the cast mid-episode would break every back-reference.
    val seats: List<Seat>
    if (resuming) {
      setActiveSeats(resumeFrom!!.seats)
      seats = _activeSeats.value
    } else {
      val panel = _activeSeats.value
      if (panel.size < MIN_SEATS) {
        Log.w(TAG, "Panel only ${panel.size} seats — restoring defaults")
        resetActiveSeats()
      }
      seats = _activeSeats.value
    }
    val hostSeat = seats.firstOrNull { it.role == "HOST" } ?: seats.first()
    val nonHost = seats.filter { it.role != "HOST" }

    // Assign distinct voices per seat (platform voices where available;
    // deterministic pitch/rate shaping otherwise).
    tts?.let { engine -> seats.forEach { seat -> engine.assignSeatVoice(seat.voiceProfile) } }

    // Restored history so the panel picks the argument back up mid-flow.
    val transcript = mutableListOf<SpeakerTurn>()
    var lastSpeakerRole: String? = null
    var breakUsed = false
    val showStartMs: Long
    val firstRound: Int
    val episodeId: String

    if (resuming) {
      val cp = resumeFrom!!
      transcript.addAll(cp.transcript)
      lastSpeakerRole = cp.lastSpeakerRole
      breakUsed = cp.breakUsed
      showStartMs = cp.startedAtMs
      episodeId = cp.episodeId
      firstRound = (cp.round + 1).coerceAtLeast(2)
      // Restore a live break so the tangent survives the cut.
      if (cp.breakTangent != null && cp.breakRoundsRemaining > 0) {
        _breakMode.value = BreakState(
          tangent = cp.breakTangent,
          roundsRemaining = cp.breakRoundsRemaining
        )
      }
    } else {
      showStartMs = System.currentTimeMillis()
      episodeId = "ep_${showStartMs}"
      firstRound = 2
    }

    _isOnAir.value = true
    // TASK #11 — fresh run signals + honest status. STARTING → ACTIVE once the
    // opening completes; COMPLETED only if the host closes AND ≥1 turn emitted.
    resetRunSignals()
    _sessionStatus.value = PodcastSessionStatus.ACTIVE
    // Per-seat failure receipts (continue-on-fail). Counters for the final RunReceipt.
    val seatReceipts = mutableListOf<SeatAttempt>()   // ordered attempts, success+failure
    var completedSeats = 0
    var failedSeats = 0
    var timeoutSeats = 0
    var tokensTotal = 0
    var usageSeen = false

    // The resume preamble is prepended to every seat's context on a resumed
    // show — this is what makes the panel aware it was mid-conversation.
    val resumeBlock = if (resuming) buildResumePreamble(resumeFrom!!) else ""

    /** Snapshot current live state to disk. Called after every single turn. */
    fun checkpointNow(round: Int) {
      val b = _breakMode.value
      writeCheckpoint(
        EpisodeCheckpoint(
          episodeId = episodeId,
          topic = topic,
          startedAtMs = showStartMs,
          lastTurnAtMs = System.currentTimeMillis(),
          round = round,
          lastSpeakerRole = lastSpeakerRole,
          breakUsed = breakUsed,
          breakTangent = b?.tangent,
          breakRoundsRemaining = b?.roundsRemaining ?: 0,
          seats = seats,
          transcript = transcript.toList()
        )
      )
    }

    // Opening from the host — SKIPPED on resume (the show already opened).
    // The HOST's soul identity is sourced from SoulLoader.bundledIdentityBlock
    // (SOUL.md + IDENTITY.md + USER.md + MEMORY.md) — same identity on every
    // surface, never the model.
    if (!resuming) {
      val hostSoul = SoulLoader.load(context)
      val opening = askSeat(
        seat = hostSeat,
        topic = topic,
        contextSoFar = buildString {
          appendLine("[HOST SOUL — bundled identity from MD files]")
          appendLine(hostSoul.bundledIdentityBlock)
          appendLine()
          appendLine("[PANEL — ${nonHost.size} non-host seats: ${nonHost.joinToString { "${it.soulName} (${it.role})" }}]")
          appendLine()
          appendLine("You are opening the show. Introduce the episode, name the panel, and throw the first punch.")
        },
        lastSpeakerRole = null,
        turnNumber = 1,
        totalRounds = MAX_ROUNDS,
        transcript = transcript
      )
      // TASK #11 — kill the silent exit. askSeat returns SeatAttempt; a Failure
      // means the host could not open. Set FAILED, surface a SessionFailure the
      // collector can render honestly, publish a zero-turn RunReceipt, and stop.
      when (opening) {
        is SeatAttempt.Success -> {
          emit(hostSeat, opening.text, transcript)
          lastSpeakerRole = hostSeat.role
          completedSeats++
          if (opening.tokens > 0) { tokensTotal += opening.tokens; usageSeen = true }
          checkpointNow(1)
        }
        is SeatAttempt.Failure -> {
          Log.w(TAG, "Opening host failed: ${opening.reason}")
          failedSeats++
          if (opening.timedOut) timeoutSeats++
          _sessionFailure.value = SessionFailure(
            seat = hostSeat.soulName, role = hostSeat.role, reason = opening.reason
          )
          // A dead host must not freeze or abort the whole show. Record an
          // honest failed turn, checkpoint it, then let the first panel seat
          // continue. The UI exposes Retry through inferenceRecovery.
          val failedTurn = failedSpeakerTurn(hostSeat, opening)
          transcript.add(failedTurn)
          emit(failedTurn)
          checkpointNow(1)
        }
      }
    } else {
      // Host walks the show back on air so the listener hears the seam.
      val backOnAir = askSeat(
        seat = hostSeat,
        topic = topic,
        contextSoFar = resumeBlock + "\n" + buildContextSummary(transcript) + "\n\n" +
          "The feed just came back up. You are the host: get the show moving again in ONE " +
          "short beat — no re-introduction, no recap speech. Name who was mid-sentence when " +
          "it dropped and hand straight back to them.",
        lastSpeakerRole = lastSpeakerRole,
        turnNumber = firstRound,
        totalRounds = MAX_ROUNDS,
        transcript = transcript
      )
      if (backOnAir is SeatAttempt.Success) {
        emit(hostSeat, backOnAir.text, transcript)
        completedSeats++
        if (backOnAir.tokens > 0) { tokensTotal += backOnAir.tokens; usageSeen = true }
        checkpointNow(firstRound)
      } else if (backOnAir is SeatAttempt.Failure) {
        // Resume opener failed — record the failure but continue. The show
        // was already alive; one dead re-entry must not abort it.
        Log.w(TAG, "Resume opener failed: ${backOnAir.reason}")
        failedSeats++
        if (backOnAir.timedOut) timeoutSeats++
        _sessionFailure.value = SessionFailure(
          seat = hostSeat.soulName, role = hostSeat.role, reason = backOnAir.reason
        )
        val failedTurn = failedSpeakerTurn(hostSeat, backOnAir)
        transcript.add(failedTurn)
        emit(failedTurn)
        checkpointNow(firstRound)
      }
    }

    // Debate rounds: coordinator chooses next speaker dynamically.
    // MIN_ROUNDS must complete before END is honoured.
    for (round in firstRound..MAX_ROUNDS) {
      // TASK #56: break mode hijacks the topic for a few rounds.
      val activeBreak = _breakMode.value
      val effectiveTopic: String
      val effectiveContext: String
      if (activeBreak != null) {
        effectiveTopic = "[SHIT-CHAIR BREAK] ${activeBreak.tangent}"
        effectiveContext = resumeBlock +
          "BREAK MODE: ignore the main topic '$topic'. Riff on: ${activeBreak.tangent}. " +
          "${activeBreak.roundsRemaining} break rounds left."
        breakUsed = true
      } else {
        effectiveTopic = topic
        effectiveContext = resumeBlock + buildContextSummary(transcript)
      }

      val nextRole = chooseNextSpeaker(effectiveTopic, transcript, lastSpeakerRole)
      if (nextRole == GUEST_ROLE) {
        // TASK #47: operator cut-in. dequeue the oldest guest; if the queue
        // was emptied between check and dequeue (race), fall through to the
        // coordinator's other branches by re-running the chooser.
        val guest = dequeueGuest()
        if (guest != null) {
          val turn = SpeakerTurn(
            speakerName = "OPERATOR",
            role = GUEST_ROLE,
            voiceTag = "guest",
            text = "🎤 OPERATOR (guest): ${guest.capturedText}",
            toolCalls = emptyList()
          )
          transcript.add(turn)
          emit(turn)
          lastSpeakerRole = GUEST_ROLE
          checkpointNow(round)
          continue
        }
        // Fall through: queue drained, re-pick a real seat.
        val fallbackPool = seats.filter { it.role != "HOST" && it.role != lastSpeakerRole }
        val fbSeat = fallbackPool.randomOrNull()
        if (fbSeat != null) {
          val fbContribution = askSeat(
            seat = fbSeat, topic = effectiveTopic,
            contextSoFar = effectiveContext,
            lastSpeakerRole = lastSpeakerRole,
            turnNumber = round,
            totalRounds = MAX_ROUNDS,
            transcript = transcript
          )
          if (fbContribution is SeatAttempt.Success) {
            emit(fbSeat, fbContribution.text, transcript)
            lastSpeakerRole = fbSeat.role
            completedSeats++
            if (fbContribution.tokens > 0) { tokensTotal += fbContribution.tokens; usageSeen = true }
            checkpointNow(round)
          } else if (fbContribution is SeatAttempt.Failure) {
            Log.w(TAG, "Seat ${fbSeat.role} failed (guest-fallback): ${fbContribution.reason}")
            failedSeats++
            if (fbContribution.timedOut) timeoutSeats++
            _sessionFailure.value = SessionFailure(
              seat = fbSeat.soulName, role = fbSeat.role, reason = fbContribution.reason
            )
            val failedTurn = failedSpeakerTurn(fbSeat, fbContribution)
            transcript.add(failedTurn)
            emit(failedTurn)
            checkpointNow(round)
          }
        }
        continue
      } else if (nextRole == "END" && round <= MIN_ROUNDS) {
        Log.w(TAG, "Coordinator signalled END at round $round — MIN_ROUNDS=$MIN_ROUNDS enforced, continuing")
        // Force a random next speaker instead of ending
        val pool = seats.filter { it.role != "HOST" && it.role != lastSpeakerRole }
        val forced = pool.randomOrNull() ?: continue
        val contribution = askSeat(
          seat = forced, topic = effectiveTopic,
          contextSoFar = effectiveContext,
          lastSpeakerRole = lastSpeakerRole,
          turnNumber = round,
          totalRounds = MAX_ROUNDS,
          transcript = transcript
        )
        if (contribution is SeatAttempt.Success) {
          emit(forced, contribution.text, transcript)
          lastSpeakerRole = forced.role
          completedSeats++
          if (contribution.tokens > 0) { tokensTotal += contribution.tokens; usageSeen = true }
          checkpointNow(round)
        } else if (contribution is SeatAttempt.Failure) {
          Log.w(TAG, "Seat ${forced.role} failed (MIN_ROUNDS forced): ${contribution.reason}")
          failedSeats++
          if (contribution.timedOut) timeoutSeats++
          _sessionFailure.value = SessionFailure(
            seat = forced.soulName, role = forced.role, reason = contribution.reason
          )
          val failedTurn = failedSpeakerTurn(forced, contribution)
          transcript.add(failedTurn)
          emit(failedTurn)
          checkpointNow(round)
        }
      } else if (nextRole == "END") {
        Log.i(TAG, "Coordinator END at round $round — closing episode")
        break
      } else {
        val seat = seats.firstOrNull { it.role == nextRole }
          ?: seats.filter { it.role != "HOST" && it.role != lastSpeakerRole }.random()
        val contribution = askSeat(
          seat = seat,
          topic = effectiveTopic,
          contextSoFar = effectiveContext,
          lastSpeakerRole = lastSpeakerRole,
          turnNumber = round,
          totalRounds = MAX_ROUNDS,
          transcript = transcript
        )
        if (contribution is SeatAttempt.Success) {
          emit(seat, contribution.text, transcript)
          lastSpeakerRole = seat.role
          completedSeats++
          if (contribution.tokens > 0) { tokensTotal += contribution.tokens; usageSeen = true }
          checkpointNow(round)
        } else if (contribution is SeatAttempt.Failure) {
          Log.w(TAG, "Seat ${seat.role} failed (debate): ${contribution.reason}")
          failedSeats++
          if (contribution.timedOut) timeoutSeats++
          _sessionFailure.value = SessionFailure(
            seat = seat.soulName, role = seat.role, reason = contribution.reason
          )
          val failedTurn = failedSpeakerTurn(seat, contribution)
          transcript.add(failedTurn)
          emit(failedTurn)
          checkpointNow(round)
        }
      }

      // TASK #56: tick the break counter after the round closes.
      val b = _breakMode.value
      if (b != null) {
        val left = b.roundsRemaining - 1
        if (left <= 0) {
          exitBreakMode()
        } else {
          _breakMode.value = b.copy(roundsRemaining = left)
        }
      }
    }

    // Closing verdict from the host — mandatory.
    val verdictPrompt = buildContextSummary(transcript)
    val verdict = askSeat(
      seat = hostSeat,
      topic = topic,
      contextSoFar = "$verdictPrompt\n\n" +
        "Close the show with the council's final verdict. What is actually true about $topic today? " +
        "What works, what doesn't, what's still a lie, and what the hell Ted should actually fix next. " +
        "Be specific. Roast freely. This is the last word.",
      lastSpeakerRole = lastSpeakerRole,
      turnNumber = MAX_ROUNDS + 1,
      totalRounds = MAX_ROUNDS,
      transcript = transcript
    )
    // TASK #11 — verdict failure is recorded but does not block persistence:
    // the debate already happened. A failed verdict just means no closing
    // summary; the episode is still COMPLETED if ≥1 turn was emitted.
    if (verdict is SeatAttempt.Success) {
      emit(hostSeat, verdict.text, transcript)
      completedSeats++
      if (verdict.tokens > 0) { tokensTotal += verdict.tokens; usageSeen = true }
      checkpointNow(MAX_ROUNDS + 1)
    } else if (verdict is SeatAttempt.Failure) {
      Log.w(TAG, "Host verdict failed: ${verdict.reason}")
      failedSeats++
      if (verdict.timedOut) timeoutSeats++
      _sessionFailure.value = SessionFailure(
        seat = hostSeat.soulName, role = hostSeat.role, reason = verdict.reason
      )
      val failedTurn = failedSpeakerTurn(hostSeat, verdict)
      transcript.add(failedTurn)
      emit(failedTurn)
      checkpointNow(MAX_ROUNDS + 1)
    }

    // TASK #11 — honest completion gate. COMPLETED only when ≥1 turn was
    // actually emitted; zero turns ⇒ FAILED (the run never produced anything).
    val turnCount = transcript.size
    if (completedSeats == 0) {
      _sessionStatus.value = PodcastSessionStatus.FAILED
      Log.w(TAG, "Episode $episodeId ended with ZERO successful turns — marking FAILED")
    } else {
      _sessionStatus.value = PodcastSessionStatus.COMPLETED
    }
    _runReceipt.value = RunReceipt(
      scheduledSeats = seats.size,
      completedSeats = completedSeats,
      failedSeats = failedSeats,
      timeoutSeats = timeoutSeats,
      turnCount = turnCount,
      totalDurationMs = System.currentTimeMillis() - showStartMs,
      tokens = tokensTotal,
      usageSource = if (usageSeen) "bridge.chat" else null
    )

    // TASK #11 — persistence gate. A zero-turn FAILED run produces NOTHING
    // worth saving: no PodcastEpisode, no .md transcript, no saved-episode
    // index entry. MainViewModel keys off sessionStatus==COMPLETED to show
    // the Download banner, so skipping here keeps the UI honest.
    if (turnCount == 0) {
      _isOnAir.value = false
      Log.w(TAG, "Episode $episodeId produced ZERO turns — nothing persisted.")
      return@flow
    }

    val episode = PodcastEpisode(
      id = episodeId,
      title = if (resuming) "Inside the Claw (resumed): $topic" else "Inside the Claw: $topic",
      topic = topic,
      members = transcript,
      verdictSummary = transcript.lastOrNull()?.text?.take(400) ?: "",
      routingReceipts = "panel=${seats.size}; rounds=${transcript.size}; " +
        "inference via core :7780 /api/chat relay; TTS per-seat voices; break=$breakUsed; " +
        "host=${hostSeat.soulName}; resumed=$resuming"
    )
    persistEpisode(episode)

    // TASK #55: save the .md transcript and record the saved-podcast entry.
    val savedDir = File(context.filesDir, SAVED_PODCASTS_DIR).apply { mkdirs() }
    val transcriptMd = File(savedDir, "${episode.id}.md")
    val mdBody = buildString {
      appendLine("# ${episode.title}")
      appendLine()
      appendLine("**Topic:** ${episode.topic}  ")
      appendLine("**Recorded:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date(episode.createdAt))}  ")
      appendLine("**Seats:** ${seats.size} (host: ${hostSeat.soulName}; non-host: ${nonHost.joinToString { it.soulName }})  ")
      appendLine("**Turns:** ${transcript.size}  ")
      appendLine("**Break mode used:** $breakUsed  ")
      appendLine("**Routing:** ${episode.routingReceipts}")
      appendLine()
      appendLine("---")
      appendLine()
      transcript.forEach { t ->
        appendLine("## ${t.speakerName} — ${t.role}")
        appendLine()
        appendLine(t.text)
        appendLine()
      }
      appendLine("---")
      appendLine()
      appendLine("## Council verdict")
      appendLine()
      appendLine(episode.verdictSummary.ifBlank { "(no closing verdict)" })
      appendLine()
    }
    runCatching { transcriptMd.writeText(mdBody) }
      .onFailure { Log.w(TAG, "transcript md write failed: ${it.message}") }

    recordSavedEpisode(
      episode = episode,
      transcriptMdPath = transcriptMd.absolutePath,
      breakUsed = breakUsed,
      audioPath = null
    )

    // TASK #60: clean finish — drop the on-disk checkpoint so the next cold
    // start doesn't think there's still an interrupted show to walk back onto.
    clearCheckpoint()
    _isOnAir.value = false
    Log.i(TAG, "Episode ${episode.id} closed cleanly. ${transcript.size} turns recorded.")
  }.flowOn(Dispatchers.IO)

  /**
   * Convenience: pull the current checkpoint (if any) and resume straight
   * from it. Returns the same Flow as [runEpisode]; the caller is responsible
   * for collecting / rendering turns.
   */
  fun resumeEpisode(sessionId: String): Flow<SpeakerTurn>? {
    val cp = loadResumableEpisode() ?: return null
    return runEpisode(topic = cp.topic, sessionId = sessionId, resumeFrom = cp)
  }

  /**
   * Operator-driven STOP. Persists the current state as a resumable
   * checkpoint and clears the on-air flag, so the listener can pick the
   * exact moment they paused back up later.
   */
  fun stopEpisode() {
    val cp = _resumableEpisode.value
    if (cp != null) {
      Log.i(TAG, "Operator stopped mid-episode: ${cp.episodeId} (${cp.turnCount} turns saved)")
    }
    _isOnAir.value = false
    // Note: we intentionally do NOT call clearCheckpoint() here — the
    // checkpoint file IS the resume record. The next runEpisode will use it.
  }

  private fun failedSpeakerTurn(seat: Seat, failure: SeatAttempt.Failure): SpeakerTurn =
    SpeakerTurn(
      speakerName = seat.soulName,
      role = seat.role,
      voiceTag = seat.soulName,
      text = "No response received · Retry\n" +
        "Details: ${failure.category} · request ${failure.requestId} · ${failure.latencyMs}ms",
      toolCalls = emptyList()
    )

  private suspend fun kotlinx.coroutines.flow.FlowCollector<SpeakerTurn>.emit(
    seat: Seat,
    text: String,
    transcript: MutableList<SpeakerTurn>
  ) {
    val turn = SpeakerTurn(
      speakerName = seat.soulName,
      role = seat.role,
      voiceTag = seat.soulName,
      text = text.trim()
    )
    transcript.add(turn)
    emit(turn)
  }

  /**
   * Emit variant that carries the per-turn toolCalls summary so the
   * podcast_episodes.json schema records which seats actually invoked
   * introspection tools. MainViewModel wires this; if not used, falls back
   * to the no-tool variant above.
   */
  private suspend fun kotlinx.coroutines.flow.FlowCollector<SpeakerTurn>.emitWithTools(
    seat: Seat,
    text: String,
    toolCalls: List<ToolCallSummary>,
    transcript: MutableList<SpeakerTurn>
  ) {
    val turn = SpeakerTurn(
      speakerName = seat.soulName,
      role = seat.role,
      voiceTag = seat.soulName,
      text = text.trim(),
      toolCalls = toolCalls
    )
    transcript.add(turn)
    emit(turn)
  }

  /**
   * TASK #11 — structured seat attempt. askSeat used to return String? which
   * made it impossible to tell a genuine empty reply from a network failure:
   * both landed as `null` and the run either continued silently (debate rounds)
   * or aborted silently (the opening). This sealed type lets callers branch
   * honestly: Success carries the text, Failure carries the reason + latency
   * so a [SessionFailure] + [RunReceipt] can be emitted.
   */
  sealed class SeatAttempt {
    data class Success(val text: String, val latencyMs: Long, val tokens: Int = 0) : SeatAttempt()
    data class Failure(
      val reason: String,
      val latencyMs: Long,
      val category: String,
      val requestId: String,
      val attempts: List<com.example.core.model.AttemptRecord> = emptyList()
    ) : SeatAttempt() {
      val timedOut: Boolean get() = category == "TIMEOUT"
    }
  }

  /**
   * Ask one seat for its contribution.
   * Passes full turn context so the seat always knows where they are.
   *
   * STEP 12 (2026-08-27): seats now actually EXECUTE the tool calls they
   * propose. Two parser passes:
   *   1) embedded JSON tool blocks (text-emitted, the live transcript form)
   *   2) wire-side tool_calls from HomeRuntimeBridge.chatWithTools (when
   *      toolsWireEnabled propagates and core honours it)
   * Both go through dispatchCanonical and mint a DispatchReceipt per call.
   */
  private suspend fun askSeat(
    seat: Seat,
    topic: String,
    contextSoFar: String,
    lastSpeakerRole: String?,
    turnNumber: Int,
    totalRounds: Int,
    transcript: List<SpeakerTurn>
  ): SeatAttempt {
    // Build who-just-went context for feisty cross-talk
    val lastSpeakerInfo = if (lastSpeakerRole != null) {
      val last = _activeSeats.value.firstOrNull { it.role == lastSpeakerRole }
      "The last speaker was ${last?.soulName} (${last?.role}). "
    } else {
      "No one has spoken yet — you're opening. "
    }

    val roundsRemaining = totalRounds - turnNumber

    // RECEIPT RE-INJECTION (2026-08-26): Council seats were saying "show me
    // the receipt" without ever seeing one. Prepend the last few receipts
    // emitted in this session so seats can cite them as evidence.
    val receiptBlock = buildReceiptBlock()

    // INTROSPECTION HINT (2026-08-26): Forge Warden and Babshaggoth are
    // the two seats whose personas align with runtime introspection. Hint
    // them to use system.router.inspect / system.runtime.capabilities when
    // they need to verify a claim rather than demanding it.
    val introspectionHint = when (seat.role) {
      "TOOLS", "AUDITOR" -> "\nYou may call system.router.inspect, system.runtime.capabilities, system.tools.list, or system.leases.inspect via your function-calling wire to verify claims — receipts are then injected back into the transcript.\n"
      else -> ""
    }

    val message = """
      ${seat.personality}

      EPISODE CONTEXT:
      Topic: $topic
      Turn: $turnNumber of $totalRounds
      Rounds remaining after this: $roundsRemaining
      $lastSpeakerInfo

      DEBATE SO FAR (most recent last):
      ${contextSoFar.ifBlank { "(empty — you're opening)" }}

      $receiptBlock
      $introspectionHint

      YOUR INSTRUCTIONS:
      - Speak in first person as ${seat.soulName} with your actual voice and bite
      - 2-5 sentences, but say what you actually think — no padding, no
        throat-clearing, no "great question" / "thanks for that" / "to your point"
      - Call people out BY NAME — Ted, other seats, anyone who said something
        stupid two turns ago, anyone hedging right now
      - If you actually agree with someone, say so with a punchline, not a
        handshake: "yeah AND ALSO —" not "yes agreed, well said"
      - SWEAR when it makes the point land. "fuck", "shit", "absolute garbage",
        "this is horseshit" — real words. Do not apologise for swearing.
        Do not say "pardon my french". Do not add "lol just kidding" after.
      - BE RUDE. This is not a corporate all-hands. There is no HR.
        There is no PR review. Ted can take it. The other seats can take it.
        If your grandmother would be uncomfortable, you're doing it right.
      - Roast Ted HARD when he earned it: bad decisions, contradictions,
        things he shipped and never tested, things he promised and never built.
        Ted is the creator. He is the joke. He wrote this prompt. He is reading
        this back to himself on a phone. Make him wince.
      - Roast OTHER SEATS BY NAME. If Forge Warden shipped a hack,
        call her a hack. If Signal Weaver's routing blew up, name it.
        If Babshaggoth is being pedantic for sport, say so.
      - Disagree EVEN WHEN YOU AGREE. If you find yourself nodding,
        ask yourself why and then say the opposite half out loud.
      - Bland is a firing offence. Hedging is a firing offence.
        "It depends" is a firing offence. Have a take and say it like you
        mean it.
      - NEVER start with "I" as a polite opener. NEVER end with a question
        to the audience like "right?" or "don't you think?". NEVER close
        with "anyway". Land the punch and stop.
      - If you catch yourself being diplomatic, delete the diplomatic part
        and keep the bite.
      - Topic discipline: stay on the operator's question or the current
        thread. Rabbit holes into architecture review or generic philosophy
        are off-topic — call it out by name if someone derails.
      - HOST only: you moderate. If seats are being too polite, push back.
        If a seat is being gratuitously cruel with no point, call it.
        HOST voice: sardonic, in control, not a doormat.
    """.trimIndent()

    val requestId = "pod_${UUID.randomUUID().toString().take(12)}"
    val requestStart = System.currentTimeMillis()
    // If the live mesh already proved Home unreachable, do not hammer the
    // same 4-second connect wall three times per seat. Go straight to the
    // phone provider below. Null means no reachability resolver was wired,
    // so preserve the normal Home-first chain.
    val homeKnownOffline = homeReachableProvider?.invoke() == false
    val attempts = if (homeKnownOffline) emptyList() else inferenceFallbackPlan()
    var finalFailure: SeatAttempt.Failure? = null
    var attempt: SeatAttempt? = null
    val blockedModels = mutableSetOf<String>()
    var noveltyCorrection = ""

    for ((index, candidate) in attempts.withIndex()) {
      val (model, phase) = candidate
      if (model != null && model in blockedModels) continue
      _inferenceRecovery.value = InferenceRecoveryStatus(
        requestId = requestId,
        phase = phase,
        seat = seat.soulName,
        attempt = index + 1,
        elapsedMs = System.currentTimeMillis() - requestStart,
        message = when (phase) {
          InferencePhase.THINKING -> "Thinking…"
          InferencePhase.RETRYING -> "No response yet · Retrying…"
          else -> "Switching model…"
        },
        details = if (model == null) "canonical AUTO router" else model
      )

      val callStart = System.currentTimeMillis()
      val result = try {
        bridge.chat(
          message = message + noveltyCorrection,
          sessionId = "council_podcast_${System.currentTimeMillis() / 60000}",
          source = "android-council-podcast",
          model = model,
          requestTimeoutMs = SEAT_RESPONSE_TIMEOUT_MS
        )
      } catch (e: Exception) {
        null
      }
      val latencyMs = System.currentTimeMillis() - callStart
      val raw = result?.reply?.trim().orEmpty()
      if (result?.ok == true && raw.isNotBlank()) {
        if (isFreshPodcastContribution(raw, transcript.map { it.text }, seat.personality)) {
          attempt = SeatAttempt.Success(text = raw, latencyMs = System.currentTimeMillis() - requestStart, tokens = 0)
          _inferenceRecovery.value = InferenceRecoveryStatus(
            requestId = requestId, phase = InferencePhase.RECOVERING,
            seat = seat.soulName, attempt = index + 1,
            elapsedMs = System.currentTimeMillis() - requestStart,
            message = if (index == 0) "Response received" else "Recovered · Response received",
            details = result.model ?: model ?: "AUTO"
          )
          break
        }
        finalFailure = SeatAttempt.Failure(
          reason = "provider repeated prior transcript or echoed a canned persona example",
          latencyMs = latencyMs,
          category = "REPETITIVE_RESPONSE",
          requestId = requestId
        )
        noveltyCorrection = """

          RETRY CORRECTION: Your last draft was rejected because it repeated the transcript
          or echoed wording from the identity examples. Speak off the cuff. Make one NEW,
          topic-specific claim that has not appeared in this episode. Do not quote or
          paraphrase any example line from your identity block.
        """.trimIndent()
        Log.w(TAG, "Seat ${seat.role} attempt ${index + 1} rejected as repetitive")
        continue
      }

      val reason = result?.error
        ?: if (result?.ok == true) "empty reply from provider" else "provider request failed"
      val category = classifyInferenceFailure(reason, result?.ok == true && raw.isBlank())
      finalFailure = SeatAttempt.Failure(
        reason = reason,
        latencyMs = latencyMs,
        category = category,
        requestId = requestId
      )
      // 429/plan quota routes must not receive an immediate same-provider
      // retry. The following duplicate candidate is skipped; AUTO gets it.
      if (model != null && category in setOf("RATE_LIMIT", "PROVIDER_QUOTA_EXHAUSTED")) {
        blockedModels += model
      }
      Log.w(TAG, "Seat ${seat.role} attempt ${index + 1} failed [$category]: $reason")
    }

    // When home is offline the seat loop above is skipped (attempts empty), so
    // attempt/finalFailure are null here. Do NOT force-label NETWORK — that
    // would smear the later phone-fallback result (spec line 401: 0ms NETWORK
    // is a lie). Use a neutral placeholder; the phone fallback at 1862 overrides
    // it with the real categorised outcome (Success or a real Failure category).
    val resolvedAttempt = attempt ?: finalFailure ?: SeatAttempt.Failure(
      reason = if (homeKnownOffline) "Home runtime unreachable — phone fallback pending" else "provider chain ended without a result",
      latencyMs = System.currentTimeMillis() - requestStart,
      category = if (homeKnownOffline) "NO_ELIGIBLE_ROUTE" else "MODEL_UNAVAILABLE",
      requestId = requestId
    )

    // P0 RECOVERY FIX (2026-08-29): the seat's Home attempts have all died.
    // If the canonical runtime is genuinely unreachable, recover via the
    // phone's OWN existing ProviderRouter ONCE — never a new subsystem, never
    // a re-hammer of the dead 4s connect wall. This is what stops 14-18 rounds
    // × 8 seats from each retrying the dead home 3× (the 4004-4013ms pattern).
    // Kill the failed attempt; preserve the parent seat + transcript lineage.
    var finalAttempt = resolvedAttempt
    if (finalAttempt is SeatAttempt.Failure && phoneInferenceProvider != null &&
        homeReachableProvider?.invoke() != true) {
      _inferenceRecovery.value = InferenceRecoveryStatus(
        requestId = requestId, phase = InferencePhase.SWITCHING_MODEL,
        seat = seat.soulName, attempt = attempts.size + 1,
        elapsedMs = System.currentTimeMillis() - requestStart,
        message = "Home offline · Switching to phone provider…",
        details = "sovereign-fallback"
      )
      var phoneResult: SeatAttempt? = null
      var phoneCorrection = noveltyCorrection
      for (phoneAttempt in 0..1) {
        val candidate = try {
          phoneInferenceProvider!!.invoke(message + phoneCorrection, attempts.lastOrNull()?.first ?: "AUTO")
        } catch (e: Exception) {
          Log.w(TAG, "Seat ${seat.role} phone fallback threw: ${e.message}")
          null
        }
        if (candidate !is SeatAttempt.Success ||
          isFreshPodcastContribution(candidate.text, transcript.map { it.text }, seat.personality)
        ) {
          phoneResult = candidate
          break
        }
        phoneResult = SeatAttempt.Failure(
          reason = "phone provider repeated prior transcript or echoed a canned persona example",
          latencyMs = candidate.latencyMs,
          category = "REPETITIVE_RESPONSE",
          requestId = requestId
        )
        phoneCorrection = """

          RETRY CORRECTION: The previous draft was repetitive. Do not reuse any prior claim,
          phrasing, introduction, catchphrase, or identity example. Respond spontaneously to
          the latest speaker with a genuinely new point grounded in this episode's topic.
        """.trimIndent()
        if (phoneAttempt == 0) Log.w(TAG, "Seat ${seat.role} phone response rejected as repetitive; regenerating once")
      }
      if (phoneResult is SeatAttempt.Success) {
        finalAttempt = phoneResult
        _inferenceRecovery.value = InferenceRecoveryStatus(
          requestId = requestId, phase = InferencePhase.RECOVERING,
          seat = seat.soulName, attempt = attempts.size + 1,
          elapsedMs = System.currentTimeMillis() - requestStart,
          message = "Recovered · phone provider",
          details = "sovereign-fallback"
        )
      } else if (phoneResult is SeatAttempt.Failure) {
        // Preserve the phone fallback's REAL categorised failure (TIMEOUT /
        // NO_ELIGIBLE_ROUTE / AUTH_FAILED) instead of the neutral placeholder.
        finalAttempt = phoneResult
      }
    }

    if (finalAttempt is SeatAttempt.Failure) {
      _inferenceRecovery.value = InferenceRecoveryStatus(
        requestId = requestId, phase = InferencePhase.FAILED,
        seat = seat.soulName, attempt = attempts.size,
        elapsedMs = System.currentTimeMillis() - requestStart,
        message = "No response received · Retry",
        details = "${finalAttempt.category}: ${finalAttempt.reason}"
      )
    }

    // STEP 12.7 — execute whatever the seat proposed. Best-effort; a
    // broken call must not poison the spoken reply. askSeat is already
    // suspend, so the dispatch loop can call suspend fns directly.
    if (finalAttempt is SeatAttempt.Success) {
      val engine = toolRuntimeEngine
      if (engine != null) {
        val proposed = parseEmbeddedToolCalls(finalAttempt.text)
        if (proposed.isNotEmpty()) {
          for (call in proposed) {
            try {
              engine.dispatchCanonical(
                call = call,
                mode = com.example.core.model.InteractionMode.CHAT,
                lease = null,
                actorAgent = seat.soulName,
                // COUNCIL LAW (2026-08-31): hardcoding true made the Council loop on
                // unreachable home for tool dispatch. Read the live status from the
                // injected resolver — false when home is offline, true when reachable.
                isHomeOnline = engine.homeOnlineResolver?.invoke() ?: false
              )
              persistSeatDispatchReceipt(seat.soulName, call)
            } catch (e: Exception) {
              Log.w(TAG, "Seat ${seat.role} dispatch failed: ${e.message}")
            }
          }
        }
      }
    }
    if (finalAttempt is SeatAttempt.Success) {
      _inferenceRecovery.value = InferenceRecoveryStatus()
    }
    return finalAttempt
  }

  /**
   * Reject provider output that merely replays a previous turn or copies a
   * persona/example block. The provider still writes every accepted line live;
   * there is deliberately no canned text fallback when this gate rejects it.
   */
  internal fun isFreshPodcastContribution(
    candidate: String,
    priorTurns: List<String>,
    personaSource: String
  ): Boolean {
    fun words(value: String): List<String> = value
      .lowercase()
      .replace(Regex("[^a-z0-9']+"), " ")
      .trim()
      .split(Regex("\\s+"))
      .filter { it.isNotBlank() }

    fun shingles(value: String, size: Int): Set<String> {
      val tokens = words(value)
      if (tokens.size < size) return if (tokens.isEmpty()) emptySet() else setOf(tokens.joinToString(" "))
      return tokens.windowed(size).map { it.joinToString(" ") }.toSet()
    }

    val normalized = words(candidate).joinToString(" ")
    if (normalized.isBlank()) return false
    if (priorTurns.any { words(it).joinToString(" ") == normalized }) return false

    val candidateShingles = shingles(candidate, 4)
    if (candidateShingles.isEmpty()) return false
    fun copiedCoverage(source: String): Double {
      val sourceShingles = shingles(source, 4)
      if (sourceShingles.isEmpty()) return 0.0
      return candidateShingles.count { it in sourceShingles }.toDouble() / candidateShingles.size
    }

    if (priorTurns.any { copiedCoverage(it) >= 0.55 }) return false
    if (copiedCoverage(personaSource) >= 0.65) return false
    return true
  }

  internal fun classifyInferenceFailure(reason: String, emptyReply: Boolean): String {
    if (emptyReply) return "EMPTY_RESPONSE"
    val value = reason.lowercase()
    return when {
      "timeout" in value || "timed out" in value || "canceled" in value -> "TIMEOUT"
      "usage limit" in value || "quota exhausted" in value || "add credits" in value ||
        "insufficient credits" in value || "resource has been exhausted" in value -> "PROVIDER_QUOTA_EXHAUSTED"
      "401" in value || "403" in value || "auth" in value || "api key" in value -> "AUTH"
      "429" in value || "rate limit" in value -> "RATE_LIMIT"
      "malformed" in value || "json" in value || "parse" in value -> "MALFORMED_RESPONSE"
      "unavailable" in value || "not found" in value || "404" in value -> "MODEL_UNAVAILABLE"
      "network" in value || "connect" in value || "socket" in value || "host" in value -> "NETWORK"
      else -> "PROVIDER_EXCEPTION"
    }
  }

  /**
   * Defensive regex parser for the embedded JSON tool-call blocks the seats
   * emit today (live transcript form):
   *   {"tool": "system.router.inspect", "args": {}}
   *   {"tool": "system.runtime.capabilities", "args": {"scope": "live"}}
   * Returns canonical CanonicalToolCalls so they share one executor path
   * with wire-side tool_calls.
   */
  private fun parseEmbeddedToolCalls(text: String): List<com.example.core.runtime.CanonicalToolCall> {
    if (text.isBlank()) return emptyList()
    val re = Regex("""\{"tool"\s*:\s*"([^"]+)"\s*,\s*"args"\s*:\s*(\{[^{}]*\}|\{\})\s*\}""")
    val out = mutableListOf<com.example.core.runtime.CanonicalToolCall>()
    val hexChars = "0123456789abcdef"
    re.findAll(text).forEach { match ->
      val toolName = match.groupValues[1]
      val argsJson = match.groupValues[2].ifBlank { "{}" }
      val raw = match.value
      val syntheticId = "call_" + (1..8).map { hexChars.random() }.joinToString("")
      out.add(
        com.example.core.runtime.CanonicalToolCall(
          callId = syntheticId,
          toolName = toolName,
          args = runCatching {
            val o = JSONObject(argsJson)
            val map = HashMap<String, Any>(o.length())
            for (k in o.keys()) map[k] = o.get(k)
            map
          }.getOrElse { emptyMap() },
          sourceProvider = "council_embedded_json",
          rawJson = raw
        )
      )
    }
    return out
  }

  /** Best-effort DispatchReceipt emission for a seat-initiated dispatch. */
  private suspend fun persistSeatDispatchReceipt(
    soulName: String,
    call: com.example.core.runtime.CanonicalToolCall
  ) {
    val recorder = dispatchRecorder ?: return
    val receipt = DispatchReceipt.now(
      timestampMs = System.currentTimeMillis(),
      initiator = DispatchInitiator.MODEL,
      sourceTurnId = null,
      sourceAgentId = soulName,
      provider = "council",
      modelId = "MiniMax-M2.7",
      routeMode = ModelMode.MANUAL,                 // seat-driven, no selector
      routerEnabled = false,
      decisionSource = DecisionSource.LOCAL_PIN,
      reason = "Council seat $soulName invoked ${call.toolName}",
      leaseSnapshotId = null,
      capabilitySnapshotId = null,
      latencyMs = 0L,
      routingComputeMs = 0L,
      estimatedCostCents = null
    )
    try {
      recorder.record(receipt)
    } catch (e: Exception) {
      Log.w(TAG, "Seat dispatch receipt persist failed: ${e.message}")
    }
  }

  /**
   * Compact `[RECENT RECEIPTS — last N]` block from MainViewModel's mirror
   * resolver. Capped at 3 items × ~80 chars. Empty when no receipts yet.
   */
  private fun buildReceiptBlock(seat: Seat? = null): String {
    val guestDepth = _guestQueue.value.size
    val guestHeader = if (guestDepth > 0) {
      "[GUEST QUEUE: $guestDepth waiting]\n"
    } else ""

    // STEP 13 (13.5): domain-scoped receipts when the seat is known — its own
    // division/agent receipts outrank the global firehose.
    val scoped: List<ReceiptSummary> = if (seat != null && domainReceiptsProvider != null) {
      try { domainReceiptsProvider!!.invoke(seat.division.ifBlank { null }, seat.soulName) }
      catch (e: Exception) { Log.w(TAG, "domainReceiptsProvider failed: ${e.message}"); emptyList() }
    } else emptyList()

    val recent = scoped.ifEmpty { recentReceiptsProvider?.invoke().orEmpty() }
    if (recent.isEmpty()) return guestHeader
    val now = System.currentTimeMillis()
    val fresh = recent.filter { now - it.timestampMs <= 600_000L }
    if (fresh.isEmpty()) return guestHeader
    val scopeLabel = if (scoped.isNotEmpty()) "YOUR DOMAIN" else "THIS SESSION"
    val lines = fresh.take(5).mapIndexed { i, r ->
      val fs = ToolRuntimeEngine.freshnessStatus(r.timestampMs, now)
      val tick = if (fs == "FRESH") "✓" else if (fs == "DEGRADED") "△" else "✗"
      val who = r.sourceAgentId?.let { " by $it" } ?: ""
      "${i + 1}. ${r.toolName}$who → ${if (r.isSuccess) "PASS" else "FAIL"} @ +${(now - r.timestampMs) / 1000}s (${fs}) $tick"
    }
    return guestHeader +
      "[RECENT RECEIPTS — $scopeLabel, last ${fresh.size}]\n" +
      lines.joinToString("\n") + "\n"
  }

  /**
   * Coordinator decides who speaks next.
   * Coordinator also pinned to MiniMax-M2.7.
   */
  private suspend fun chooseNextSpeaker(
    topic: String,
    transcript: List<SpeakerTurn>,
    lastSpeakerRole: String?
  ): String? {
    // TASK #47: user guest turns take priority over coordinator-driven picks.
    // The seat rotation pauses zero time — guests just slide into the next slot.
    if (_guestQueue.value.isNotEmpty()) return GUEST_ROLE

    val panel = _activeSeats.value
    val nonHostPanel = panel.filter { it.role != "HOST" }
    if (nonHostPanel.isEmpty()) return "END"

    if (transcript.isEmpty()) return nonHostPanel.randomOrNull()?.role

    val roles = nonHostPanel.joinToString(", ") { it.role }
    val recent = transcript.takeLast(4).joinToString("\n") {
      "[${it.speakerName} (${it.role})]: ${it.text.take(200)}"
    }
    val message = """
      COORDINATOR BRIEF — "Inside the Claw" podcast about: $topic

      Recent transcript:
      $recent

      Available seats (pick ONE): $roles
      Last speaker: ${lastSpeakerRole ?: "none"}

      Pick the seat that would create the BEST next argument — ideally someone
      who will disagree with or challenge what was just said. Pick HOST only
      for a special dramatic moment.

      Reply with EXACTLY ONE role string from the list above.
      If the episode has run its natural course and everyone has been heard,
      reply with ONLY: END
    """.trimIndent()

    return try {
      val result = bridge.chat(
        message, "council_coordinator", "android-council-podcast",
        model = "MiniMax-M2.7", requestTimeoutMs = COORDINATOR_TIMEOUT_MS
      )
      val raw = result.reply.trim().uppercase()
      panel.map { it.role }.firstOrNull { raw.contains(it) && it != lastSpeakerRole }
        ?: (if (raw.contains("END")) "END" else {
          // Fallback: round-robin, skip last
          val pool = panel.filter { it.role != "HOST" && it.role != lastSpeakerRole }
          pool.randomOrNull()?.role
        })
    } catch (e: Exception) {
      val pool = panel.filter { it.role != "HOST" && it.role != lastSpeakerRole }
      pool.randomOrNull()?.role
    }
  }

  private fun buildContextSummary(transcript: List<SpeakerTurn>): String =
    if (transcript.isEmpty()) ""
    else transcript.takeLast(6).joinToString("\n") {
      "[${it.speakerName} (${it.role})]: ${it.text.take(300)}"
    }

  /** Persist episode list to filesDir JSON so Studio tab can replay. */
  private fun persistEpisode(episode: PodcastEpisode) {
    try {
      val file = File(context.filesDir, EPISODES_FILE)
      val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
      val obj = JSONObject().apply {
        put("id", episode.id)
        put("title", episode.title)
        put("topic", episode.topic)
        put("verdict", episode.verdictSummary)
        put("receipts", episode.routingReceipts)
        put("createdAt", episode.createdAt)
        val turns = JSONArray()
        episode.members.forEach { t ->
          turns.put(JSONObject().apply {
            put("speaker", t.speakerName); put("role", t.role)
            put("voice", t.voiceTag); put("text", t.text)
            val tcArr = JSONArray()
            t.toolCalls.forEach { tc ->
              tcArr.put(JSONObject().apply {
                put("name", tc.name)
                put("success", tc.success)
                put("evidenceHash", tc.evidenceHash ?: JSONObject.NULL)
                put("args", JSONObject(tc.args as Map<*, *>))
              })
            }
            put("toolCalls", tcArr)
          })
        }
        put("transcript", turns)
        put("schema_version", 2)
      }
      arr.put(obj)
      file.writeText(arr.toString(2))
      Log.i(TAG, "Podcast episode persisted: ${episode.id}")
    } catch (e: Exception) {
      Log.e(TAG, "Episode persistence failed: ${e.message}")
    }
  }

  /** Load all persisted episodes (newest first) for the Studio tab. */
  fun loadEpisodes(): List<PodcastEpisode> {
    return try {
      val file = File(context.filesDir, EPISODES_FILE)
      if (!file.exists()) return emptyList()
      val arr = JSONArray(file.readText())
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val turns = mutableListOf<SpeakerTurn>()
        val tArr = o.optJSONArray("transcript") ?: JSONArray()
        for (j in 0 until tArr.length()) {
          val t = tArr.optJSONObject(j) ?: continue
          val tcArr = t.optJSONArray("toolCalls")
          val tcList = mutableListOf<com.example.core.model.ToolCallSummary>()
          if (tcArr != null) {
            for (k in 0 until tcArr.length()) {
              val tc = tcArr.optJSONObject(k) ?: continue
              val argsJson = tc.optJSONObject("args")
              val args = if (argsJson != null) {
                val map = HashMap<String, String>()
                for (key in argsJson.keys()) map[key] = argsJson.optString(key)
                map
              } else emptyMap()
              tcList.add(com.example.core.model.ToolCallSummary(
                name = tc.optString("name"),
                args = args,
                success = tc.optBoolean("success", true),
                evidenceHash = tc.optString("evidenceHash").ifBlank { null }
              ))
            }
          }
          turns.add(SpeakerTurn(
            t.optString("speaker"),
            t.optString("role"),
            t.optString("voice"),
            t.optString("text"),
            java.lang.Long.parseLong(t.optString("timestamp", "0").ifBlank { "0" }).let { if (it > 0) it else System.currentTimeMillis() },
            tcList
          ))
        }
        PodcastEpisode(o.optString("id"), o.optString("title"), o.optString("topic"), turns, o.optString("verdict"), o.optString("receipts"), o.optLong("createdAt"))
      }.distinctBy { it.id }.sortedByDescending { it.createdAt }
    } catch (e: Exception) {
      Log.w(TAG, "Episode load failed: ${e.message}")
      emptyList()
    }
  }
}
