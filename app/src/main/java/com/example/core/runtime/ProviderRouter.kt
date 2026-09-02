package com.example.core.runtime

import android.util.Log
import com.example.core.model.CatalogueModel
import com.example.core.model.DecisionSource
import com.example.core.model.DispatchInitiator
import com.example.core.model.DispatchReceipt
import com.example.core.model.MiniMaxCapabilityStatus
import com.example.core.model.ModelMode
import com.example.core.model.ProviderSource
import com.example.core.model.ProviderType
import com.example.core.model.ReasoningEffort
import com.example.core.model.RoutingProfile
import com.example.core.model.RoutingReceipt
import com.example.core.model.RoutingState
import com.example.core.model.SpendMode
import com.example.core.model.SpendPolicy
import com.example.core.network.HomeRuntimeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ProviderExecutionResult(
  val content: String,
  val providerModel: String,
  val tokenCount: Int,
  val latencyMs: Long,
  val isFallback: Boolean = false,
  val errorMessage: String? = null,
  val statusCode: Int? = null,           // HTTP status code if available (e.g. 429, 502, 404)
  val routingReceipt: RoutingReceipt? = null,
  val toolCalls: List<CanonicalToolCall> = emptyList(),
  val reasoning: String? = null          // model thinking — rendered in the status box, never in chat text
)

data class MiniMaxMediaResult(
  val taskId: String,
  val status: String,
  val mediaUrl: String? = null,
  val rawResponse: String,
  val latencyMs: Long,
  val errorMessage: String? = null
)

data class ModelBenchmarkReport(
  val modelId: String,
  val success: Boolean,
  val latencyMs: Long,
  val tokensPerSecond: Float,
  val outputSample: String,
  val error: String? = null
)

data class SpendVerdict(
  val allowed: Boolean,
  val reason: String
)

data class SpendSnapshot(
  val spentCentsToday: Double,
  val capCents: Int,
  val mode: SpendMode
)

class ProviderRouter(
  private val vault: KeyStoreVault,
  private val localHost: LocalModelHost,
  private val appContext: android.content.Context,
  private val dispatchLedgerDao: com.example.core.database.DispatchLedgerDao
) {
  private val _dispatchRecorder: DispatchRecorder = DispatchRecorder(dispatchLedgerDao)

  companion object {
    private const val TAG = "ProviderRouter"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    // MODEL PIN STICKINESS LAW: manual provider/model pick survives process death.
    private const val PREFS = "purpclaw_routing"
    private const val KEY_PROVIDER = "pinned_provider"
    private const val KEY_MODEL = "pinned_model"
    // Cost incident 2026-09-01: AUTO is fail-closed away from OpenRouter
    // until its account-side charged route is independently reconciled.
    // NO HARDCODED AUTO EXCLUSION (operator 2026-09-02): every configured gateway
    // joins the AUTO pool. No provider is held out at the source-code level.
    // If a lane misbehaves, quarantine it through the live health cache at
    // runtime — the catalogue is the source of truth, not a build-time list.
    private val UUID_MODEL_ID = Regex(
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )

    internal fun callableNimModelId(rawId: String, advertisedName: String): String =
      if (UUID_MODEL_ID.matches(rawId) && advertisedName.isNotBlank()) advertisedName else rawId

    /** Routes which are discoverable but cannot be invoked by this Android client. */
    internal fun isCallableAutoModelId(id: String): Boolean =
      id.isNotBlank() &&
        !UUID_MODEL_ID.matches(id) &&
        !id.contains("thinkingmachines/inkling", ignoreCase = true)

    /**
     * Chat-only filter for the OpenAI-compatible free gateways' /models
     * listings (Groq/Cerebras/Google AI/Cloudflare). Same intent as
     * isNimChatEndpoint but scoped to these lanes' model families
     * (e.g. cloudflare "@cf/black-forest-labs/flux-…", "@cf/baai/bge-…").
     */
    internal fun isGatewayChatModelId(id: String): Boolean {
      val lower = id.lowercase()
      val tokens = lower.split('-', '_', '/').filter { it.isNotBlank() }.toSet()
      val nonChat = setOf(
        "embed", "embedding", "rerank", "retriev", "guard", "whisper", "clip",
        "flux", "sdxl", "stable", "diffusion", "tts", "speech", "audio",
        "image", "img", "video", "translate", "moderation", "coder",
        "completion", "bge", "m2m100"
      )
      return tokens.none { it in nonChat } && !lower.contains("embedding")
    }

    /**
     * PERSISTED ROUTE HEALTH (2026-09-02 live fix). NIM /models is the GLOBAL
     * catalog, not this key's entitlement — partner slugs appear live but 404
     * "Function not found for account" forever. Entitlement truth is learned
     * from the wire and PERSISTED across boots:
     *   dead   = provider said not-found/no-such-model for THIS key
     *   served = this id actually produced output for THIS key (floats first)
     * A successful serve revives a dead id. No hardcoded lists anywhere.
     * Lives in the companion so buildPhoneCandidates can rank with it.
     */
    private object RouteHealth {
      private lateinit var prefs: android.content.SharedPreferences
      val dead = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
      val served = java.util.concurrent.ConcurrentHashMap<String, Long>()
      fun attach(context: android.content.Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("purpclaw_route_health", android.content.Context.MODE_PRIVATE)
        prefs.getStringSet("perm_dead", emptySet()).orEmpty().forEach { dead[it] = true }
        prefs.getStringSet("served_rank", emptySet()).orEmpty().forEach { entry ->
          val parts = entry.split('|')
          if (parts.size == 2) served[parts[0]] = parts[1].toLongOrNull() ?: 0L
        }
      }
      @Synchronized
      private fun persist() {
        if (!::prefs.isInitialized) return
        prefs.edit()
          .putStringSet("perm_dead", dead.keys.toSet())
          .putStringSet("served_rank", served.entries.map { "${it.key}|${it.value}" }.toSet())
          .apply()
      }
      fun markDead(id: String) {
        if (dead.put(id, true) == null) persist()
      }
      fun markServed(id: String) {
        dead.remove(id)
        served[id] = System.currentTimeMillis()
        persist()
      }
      fun revive(id: String) {
        if (dead.remove(id) != null) persist()
      }
      fun isDead(id: String) = dead.containsKey(id)
      fun servedAt(id: String): Long = served[id] ?: 0L
    }

    /**
     * Honest chat-capable classifier. The old version had a hardcoded
     * 50-stem blocklist that dropped 36 of 82 live NIM models (including
     * valid chat targets like llama-3.2-*-vision-instruct, muse-glimmer-30b,
     * nemotron-voicechat, kimi-k2.6, mistral-nemotron). That blocklist
     * was the "still hardcoded" the operator objected to.
     *
     * New rule: a model is chat UNLESS its id provably identifies a
     * different modality (embedding, safety classifier, OCR, TTS, ASR,
     * code-only, vision-only detector, parser, reward model). The live
     * API returns no tier/pricing metadata, so we cannot distinguish
     * NVIDIA's editorial "Free Endpoint" curation from the raw upstream
     * response — the count of 82 vs the website's 39 reflects NVIDIA's
     * hand-curation, not a hidden field we can read.
     */
    internal fun isNimChatEndpoint(id: String): Boolean {
      val lower = id.lowercase()
      // Substring matches (String.contains, not regex — backslash-b is literal).
      // Each pattern is something that provably identifies a non-chat modality.
      val nonChatTokens = listOf(
        "embed",          // embed-qa-4, nv-embedqa, llama-nemotron-embed-vl, nemotron-3-embed
        "rerank",         // retrievers/rerankers
        "retriev",        // nemoretriever
        "guard",          // nemoguard, safety-guard
        "safety",         // content-safety, llama-3.1-nemotron-safety-guard
        "moderation",
        "ocr",
        "riva",           // riva-translate-*
        "translate",
        "tts",            // magpie-tts-zeroshot
        "asr",            // speech recognition
        "whisper",
        "nvclip",
        "deplot",
        "fuyu",
        "kosmos",
        "ising",          // quantum calibration models
        "streampetr",
        "sparsedrive",
        "bevformer",
        "synthetic-video-detector",
        "parse",          // nemotron-parse
        "starcoder",
        "bigcode",
        "reward",         // nemotron-4-340b-reward
        "neva-22b",       // legacy vision-only
        "vila",           // vision-language action model
        "denois",
        "voicechat"       // voicechat ≠ chat (it's audio chat, not general chat)
      )
      return nonChatTokens.none { lower.contains(it) }
    }

    /** Pure bounded phone-route selection used by AUTO and unit tests. */
    internal fun buildPhoneCandidates(
      gateways: List<CatalogueModel>,
      requestedModel: String?,
      toolsRequired: Boolean,
      openRouterConfigured: Boolean,
      quarantined: (String) -> Boolean,
      estimatedPromptTokens: Int = 0
    ): List<String> {
      fun toolEligible(model: CatalogueModel): Boolean = !toolsRequired ||
        model.isToolCapable ||
        model.id == "openrouter/free" ||
        // These gateways' live /models responses do not publish a reliable
        // tools flag. Runtime invocation with the tools envelope is the
        // truthful probe; unsupported endpoints fail and rotate instead of
        // disappearing as NONE.
        model.sourceProvider in setOf("nim", "nvidia", "groq", "cerebras", "googleai", "cloudflare")

      val live = gateways.filter {
        it.modelClass == "chat" && it.isFree && it.available && it.configured &&
          it.isQualifiedFree && it.pricingPrompt == 0.0 && it.pricingCompletion == 0.0 &&
          isCallableAutoModelId(it.id) && !it.isUserExcludedFromAuto &&
          !quarantined(it.id) && toolEligible(it) &&
          // CONTEXT-FIT LAW (operator 2026-09-02): a model whose advertised
          // context is smaller than the prompt will 413/400 on the first
          // request. Filter them out before rotation so a 9,980-token turn
          // never lands on an 8k-TPM-capable small-context model. 0 means
          // upstream did not advertise a context — keep the candidate;
          // truthful failure is better than silent omission.
          (it.contextLength == 0 || it.contextLength >= estimatedPromptTokens * 13 / 10)
      }
      val requestedRecord = live.firstOrNull { it.id == requestedModel }
      // MULTI-LANE LAW (operator 2026-09-01): AUTO interleaves EVERY gateway
      // provider, not just OpenRouter+NIM. A bounded list filled entirely by
      // one provider is not failover; it is six variations of the same outage.
      // Lane priority keeps the historical openrouter→nim ordering intact for
      // existing TVGs; new lanes join the same round-robin.
      val lanePriority = listOf("openrouter", "nim", "nvidia", "groq", "cerebras", "googleai", "cloudflare")
      val byProvider = live.groupBy { it.sourceProvider }
        .mapValues { (_, models) ->
          models.filter { it.id != "openrouter/free" }
            // SERVED-FIRST LAW: ids this key has actually served float to the
            // top of their lane; never-served ids follow by capability. The
            // first attempt after boot is then the last known-good model,
            // not an alphabetical partner slug that 404s.
            .sortedWith(
              compareByDescending<CatalogueModel> { RouteHealth.servedAt(it.id) }
                .thenByDescending { it.isToolCapable }
                .thenByDescending { it.contextLength }
            )
        }
      val orderedLanes = byProvider.entries
        .sortedWith(compareBy({ lanePriority.indexOf(it.key).let { i -> if (i < 0) lanePriority.size else i } }, { it.key }))
        .map { it.value }
      val maxLane = orderedLanes.maxOfOrNull { it.size } ?: 0
      return buildList {
        // AUTO's generic alias is not a real capability-qualified model. In
        // WORK it must not jump ahead of concrete live models that explicitly
        // advertise tool calling; retain it as the final gateway fallback.
        if (requestedRecord != null && !(toolsRequired && requestedRecord.id == "openrouter/free")) {
          add(requestedRecord.id)
        }
        repeat(maxLane) { index ->
          orderedLanes.forEach { lane -> lane.getOrNull(index)?.let { add(it.id) } }
        }
        if (openRouterConfigured && !quarantined("openrouter/free")) add("openrouter/free")
      }.distinct().take(6)
    }
  }

  // Restored pin survives app kill/relaunch until operator picks AUTO.
  private val prefs = appContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

  init {
    // Load persisted entitlement truth (dead/served) before any routing call.
    RouteHealth.attach(appContext)
  }

  private val httpClient = OkHttpClient.Builder()
    .callTimeout(18, TimeUnit.SECONDS)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(35, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

  private val _routingState = MutableStateFlow(RoutingState())
  val routingState: StateFlow<RoutingState> = _routingState.asStateFlow()

  private val _openRouterCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val openRouterCatalogue: StateFlow<List<CatalogueModel>> = _openRouterCatalogue.asStateFlow()

  // BOOT-REFRESH LAW (operator 2026-08-28): every process boot must refresh
  // the live catalog so a model added today is selectable tomorrow. The
  // timestamp is observable so an operator can audit "did the last boot
  // actually re-fetch?" without re-running probes.
  private val _lastCatalogRefreshAtMs = MutableStateFlow(0L)
  val lastCatalogRefreshAtMs: StateFlow<Long> = _lastCatalogRefreshAtMs.asStateFlow()

  /**
   * Record the boot of a catalogue refresh cycle. Called from MainViewModel
   * startup AFTER every per-provider refresh resolves, so the stamp reflects
   * the moment the boot cycle completed (not the start). Zero = never
   * refreshed in this process.
   */
  fun markBootCatalogRefresh() {
    val now = System.currentTimeMillis()
    _lastCatalogRefreshAtMs.value = now
    Log.i(TAG, "BOOT_REFRESH catalog stamp=$now providers=or,nim,minimax,longcat,kimi,qwen,deepseek,openai,zai")
  }

  private val _miniMaxStatus = MutableStateFlow(MiniMaxCapabilityStatus())
  val miniMaxStatus: StateFlow<MiniMaxCapabilityStatus> = _miniMaxStatus.asStateFlow()

  // NVIDIA NIM — free-tier integrate.api.nvidia.com with live /v1/models pull
  private val _nimCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val nimCatalogue: StateFlow<List<CatalogueModel>> = _nimCatalogue.asStateFlow()

  // HEALTH CACHE (2026-08-29, paste_45 eligibility gate): a model/route that
  // recently returned 4xx/5xx/timeout is quarantined so the AUTO router stops
  // gambling on the same corpse. resolvePhoneModel excludes quarantined ids.
  // Without this, openrouter/free can route to a dead model (e.g. "Stealth"
  // -> HTTP 502 Invalid URL) and the fallback loops on the same broken route.
  private val recentFailureQuarantine = java.util.concurrent.ConcurrentHashMap<String, Long>()
  private val QUARANTINE_MS = 60_000L
  /** Per-model errors that are permanent for that model and don't warrant 60 s quarantine. */
  private val PER_MODEL_PERMANENT_ERRORS = listOf(
    "model_not_found", "model does not exist", "model_terms_required",
    "tool calling", "is not supported with this model"
  )
  fun quarantineModel(id: String) {
    recentFailureQuarantine[id] = System.currentTimeMillis() + QUARANTINE_MS
  }

  // Delegates into the persisted companion store — see RouteHealth above.
  fun markModelDead(id: String) = RouteHealth.markDead(id)
  fun markModelServed(id: String) = RouteHealth.markServed(id)
  fun isModelDead(id: String): Boolean = RouteHealth.isDead(id)
  fun servedRankOf(id: String): Long = RouteHealth.servedAt(id)

  fun isQuarantined(id: String): Boolean {
    if (RouteHealth.isDead(id)) return true
    val until = recentFailureQuarantine[id] ?: return false
    return System.currentTimeMillis() < until
  }
  fun clearQuarantine(id: String) {
    recentFailureQuarantine.remove(id)
    RouteHealth.revive(id)
  }

  /** Carries per-call attribution from the caller (Initiator / source ids). */
  @Volatile
  var pendingRoutingContextProvider: (() -> RoutingDecisionContext?) = { null }

  /**
   * Mint a DispatchReceipt for this execution and (best-effort) persist it
   * via the recorder. Decision-source classification:
   *   MANUAL pin → LOCAL_PIN
   *   fallback path non-empty → FALLBACK
   *   homeResolvedModel != null → CORE_RELAY
   *   else → AUTO_SELECT
   *
   * The recorder call is fire-and-forget; failure to persist must not
   * poison the chat result. Returns the dispatchId for diagnostics.
   */
  private suspend fun persistDispatchReceipt(
    providerLabel: String,
    modelIdUsed: String,
    latencyMs: Long,
    routingReason: String,
    fallbackTrace: List<String>,
    errorCode: String?
  ): String? {
    val ctx = pendingRoutingContextProvider() ?: RoutingDecisionContext.UNKNOWN
    val state = _routingState.value
    val decision = when {
      ctx.decisionSource != DecisionSource.UNKNOWN -> ctx.decisionSource
      state.modelMode == ModelMode.MANUAL -> DecisionSource.LOCAL_PIN
      fallbackTrace.isNotEmpty() -> DecisionSource.FALLBACK
      state.homeResolvedModel != null -> DecisionSource.CORE_RELAY
      else -> DecisionSource.AUTO_SELECT
    }
    val receipt = DispatchReceipt.now(
      timestampMs = System.currentTimeMillis(),
      initiator = ctx.initiator,
      sourceTurnId = ctx.sourceTurnId,
      sourceAgentId = ctx.sourceAgentId,
      provider = providerLabel,
      modelId = modelIdUsed,
      routeMode = state.modelMode,
      routerEnabled = state.useHomeRouting && ctx.routerEnabled,
      decisionSource = decision,
      reason = routingReason,
      leaseSnapshotId = ctx.leaseSnapshotId,
      capabilitySnapshotId = ctx.capabilitySnapshotId,
      latencyMs = latencyMs,
      routingComputeMs = ctx.routingComputeMs,
      estimatedCostCents = ctx.estimatedCostCents,
      errorCode = errorCode
    )
    val recorder = _dispatchRecorder
    return try {
      recorder.record(receipt)
    } catch (e: CancellationException) {
      // Coroutine was cancelled mid-persist — silently skip (fire-and-forget contract)
      receipt.dispatchId
    } catch (e: Exception) {
      Log.w(TAG, "DispatchReceipt persist failed: ${e.message}")
      receipt.dispatchId
    }
  }

  /**
   * REASONING HYGIENE LAW (P0 #9, 2026-09-02): model thinking never leaks into
   * chat content. Extracts DeepSeek-style `reasoning_content` / OpenAI-style
   * `reasoning` fields AND inline <think>…</think> markup into a separate
   * reasoning channel; the content string comes back clean. Mirrors the
   * HomeRuntimeBridge hygiene strip so every lane behaves the same.
   */
  private fun parseChoiceMessage(json: JSONObject): Pair<String, String?> {
    val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
      ?: return "" to null
    val raw = message.optString("content")
    var reasoning = message.optString("reasoning_content").takeIf { it.isNotBlank() }
      ?: message.optString("reasoning").takeIf { it.isNotBlank() }
    if (reasoning.isNullOrBlank()) {
      reasoning = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
        .findAll(raw)
        .mapNotNull { it.groupValues[1].trim().takeIf(String::isNotEmpty) }
        .firstOrNull()
    }
    var cleaned = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE).replace(raw, "")
    cleaned = Regex("^\\s*<think>[\\s\\S]*", RegexOption.IGNORE_CASE).replace(cleaned, "") // unclosed think at start
    return cleaned.trim() to reasoning?.takeIf { it.isNotBlank() }
  }

  /**
   * OpenAI-compatible /v1/models fetcher reused for every DIRECT provider
   * (Kimi, Qwen, DeepSeek, OpenAI, Z.ai). MiniMax has its own bespoke
   * endpoint shape and is handled separately.
   *
   * A direct provider's catalogue is only populated when the vault key is
   * present — otherwise the flow stays empty and the UI shows the "Add API
   * key" tile. Pricing is taken straight from the upstream listing when
   * present; we never mark these as `isFree = true` unless the upstream
   * response explicitly reports $0 for both prompt and completion.
   */
  private suspend fun refreshOpenAiCompatibleCatalogue(
    source: ProviderSource
  ): Int {
    val apiKey = vault.retrieveSecret(source.vaultKey)
    if (apiKey.isNullOrBlank()) {
      Log.i(TAG, "${source.name} catalogue refresh skipped: ${source.vaultKey} missing")
      clearDirectFlow(source)
      return 0
    }
    return try {
      val models = withContext(Dispatchers.IO) {
        val req = Request.Builder()
          .url("${source.baseUrl}/models")
          .addHeader("Authorization", "Bearer $apiKey")
          .get()
        val resp = httpClient.newCall(req.build()).execute()
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          Log.w(TAG, "${source.name} models HTTP ${resp.code}")
          return@withContext null
        }
        val arr = JSONObject(body).optJSONArray("data") ?: return@withContext null
        val list = mutableListOf<CatalogueModel>()
        for (i in 0 until arr.length()) {
          val m = arr.optJSONObject(i) ?: continue
          val id = m.optString("id")
          if (id.isBlank()) continue
          // Free-tier check on direct providers: the model is free ONLY when
          // the upstream payload explicitly lists pricing as $0. A missing
          // pricing block on a direct provider means paid — never assume.
          val pricingJson = m.optJSONObject("pricing")
          val promptCost = pricingJson?.optDouble("prompt", -1.0) ?: -1.0
          val completionCost = pricingJson?.optDouble("completion", -1.0) ?: -1.0
          val isFree = pricingJson != null && promptCost == 0.0 && completionCost == 0.0
          list.add(
            CatalogueModel(
              id = "$source/${id}",
              name = m.optString("display_name", id),
              provider = source.name,
              providerType = ProviderType.DIRECT,
              sourceProvider = source.name.lowercase().split(" ")[0].replace("(", "").replace(")", ""),
              description = "${source.name} · direct BYO key",
              contextLength = m.optInt("context_length", 0),
              isFree = isFree,
              isToolCapable = true,
              isVisionCapable = id.contains("vision", ignoreCase = true) || id.contains("vl", ignoreCase = true),
              isReasoningCapable = id.contains("reason") || id.contains("r1") || id.contains("thinking"),
              modelClass = if (id.contains("embed", ignoreCase = true)) "embedding"
                else if (id.contains("rerank", ignoreCase = true)) "reranker"
                else "chat",
              avgLatencyMs = 0,
              healthStatus = "HEALTHY",
              pricingPrompt = if (promptCost >= 0) promptCost else 0.0,
              pricingCompletion = if (completionCost >= 0) completionCost else 0.0,
              isQualifiedFree = isFree,
              configured = true,
              available = true,
              discoveredAtMs = System.currentTimeMillis(),
              endpointSource = "live_catalog",
              vaultKeyName = source.vaultKey
            )
          )
        }
        // NO SEED AUTHORITY LAW (2026-09-02): a live /models call that answers
        // with an empty listing proves the provider exposes nothing selectable
        // right now. Installing the old hardcoded seed list here advertised
        // model ids nobody verified — the same drift that produced dead NIM
        // endpoints. Catalogue stays EMPTY; UNKNOWN is not a fallback list.
        if (list.isEmpty()) {
          Log.w(TAG, "${source.name} live /models returned EMPTY data — catalogue left empty (seed lists are not authority)")
          clearDirectFlow(source)
          return@withContext emptyList()
        }
        list
      }
      if (models == null) {
        // HTTP failed: the operator must see "refresh failed", not a stale
        // hardcoded list dressed up as a live catalogue.
        Log.w(TAG, "${source.name} catalogue refresh failed — catalogue left empty (seed lists are not authority)")
        clearDirectFlow(source)
        return 0
      }
      writeDirectFlow(source, models)
      models.size
    } catch (e: Exception) {
      Log.w(TAG, "${source.name} catalogue refresh failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"} — catalogue left empty")
      clearDirectFlow(source)
      0
    }
  }

  private fun clearDirectFlow(source: ProviderSource) {
    writeDirectFlow(source, emptyList())
  }

  private fun writeDirectFlow(source: ProviderSource, list: List<CatalogueModel>) {
    val freeFirst = list.sortedWith(
      compareByDescending<CatalogueModel> { it.isFree }
        .thenByDescending { it.isToolCapable }
        .thenByDescending { it.contextLength }
        .thenBy { it.name.lowercase() }
    )
    when (source) {
      ProviderSource.MINIMAX -> _minimaxCatalogue.value = freeFirst
      ProviderSource.LONGCAT -> _longcatCatalogue.value = freeFirst
      ProviderSource.KIMI -> _kimiCatalogue.value = freeFirst
      ProviderSource.QWEN -> _qwenCatalogue.value = freeFirst
      ProviderSource.DEEPSEEK -> _deepseekCatalogue.value = freeFirst
      ProviderSource.OPENAI -> _openaiCatalogue.value = freeFirst
      ProviderSource.ZAI -> _zaiCatalogue.value = freeFirst
      else -> Unit
    }
  }

  // REMOVED (operator 2026-09-02): seedDirectFlow() and seedDirectProvider() —
  // the per-direct-provider hardcoded default_seed list (Kimi moonshot-v1-*,
  // Qwen qwen-plus/turbo/max, Deepseek deepseek-chat/reasoner/coder, OpenAI
  // gpt-4o/4o-mini/4.1, ZAI glm-4.5*, LongCat LongCat-2.0). They were dead
  // code — refreshOpenAiCompatibleCatalogue never called them — and the
  // existence of a hardcoded list in the source violated the NO HARDCODED
  // LISTS law even when unused. The live /models fetch is the sole
  // authority; empty fetch → empty catalogue.

  // DIRECT PROVIDER REFRESH ENTRY POINTS — each is a no-op when the key is
  // absent. Call from MainViewModel after vault writes. No seed lists: the
  // live /models response is the only authority.
  suspend fun refreshKimiCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(ProviderSource.KIMI)
  suspend fun refreshQwenCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(ProviderSource.QWEN)
  suspend fun refreshDeepseekCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(ProviderSource.DEEPSEEK)
  suspend fun refreshOpenaiCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(ProviderSource.OPENAI)
  suspend fun refreshZaiCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(ProviderSource.ZAI)
  // LONGCAT (2026-08-29): Meituan OpenAI-compatible provider, direct BYO key.
  // Live production proof (2026-08-29): both model discovery and chat are
  // served below /openai/v1. The docs' bare /v1/models path returned HTML 404;
  // /openai/v1/models returned the authenticated LongCat-2.0 catalogue.
  suspend fun refreshLongcatCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(ProviderSource.LONGCAT)

  /**
   * MiniMax refresh — refreshed EVERY boot, but truthfully labelled: MiniMax
   * exposes no OpenAI-compatible /v1/models for chat, so the M-family ids are
   * an OPERATOR-ATTESTED allowlist (api.minimax.io subscription, attested
   * 2026-08-29), not live discovery. endpointSource says so — the selector and
   * receipts must never dress this up as a live catalogue.
   */
  suspend fun refreshMinimaxCatalogue(): Int {
    val apiKey = vault.retrieveSecret(ProviderSource.MINIMAX.vaultKey)
    if (apiKey.isNullOrBlank()) {
      Log.i(TAG, "MiniMax catalogue refresh skipped: MINIMAX_API_KEY missing")
      clearDirectFlow(ProviderSource.MINIMAX)
      return 0
    }
    // MiniMax native subscription (api.minimax.io, operator-attested 2026-08-29):
    // the M-family chat models served under the one MINIMAX_API_KEY. Legacy
    // abab ids belong to the old api.minimax.chat lane and are retired here.
    val ids = listOf(
      "MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed",
      "MiniMax-M2.5", "MiniMax-M2.1"
    )
    val list = ids.map { id ->
      CatalogueModel(
        id = "minimax/$id",
        name = id,
        provider = "MiniMax",
        providerType = ProviderType.DIRECT,
        sourceProvider = "minimax",
        description = "MiniMax · direct BYO key",
        contextLength = 0,
        isFree = false,
        isToolCapable = true,
        isVisionCapable = id.contains("vision", ignoreCase = true),
        isReasoningCapable = false,
        modelClass = "chat",
        avgLatencyMs = 0,
        healthStatus = "HEALTHY",
        pricingPrompt = 0.0,
        pricingCompletion = 0.0,
        isQualifiedFree = false,
        configured = true,
        available = true,
        discoveredAtMs = System.currentTimeMillis(),
        endpointSource = "attested_allowlist_2026-08-29",
        vaultKeyName = ProviderSource.MINIMAX.vaultKey
      )
    }
    writeDirectFlow(ProviderSource.MINIMAX, list)
    return list.size
  }

  // DIRECT PROVIDER CATALOGUES (provider-law 2026-08-26):
  // Each is only populated when its vault key is present. Until then the
  // selector renders an "Add API key" tile and these flows stay empty.
  // ProviderType=DIRECT, sourceProvider=<canonical id>, configured=true.
  private val _minimaxCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val minimaxCatalogue: StateFlow<List<CatalogueModel>> = _minimaxCatalogue.asStateFlow()
  private val _kimiCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val kimiCatalogue: StateFlow<List<CatalogueModel>> = _kimiCatalogue.asStateFlow()
  private val _qwenCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val qwenCatalogue: StateFlow<List<CatalogueModel>> = _qwenCatalogue.asStateFlow()
  private val _deepseekCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val deepseekCatalogue: StateFlow<List<CatalogueModel>> = _deepseekCatalogue.asStateFlow()
  private val _openaiCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val openaiCatalogue: StateFlow<List<CatalogueModel>> = _openaiCatalogue.asStateFlow()
  private val _zaiCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val zaiCatalogue: StateFlow<List<CatalogueModel>> = _zaiCatalogue.asStateFlow()
  private val _longcatCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val longcatCatalogue: StateFlow<List<CatalogueModel>> = _longcatCatalogue.asStateFlow()
  // Operator order 2026-09-01 — new AUTO gateway lanes (all OpenAI-compatible).
  private val _groqCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val groqCatalogue: StateFlow<List<CatalogueModel>> = _groqCatalogue.asStateFlow()
  private val _cerebrasCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val cerebrasCatalogue: StateFlow<List<CatalogueModel>> = _cerebrasCatalogue.asStateFlow()
  private val _googleAiCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val googleAiCatalogue: StateFlow<List<CatalogueModel>> = _googleAiCatalogue.asStateFlow()
  private val _cloudflareCatalogue = MutableStateFlow<List<CatalogueModel>>(emptyList())
  val cloudflareCatalogue: StateFlow<List<CatalogueModel>> = _cloudflareCatalogue.asStateFlow()

  // SPEND LAW: rolling counter in cents, persisted by caller (MainViewModel).
  // We expose getters so the UI can show "spent today / cap".
  private var dailySpendCents: Double = 0.0
  private var dailySpendDayKey: String = ""

  /**
   * AUTO FREE POOL: every model the AUTO router is allowed to pick.
   * MULTI-LANE LAW (operator 2026-09-01): every configured gateway joins the
   * pool — openrouter, nim, groq, cerebras, googleai, cloudflare — no
   * OpenRouter+NIM duopoly. A lane with a missing key simply contributes an
   * empty slice. AUTO NEVER touches a direct provider, no matter what the
   * operator pinned before. NO HARDCODED EXCLUSION: every gateway above is
   * admitted; misbehaviour is handled via the live health cache, not a
   * build-time list.
   */
  fun queryAllFreeGateways(): List<CatalogueModel> {
    fun laneConfigured(source: ProviderSource): Boolean {
      val keyPresent = !vault.retrieveSecret(source.vaultKey).isNullOrBlank()
      if (source != ProviderSource.CLOUDFLARE) return keyPresent
      // Cloudflare Workers AI needs the account id alongside the API token.
      return keyPresent && !vault.retrieveSecret("CLOUDFLARE_ACCOUNT_ID").isNullOrBlank()
    }
    val gatewayTags = setOf("openrouter", "nim", "nvidia", "groq", "cerebras", "googleai", "cloudflare")
    return buildList {
      if (laneConfigured(ProviderSource.OPENROUTER)) addAll(_openRouterCatalogue.value)
      if (laneConfigured(ProviderSource.NIM)) addAll(_nimCatalogue.value)
      if (laneConfigured(ProviderSource.GROQ)) addAll(_groqCatalogue.value)
      if (laneConfigured(ProviderSource.CEREBRAS)) addAll(_cerebrasCatalogue.value)
      if (laneConfigured(ProviderSource.GOOGLE_AI)) addAll(_googleAiCatalogue.value)
      if (laneConfigured(ProviderSource.CLOUDFLARE)) addAll(_cloudflareCatalogue.value)
    }.filter {
      it.providerType == ProviderType.GATEWAY &&
        it.isFree &&
        it.isQualifiedFree &&
        it.pricingPrompt == 0.0 &&
        it.pricingCompletion == 0.0 &&
        it.configured &&
        it.available &&
        it.sourceProvider in gatewayTags
    }
  }

  /** All DIRECT providers that have a key configured — feeds the manual-only selector lane. */
  fun queryAllDirect(): List<CatalogueModel> = buildList {
    addAll(_minimaxCatalogue.value)
    addAll(_kimiCatalogue.value)
    addAll(_qwenCatalogue.value)
    addAll(_deepseekCatalogue.value)
    addAll(_openaiCatalogue.value)
    addAll(_zaiCatalogue.value)
    addAll(_longcatCatalogue.value)
    addAll(_googleAiCatalogue.value)
  }.filter { it.configured && it.available }

  /**
   * Exact live model records currently eligible for the existing bounded AUTO
   * executor. Diagnostics consume this projection instead of reimplementing
   * ranking or copying ids into a second route table.
   */
  fun currentAutoCatalogueCandidates(toolsRequired: Boolean = false): List<CatalogueModel> {
    val gateways = queryAllFreeGateways()
    val ids = buildPhoneCandidates(
      gateways = gateways,
      requestedModel = null,
      toolsRequired = toolsRequired,
      openRouterConfigured = vault.hasSecret("OPENROUTER_API_KEY"),
      quarantined = ::isQuarantined
    )
    return ids.mapNotNull { id -> gateways.firstOrNull { it.id == id } }
  }

  /** SPEND LAW: would running modelId right now exceed the operator's cap? */
  fun isSpendAllowed(model: CatalogueModel, estimatedCostCents: Double = 0.0): SpendVerdict {
    val policy = _routingState.value.spendPolicy
    if (model.isFree && model.isQualifiedFree &&
      model.pricingPrompt == 0.0 && model.pricingCompletion == 0.0) {
      // A gateway is a transport class, not a price class. OpenRouter carries
      // both free and paid models; only independently qualified zero-price
      // routes may bypass the spend gate.
      return SpendVerdict(allowed = true, reason = "free lane")
    }
    return when (policy.mode) {
      SpendMode.OFF -> SpendVerdict(
        allowed = false,
        reason = "Spend mode OFF — paid lanes blocked. Switch to AUTO FREE or flip Spend Mode."
      )
      SpendMode.ASK -> SpendVerdict(
        allowed = false,
        reason = "Spend mode ASK — paid lane requires operator approval per turn."
      )
      SpendMode.AUTO_WITH_LIMIT -> {
        val dailyRoom = policy.dailyCapCents - dailySpendCents
        val perJobRoom = policy.perJobCapCents - estimatedCostCents
        when {
          policy.dailyCapCents > 0 && dailyRoom <= 0 -> SpendVerdict(
            allowed = false, reason = "Daily cap reached (${policy.dailyCapCents}¢)."
          )
          policy.perJobCapCents > 0 && perJobRoom < 0 -> SpendVerdict(
            allowed = false, reason = "Per-job cap exceeded (${policy.perJobCapCents}¢)."
          )
          else -> SpendVerdict(allowed = true, reason = "under cap")
        }
      }
    }
  }

  /** Record spend after a paid turn completes. Called by the caller once cost is known. */
  fun recordSpend(model: CatalogueModel, costCents: Double) {
    if (model.isFree && model.isQualifiedFree &&
      model.pricingPrompt == 0.0 && model.pricingCompletion == 0.0) return
    val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    if (today != dailySpendDayKey) {
      dailySpendDayKey = today
      dailySpendCents = 0.0
    }
    dailySpendCents += costCents
  }

  fun setSpendPolicy(policy: SpendPolicy) {
    updateRoutingState { it.copy(spendPolicy = policy) }
  }

  fun snapshotSpend(): SpendSnapshot = SpendSnapshot(
    spentCentsToday = dailySpendCents,
    capCents = _routingState.value.spendPolicy.dailyCapCents,
    mode = _routingState.value.spendPolicy.mode
  )

  /** Daily-spend rollover hook so the UI can re-read on day change. */
  fun primeSpendForToday(persistedCents: Double, dayKey: String) {
    val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    dailySpendDayKey = today
    dailySpendCents = if (dayKey == today) persistedCents else 0.0
  }

  private val sessionAffinityMap = mutableMapOf<String, String>() // sessionId -> modelId
  private val userPreferredModels = mutableSetOf<String>()
  private val userExcludedModels = mutableSetOf<String>()

  init {
    // NO HARDCODED SEEDS (operator 2026-09-02): the OpenRouter catalogue is empty
    // until the live /api/v1/models fetch returns. A failed or empty fetch leaves
    // the catalogue empty — seed lists are NOT authority.
    _openRouterCatalogue.value = emptyList()
    // MODEL PIN STICKINESS LAW: restore persisted pin before any routing happens.
    val savedProvider = prefs.getString(KEY_PROVIDER, null)
    val savedModel = prefs.getString(KEY_MODEL, null)
    if (savedProvider != null && savedModel != null) {
      _routingState.value = _routingState.value.copy(
        selectedProvider = savedProvider,
        selectedModel = savedModel,
        modelMode = if (savedModel == "AUTO") ModelMode.AUTO else ModelMode.MANUAL
      )
      Log.i(TAG, "restored pinned route: $savedProvider/$savedModel")
    }
    // MiniMax capability probe is a suspend network call — kicked from the
    // caller (ViewModel init) rather than the constructor.
  }

  fun updateRoutingState(transform: (RoutingState) -> RoutingState) {
    _routingState.value = transform(_routingState.value)
  }

  fun setModelMode(mode: ModelMode) {
    updateRoutingState { it.copy(modelMode = mode) }
  }

  fun setRoutingProfile(profile: RoutingProfile) {
    updateRoutingState { it.copy(routingProfile = profile) }
  }

  fun setReasoningEffort(effort: ReasoningEffort) {
    updateRoutingState { it.copy(reasoningEffort = effort) }
  }

  fun setSelectedModel(provider: String, model: String) {
    // CORE AUTHORITY LAW: the pin belongs to the canonical core. Local prefs
    // are a READ-CACHE for offline display/emergency use only — never the
    // source of truth. Write-through to core; core state wins on next sync.
    //
    // PROVIDER-LAW 2026-08-26: AUTO mode is restricted to the FREE GATEWAY pool.
    // Pinning a direct provider forces MANUAL so the operator's choice is
    // explicit and the spend law can gate the call.
    val resolvedProvider = resolveProviderFromPin(provider, model)
    val mode = if (model == "AUTO") ModelMode.AUTO else ModelMode.MANUAL

    prefs.edit()
      .putString(KEY_PROVIDER, provider)
      .putString(KEY_MODEL, model)
      .apply()
    updateRoutingState {
      it.copy(
        selectedProvider = provider,
        selectedModel = model,
        modelMode = mode,
        resolvedProvider = resolvedProvider
      )
    }
    // Fire-and-forget mirror to canonical core. Failure is non-fatal: the pin
    // still applies locally for the emergency lane, and core remains authoritative.
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
      try {
        HomeRuntimeBridge.pinRouter(provider = provider, model = model)
        Log.i(TAG, "pin mirrored to canonical core: $provider/$model (mode=$mode)")
      } catch (e: Exception) {
        Log.w(TAG, "core pin-mirror failed (offline?): ${e.message}")
      }
    }
  }

  /**
   * Resolve a (provider, model) pin to its canonical source. AUTO always
   * routes through gateways — for pin display we surface "gateway-pool"
   * rather than naming a direct provider.
   */
  private fun resolveProviderFromPin(provider: String, model: String): String {
    if (model == "AUTO") return "gateway-pool"
    return when {
      model.startsWith("openrouter/") -> "openrouter"
      model.startsWith("nvidia/") || _nimCatalogue.value.any { it.id == model } -> "nvidia"
      model.startsWith("groq/") -> "groq"
      model.startsWith("cerebras/") -> "cerebras"
      model.startsWith("googleai/") -> "google-ai"
      model.startsWith("cloudflare/") -> "cloudflare"
      model.startsWith("minimax/") || model.contains("abab") -> "minimax"
      model.startsWith("longcat/") || model.startsWith("LongCat-") -> "longcat"
      model.startsWith("kimi/") -> "kimi"
      model.startsWith("qwen/") -> "qwen"
      model.startsWith("deepseek/") -> "deepseek"
      model.startsWith("openai/") -> "openai"
      model.startsWith("z-ai/") || model.startsWith("glm-") -> "z-ai"
      else -> provider
    }
  }

  fun toggleSessionAffinity(enabled: Boolean) {
    updateRoutingState { it.copy(sessionAffinity = enabled) }
  }

  fun toggleFallbackEnabled(enabled: Boolean) {
    updateRoutingState { it.copy(fallbackEnabled = enabled) }
  }

  fun toggleFreeOnly(enabled: Boolean) {
    // FREE-ONLY LAW: paid lanes are purged from the catalogue; the switch
    // cannot re-enable what no longer exists.
    updateRoutingState { it.copy(freeOnly = true) }
  }

  fun toggleLocalOnly(enabled: Boolean) {
    updateRoutingState { it.copy(localOnly = enabled) }
  }

  fun toggleQualityGate(enabled: Boolean) {
    updateRoutingState { it.copy(qualityGateEnabled = enabled) }
  }

  fun setModelAutoPreference(modelId: String, isPreferred: Boolean) {
    if (isPreferred) {
      userPreferredModels.add(modelId)
      userExcludedModels.remove(modelId)
    } else {
      userPreferredModels.remove(modelId)
    }
    refreshCatalogueUserPreferences()
  }

  fun setModelAutoExclusion(modelId: String, isExcluded: Boolean) {
    if (isExcluded) {
      userExcludedModels.add(modelId)
      userPreferredModels.remove(modelId)
    } else {
      userExcludedModels.remove(modelId)
    }
    refreshCatalogueUserPreferences()
  }

  private fun refreshCatalogueUserPreferences() {
    _openRouterCatalogue.value = _openRouterCatalogue.value.map { model ->
      model.copy(
        isUserPreferredInAuto = userPreferredModels.contains(model.id),
        isUserExcludedFromAuto = userExcludedModels.contains(model.id)
      )
    }
  }

  /**
   * EMERGENCY-ONLY EXECUTOR — ROUTING AUTHORITY DEMOLISHED 2026-08-25.
   *
   * CORE AUTHORITY LAW: the canonical core (unified_api.js smart-router +
   * model-registry) owns ALL routing decisions. This method no longer scores
   * candidates, ranks a catalogue, or picks models. It exists solely so the
   * phone can still answer when the Home runtime is UNREACHABLE — and even
   * then it executes ONE pinned model with zero fallback chain.
   *
   * Every normal turn MUST go through HomeRuntimeBridge.chat(), which sends
   * literal "AUTO" or the operator's canonical pin to the core.
   *
   * Receipts are stamped EXECUTION_NODE=android-offline so the 5-fact badge
   * (mode/resolved/served/fallback/nodes) never mistakes this lane for a
   * core-routed turn.
   */
  suspend fun generateResponse(
    prompt: String,
    preferredProvider: String = "AUTO",
    systemInstruction: String = "You are PurpClaw Sovereign Agentic OS running on Android.",
    sessionId: String = "ses_canonical_01",
    toolsRequired: Boolean = false,
    visionRequired: Boolean = false,
    isHomeOnline: Boolean = false,
    conversationHistory: List<Pair<String, String>> = emptyList(),
    priority: SharedQuotaLedger.Priority = SharedQuotaLedger.Priority.INTERACTIVE
  ): ProviderExecutionResult = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    val state = _routingState.value
    // PINNING AUTHORITY LAW (operator 2026-09-02): the preferredProvider string
    // IS the authority. If "AUTO", routing runs scored-auto. If anything else,
    // modelMode must be MANUAL and receipts must say so — no silent AUTO override.
    val routingMode = if (preferredProvider == "AUTO") "AUTO" else "MANUAL"
    if (routingMode == "MANUAL") {
      Log.i(TAG, "MANUAL_PIN dispatch preferredProvider=$preferredProvider")
    }
    android.util.Log.i("VoiceTiming", "PROMPT_DISPATCH sessionId=$sessionId ts=${System.currentTimeMillis()} routingMode=$routingMode")

    // AUTO RESOLUTION LAW (2026-08-29 fix): AUTO must NOT mean "refuse if no
    // pin". When preferredProvider is AUTO, resolve a concrete eligible FREE
    // chat model from the freshly-booted live catalogue so the request actually
    // dispatches. Falling back to a stale persisted pin (state.selectedModel)
    // produced NO_ROUTING_AUTHORITY at 0ms — the exact death spiral we killed.
    // Only if the live catalogue yields nothing do we fall back to the pin.
    val emergencyModel = if (preferredProvider != "AUTO") preferredProvider
      else (queryAllFreeGateways().firstOrNull { it.modelClass == "chat" && it.isFree }?.id
        ?: state.selectedModel)
    val fallbackTrace = mutableListOf<String>()

    // ── BOUNDED ROTATION (2026-08-29, paste_45 eligibility gate) ──────────────
    // Rotation lives in the SHARED provider layer so CHAT/VOICE/WORK/PODCAST all
    // recover — not just the podcast lambda. A single free model can be dead
    // (openrouter/free -> "Stealth" 502, NIM starcoder2 404, hung free models);
    // we try up to 3 eligible FREE chat models, quarantining dead routes via the
    // health cache, never spinning on auth/quota (account-state, not model-health).
    val gateways: List<CatalogueModel> = queryAllFreeGateways()
    val candidates = buildPhoneCandidates(
      gateways = gateways,
      requestedModel = emergencyModel,
      toolsRequired = toolsRequired,
      openRouterConfigured = vault.hasSecret("OPENROUTER_API_KEY"),
      quarantined = ::isQuarantined,
      // CONTEXT-FIT LAW: rough char/4 token estimate of the prompt PLUS
      // system instruction PLUS prior history. The catalogue's per-model
      // contextLength is the source of truth; we filter candidates whose
      // advertised context is too small to fit the request rather than
      // burning a turn on a guaranteed 413.
      estimatedPromptTokens = (prompt.length + systemInstruction.length +
        conversationHistory.sumOf { it.first.length + it.second.length }) / 4
    )

    if (candidates.isEmpty()) {
      Log.w(TAG, "No eligible FREE route for sovereign fallback (catalogue empty or all quarantined).")
      val attemptRecords = listOf(com.example.core.model.AttemptRecord(
        attemptIndex = 0,
        provider = null,
        model = null,
        outcome = com.example.core.model.AttemptOutcome.SKIPPED,
        failureClass = "NO_ELIGIBLE_ROUTE",
        errorDetail = "no eligible FREE chat model available (empty catalogue or all quarantined)"
      ))
      val receipt = RoutingReceipt(
        routingMode = routingMode,
        resolvedProvider = "none",
        resolvedModel = "none",
        fallbackOccurred = false,
        fallbackPath = emptyList(),
        routingReason = "NO_ELIGIBLE_ROUTE",
        qualityGateResult = "NO_ELIGIBLE_ROUTE",
        attempts = attemptRecords
      )
      return@withContext ProviderExecutionResult(
        content = "", providerModel = "none", tokenCount = 0,
        latencyMs = System.currentTimeMillis() - startTime,
        errorMessage = "NO_ELIGIBLE_ROUTE",
        routingReceipt = receipt
      ).also {
        com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
          router = "ProviderRouter",
          kind = "model_route",
          decision = "NO_ELIGIBLE_ROUTE",
          success = false,
          durationMs = it.latencyMs,
          sessionId = sessionId,
          input = "preferredProvider=$preferredProvider modelClass=${if (toolsRequired) "tool" else "chat"}",
          output = "candidates=${candidates.size}",
          errorClass = "NO_ELIGIBLE_ROUTE",
          errorMessage = "no eligible FREE chat model available (empty catalogue or all quarantined)"
        )
      }
    }

    // OWNER-FIRST LAW (operator 2026-09-01): MiniMax is Eddie's paid sub — it goes
    // first before any free gateway rotation WHEN the operator has NOT pinned a
    // manual model. A manual pin (preferredProvider != "AUTO") MUST take priority
    // over owner-first; otherwise the picker reverts to MiniMax M2.1 regardless
    // of what was selected (regression logged 2026-09-02).
    //
    // Pin-honour check: if the manual pin is a direct-provider id (minimax/,
    // kimi/, qwen/, deepseek/, etc.) AND the pin is configured, dispatch the
    // pin as a single-shot call — no gateway rotation, no MiniMax splice. This
    // matches the operator's explicit choice.
    val pinIsManual = preferredProvider != "AUTO" && preferredProvider.isNotBlank()
    val pinIsDirectProvider = pinIsManual && (
      preferredProvider.startsWith("minimax/") || preferredProvider.startsWith("kimi/") ||
        preferredProvider.startsWith("qwen/") || preferredProvider.startsWith("deepseek/") ||
        preferredProvider.startsWith("openai/") || preferredProvider.startsWith("z-ai/") ||
        preferredProvider.startsWith("longcat/") || preferredProvider.startsWith("googleai/")
      )
    val pinRecord = if (pinIsManual && !pinIsDirectProvider) {
      // Gateway-pin: confirm it lives in the gateway pool. If not, fall through.
      gateways.firstOrNull { it.id == preferredProvider }
    } else null
    // Normalize google-ai/ → googleai/ for direct lookup (catalogue uses googleai/)
    val normalizedPrefProvider = if (preferredProvider.startsWith("google-ai/")) {
      "googleai/${preferredProvider.removePrefix("google-ai/")}"
    } else preferredProvider
    val directPinRecord = if (pinIsDirectProvider) {
      queryAllDirect().firstOrNull { it.id == normalizedPrefProvider }
    } else null

    val allCandidates = when {
      // Manual pin to a direct provider that is configured → run it alone.
      // Bypasses both owner-first MiniMax splice AND gateway rotation so the
      // operator's explicit pick is honoured without fail-over drowning it.
      directPinRecord != null -> listOf(directPinRecord.id)
      // Manual pin to a gateway pool model → run it first, then gateway fallbacks,
      // but DO NOT prepend MiniMax (owner-first) — the operator chose otherwise.
      pinRecord != null -> listOf(pinRecord.id) + candidates.filter { it != pinRecord.id }
      // AUTO (or unresolved manual pin) → owner-first splice, then gateway rotation.
      // If preferredProvider carries an embedded provider prefix (e.g. "groq/qwen3.8-27b"),
      // strip it so it becomes the bare catalog id and resolveProviderFromPin does not
      // re-prepend the same prefix (triple-prefix bug: groq/groq/qwen3.8-27b).
      else -> {
        val miniMaxChatModels = _minimaxCatalogue.value.filter {
          it.modelClass == "chat" && it.available && it.configured && !isQuarantined(it.id)
        }.map { it.id }
        val strippedPin = if (preferredProvider.contains("/")) {
          val afterFirst = preferredProvider.substringAfter("/")
          if (afterFirst.contains("/")) afterFirst.substringAfter("/") else afterFirst
        } else preferredProvider
        listOfNotNull(strippedPin.takeIf { it.isNotBlank() && it != preferredProvider }) + miniMaxChatModels + candidates
      }
    }

    // Attempt chain tracking for canonical receipt
    val attemptRecords = mutableListOf<com.example.core.model.AttemptRecord>()
    var servedProvider: String? = null
    var servedModel: String? = null

    var lastResult: ProviderExecutionResult? = null
    for ((attemptIdx, cand) in allCandidates.withIndex()) {
      val cProvider = when (val src = resolveProviderFromPin(preferredProvider, cand)) {
        "nvidia", "nim" -> "nim"
        "openrouter" -> "openrouter"
        "minimax" -> "minimax"
        else -> SharedQuotaLedger.providerTagFor(cand)
      }
      val cLogicalId = "gen_${UUID.randomUUID().toString().take(10)}"
      val attemptStart = System.currentTimeMillis()
      var acquired = false
      var lastDenial: SharedQuotaLedger.AcquireResult.Denied? = null
      for (waitHop in 0 until 6) {
        when (val r = SharedQuotaLedger.acquire(cProvider, cLogicalId, waitHop, priority)) {
          is SharedQuotaLedger.AcquireResult.Granted -> { acquired = true; break }
          is SharedQuotaLedger.AcquireResult.Denied -> {
            lastDenial = r
            if (r.reason in setOf("STORM_KILL_SWITCH", "CIRCUIT_OPEN", "PROVIDER_RETRY_AFTER", "PROVIDER_QUOTA_EXHAUSTED", "DUPLICATE_REQUEST")) break
            delay(r.backoffMs.coerceAtLeast(50))
          }
        }
      }
      val attemptEnd = System.currentTimeMillis()
      if (!acquired) {
        lastResult = ProviderExecutionResult(content = "", providerModel = cand, tokenCount = 0,
          latencyMs = attemptEnd - attemptStart,
          errorMessage = "RATE_LIMITED_LOCAL: ${cProvider} budget exhausted (${lastDenial?.reason})",
          statusCode = 429,
          routingReceipt = null
        )
        // Record this attempt's failure in the canonical chain
        val fc = SharedQuotaLedger.classifyFailure(lastResult!!.errorMessage)
        attemptRecords.add(com.example.core.model.AttemptRecord(
          attemptIndex = attemptIdx,
          provider = cProvider,
          model = cand,
          outcome = when (fc) {
            SharedQuotaLedger.FailureClass.RATE_LIMITED -> com.example.core.model.AttemptOutcome.RATE_LIMITED
            SharedQuotaLedger.FailureClass.PROVIDER_QUOTA_EXHAUSTED -> com.example.core.model.AttemptOutcome.QUOTA_EXHAUSTED
            SharedQuotaLedger.FailureClass.AUTH_MISSING_KEY,
            SharedQuotaLedger.FailureClass.AUTH_REJECTED,
            SharedQuotaLedger.FailureClass.AUTH_FORBIDDEN -> com.example.core.model.AttemptOutcome.AUTH_REQUIRED
            SharedQuotaLedger.FailureClass.TIMEOUT -> com.example.core.model.AttemptOutcome.FIRST_TOKEN_TIMEOUT
            SharedQuotaLedger.FailureClass.DEAD_ENDPOINT -> com.example.core.model.AttemptOutcome.SERVER_ERROR
            SharedQuotaLedger.FailureClass.TOOLS_UNSUPPORTED -> com.example.core.model.AttemptOutcome.UNKNOWN_FAILURE
            SharedQuotaLedger.FailureClass.PROMPT_TOO_LARGE,
            SharedQuotaLedger.FailureClass.CONTEXT_TOO_SMALL -> com.example.core.model.AttemptOutcome.CONTEXT_OVERFLOW
            SharedQuotaLedger.FailureClass.OTHER -> com.example.core.model.AttemptOutcome.UNKNOWN_FAILURE
          },
          failureClass = fc.name,
          statusCode = 429,
          startedAtMs = attemptStart, endedAtMs = attemptEnd,
          latencyMs = attemptEnd - attemptStart,
          errorDetail = (lastResult!!.errorMessage ?: "quota exhausted").take(200)
        ))
        continue
      }
      fallbackTrace.add("candidate: $cand")
      val res = try {
        executeSpecificModel(cand, prompt, systemInstruction, attemptStart, conversationHistory)
      } finally {
        SharedQuotaLedger.release(cProvider)
      }
      val resEnd = System.currentTimeMillis()
      lastResult = res
      val cErr = res.errorMessage
      if (cErr == null || res.content.isNotBlank()) {
        // SUCCESS — record the served attempt with the ACTUAL served provider/model
        servedProvider = cProvider
        servedModel = cand
        // SERVED = live entitlement proof: float this id to the front of its
        // lane and revive it if it was previously marked dead.
        markModelServed(cand)
        attemptRecords.add(com.example.core.model.AttemptRecord(
          attemptIndex = attemptIdx,
          provider = cProvider,
          model = cand,
          outcome = com.example.core.model.AttemptOutcome.SERVED,
          startedAtMs = attemptStart, endedAtMs = resEnd,
          latencyMs = res.latencyMs,
          statusCode = res.statusCode
        ))
        val receipt = RoutingReceipt(
          routingMode = routingMode,
          resolvedProvider = cProvider,
          resolvedModel = cand,
          servedProvider = cProvider,
          servedModel = cand,
          fallbackOccurred = attemptIdx > 0,
          // SUCCESS-PATH CRASH FIX (2026-09-02, caught live): rotation can
          // advance attemptIdx past the candidate list length (quarantines and
          // circuit changes shrink eligibility mid-turn). An unclamped subList
          // threw IndexOutOfBoundsException exactly when a model SERVED —
          // crashing the app at the moment of victory and murdering the turn.
          fallbackPath = candidates.subList(0, attemptIdx.coerceAtMost(candidates.size)).toList(),
          routingReason = if (attemptIdx == 0) "AUTO_FIRST_ELIGIBLE" else "AUTO_ROTATION_FROM_$attemptIdx",
          qualityGateResult = "PASS",
          attempts = attemptRecords
        )
        return@withContext res.copy(
          routingReceipt = receipt,
          latencyMs = System.currentTimeMillis() - startTime
        )
      }
      val failureClass = SharedQuotaLedger.classifyFailure(cErr)
      val fcOutcome = when (failureClass) {
        SharedQuotaLedger.FailureClass.RATE_LIMITED -> com.example.core.model.AttemptOutcome.RATE_LIMITED
        SharedQuotaLedger.FailureClass.PROVIDER_QUOTA_EXHAUSTED -> com.example.core.model.AttemptOutcome.QUOTA_EXHAUSTED
        SharedQuotaLedger.FailureClass.AUTH_MISSING_KEY -> com.example.core.model.AttemptOutcome.AUTH_REQUIRED
        SharedQuotaLedger.FailureClass.AUTH_REJECTED -> com.example.core.model.AttemptOutcome.AUTH_REQUIRED
        SharedQuotaLedger.FailureClass.AUTH_FORBIDDEN -> com.example.core.model.AttemptOutcome.AUTH_REQUIRED
        SharedQuotaLedger.FailureClass.TIMEOUT -> com.example.core.model.AttemptOutcome.FIRST_TOKEN_TIMEOUT
        SharedQuotaLedger.FailureClass.DEAD_ENDPOINT -> com.example.core.model.AttemptOutcome.SERVER_ERROR
        SharedQuotaLedger.FailureClass.TOOLS_UNSUPPORTED -> com.example.core.model.AttemptOutcome.UNKNOWN_FAILURE
        SharedQuotaLedger.FailureClass.PROMPT_TOO_LARGE,
        SharedQuotaLedger.FailureClass.CONTEXT_TOO_SMALL -> com.example.core.model.AttemptOutcome.CONTEXT_OVERFLOW
        SharedQuotaLedger.FailureClass.OTHER -> com.example.core.model.AttemptOutcome.UNKNOWN_FAILURE
      }
      // Provider/credential state is route-local. Record it and advance to a
      // different eligible provider; never kill the whole AUTO turn because
      // one credential hit quota/auth/rate limits.
      // Per-model permanent errors (unknown model, terms not accepted, tool-
      // calling unsupported) are also route-local — they won't recover in 60 s.
      val perModelPermanent = cErr != null && PER_MODEL_PERMANENT_ERRORS.any { cErr.contains(it, ignoreCase = true) }
      // Typed route/model-local failures (dead endpoint, tool-schema rejection,
      // oversized prompt) rotate to the next candidate — they never recover in
      // 60s and never justify killing the whole AUTO turn.
      if (failureClass in setOf(SharedQuotaLedger.FailureClass.AUTH_MISSING_KEY, SharedQuotaLedger.FailureClass.AUTH_REJECTED, SharedQuotaLedger.FailureClass.AUTH_FORBIDDEN, SharedQuotaLedger.FailureClass.RATE_LIMITED, SharedQuotaLedger.FailureClass.PROVIDER_QUOTA_EXHAUSTED, SharedQuotaLedger.FailureClass.DEAD_ENDPOINT, SharedQuotaLedger.FailureClass.TOOLS_UNSUPPORTED, SharedQuotaLedger.FailureClass.PROMPT_TOO_LARGE, SharedQuotaLedger.FailureClass.CONTEXT_TOO_SMALL) || perModelPermanent) {
        // NO-REPEAT LAW (2026-09-02 live fix): dead endpoints, tool-unsupported
        // models and other per-model permanent failures are quarantined HERE —
        // rotating without quarantining made every turn re-try the same dead
        // NIM/Groq/Cloudflare ids until exhaustion. A provider-level quota
        // wall (Cerebras 402) opens that lane's circuit so it stops firing
        // once per turn too.
        if (failureClass == SharedQuotaLedger.FailureClass.DEAD_ENDPOINT ||
          failureClass == SharedQuotaLedger.FailureClass.TOOLS_UNSUPPORTED ||
          perModelPermanent
        ) {
          quarantineModel(cand)
          // Persist entitlement-dead across boots — see PERSISTED ROUTE HEALTH.
          if (failureClass == SharedQuotaLedger.FailureClass.DEAD_ENDPOINT || perModelPermanent) {
            markModelDead(cand)
          }
        }
        if (failureClass == SharedQuotaLedger.FailureClass.PROVIDER_QUOTA_EXHAUSTED) {
          runCatching { SharedQuotaLedger.recordQuotaExhausted(cProvider) }
        }
        if (failureClass == SharedQuotaLedger.FailureClass.RATE_LIMITED) {
          runCatching { SharedQuotaLedger.recordRateLimit(cProvider) }
        }
        attemptRecords.add(com.example.core.model.AttemptRecord(
          attemptIndex = attemptIdx, provider = cProvider, model = cand,
          outcome = fcOutcome, failureClass = failureClass.name, statusCode = res.statusCode,
          startedAtMs = attemptStart, endedAtMs = resEnd,
          latencyMs = res.latencyMs,
          errorDetail = (cErr ?: "failure").take(200)
        ))
        fallbackTrace.add("candidate '$cand' unavailable: ${failureClass.name}${if (perModelPermanent) " (per-model permanent)" else ""} — rotating provider")
        continue
      }
      // Transport/dead failure: quarantine + rotate to next healthy route.
      quarantineModel(cand)
      // "No such model" on a 400 is the same entitlement lie as a 404 function
      // miss — persist it dead so it never wastes another first attempt.
      if (cErr?.contains("no such model", ignoreCase = true) == true ||
        cErr?.contains("function '", ignoreCase = true) == true
      ) {
        markModelDead(cand)
      }
      fallbackTrace.add("candidate '$cand' failed: $cErr — rotating")
      attemptRecords.add(com.example.core.model.AttemptRecord(
        attemptIndex = attemptIdx, provider = cProvider, model = cand,
        outcome = fcOutcome, failureClass = failureClass.name, statusCode = res.statusCode,
        startedAtMs = attemptStart, endedAtMs = resEnd,
        latencyMs = res.latencyMs,
        errorDetail = (cErr ?: "failure").take(200)
      ))
    }
    // Exhausted all eligible routes — return the last honest failure with receipt.
    val finalErr = lastResult?.errorMessage ?: "sovereign fallback exhausted eligible free models"
    val receipt = RoutingReceipt(
      routingMode = routingMode,
      resolvedProvider = servedProvider ?: "none",
      resolvedModel = servedModel ?: "none",
      servedProvider = servedProvider,
      servedModel = servedModel,
      fallbackOccurred = true,
      fallbackPath = allCandidates.toList(),
      routingReason = "AUTO_ALL_CANDIDATES_EXHAUSTED",
      qualityGateResult = "FAIL",
      attempts = attemptRecords
    )
    return@withContext (lastResult ?: ProviderExecutionResult(
      content = "", providerModel = "none", tokenCount = 0,
      latencyMs = System.currentTimeMillis() - startTime, errorMessage = finalErr
    )).copy(routingReceipt = receipt, latencyMs = System.currentTimeMillis() - startTime).also { finalRes ->
      // TELEMETRY: every AUTO rotation outcome is queryable.
      val errMsg = finalRes.errorMessage.orEmpty()
      com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
        router = "ProviderRouter",
        kind = "model_route",
        decision = if (errMsg.isBlank()) "served:${finalRes.providerModel}" else "FAILED",
        success = errMsg.isBlank(),
        durationMs = finalRes.latencyMs,
        sessionId = sessionId,
        input = "preferredProvider=$preferredProvider candidates=${allCandidates.size}",
        output = "served=${finalRes.providerModel} attempts=${attemptRecords.size} fallback=${receipt.fallbackPath.size}",
        errorClass = if (errMsg.isBlank()) "" else "ALL_EXHAUSTED",
        errorMessage = errMsg
      )
    }
  }
    // (rotation path above returns directly; no single-pin post-handling)



  // ONE ROUTER LAW: candidate ranking, quality gating and routing-reason
  // minting were deleted — routing authority lives on the core runtime only.
  // The emergency executor runs exactly one pinned model or refuses.

  private suspend fun executeSpecificModel(
    modelId: String,
    prompt: String,
    systemInstruction: String,
    startTime: Long,
    conversationHistory: List<Pair<String, String>> = emptyList()
  ): ProviderExecutionResult {
    // DOUBLE-PREFIX GUARD (2026-09-02): modelId may arrive with its provider
    // prefix already attached (e.g. "groq/qwen/qwen3.8-27b" from a prior
    // setSelectedModel call). Strip it before any routing logic so the clean
    // bare id is always what gets stored in prefs and passed to executors.
    val cleanModelId = modelId.let { id ->
      val prefix = listOf("minimax", "kimi", "qwen", "deepseek", "openai", "z-ai", "longcat", "openrouter", "nvidia", "groq", "cerebras", "googleai", "cloudflare", "local")
        .firstOrNull { id.startsWith("${it}/") }
      if (prefix != null) id.removePrefix("${prefix}/") else id
    }

    // GOOGLE PURGE LAW — execute-layer guard: even a pinned/manual google lane refuses.
    // EXCEPTION (operator order 2026-09-01): the googleai/ lane is the sanctioned
    // Google AI Studio free tier under its own key and MAY execute. Everything
    // else google/gemini stays purged (stray gateway ids, local Gemma routing).
    val googleAiLane = cleanModelId.startsWith("googleai/") ||
      _googleAiCatalogue.value.any { it.id == cleanModelId }
    if (!googleAiLane && (cleanModelId.startsWith("google/") || cleanModelId.contains("gemini", ignoreCase = true))) {
      return ProviderExecutionResult(
        content = "", providerModel = cleanModelId, tokenCount = 0, latencyMs = 0,
        errorMessage = "Google cloud lanes are purged from this build (local Gemma is download-only, never routed)"
      )
    }
    // Resolve a direct provider by its prefixed id so the manual-only lanes
    // have an executor. AUTO never reaches here for a DIRECT provider.
    val directPrefix = listOf(
      ProviderSource.MINIMAX, ProviderSource.KIMI, ProviderSource.QWEN,
      ProviderSource.DEEPSEEK, ProviderSource.OPENAI, ProviderSource.ZAI,
      ProviderSource.LONGCAT
    ).firstOrNull { cleanModelId.startsWith("${it.name.lowercase().split(" ")[0].replace("(", "").replace(")", "")}/") }
    return when {
      cleanModelId.startsWith("openrouter/") || _openRouterCatalogue.value.any { it.id == cleanModelId && it.provider == "openrouter" } -> {
        callOpenRouter(prompt, cleanModelId, systemInstruction, conversationHistory)
      }
      cleanModelId.startsWith("minimax/") || cleanModelId.contains("abab") -> {
        callMiniMaxText(prompt, cleanModelId, systemInstruction, conversationHistory)
      }
      cleanModelId.startsWith("nvidia/") || _nimCatalogue.value.any { it.id == cleanModelId || it.id == "nvidia/$cleanModelId" } -> {
        callNvidiaNim(prompt, cleanModelId, systemInstruction, conversationHistory)
      }
      // New AUTO gateway lanes (operator 2026-09-01) — all OpenAI-compatible.
      cleanModelId.startsWith("groq/") || _groqCatalogue.value.any { it.id == cleanModelId || it.id == "groq/$cleanModelId" } -> {
        callOpenAiCompatible(ProviderSource.GROQ, cleanModelId, prompt, systemInstruction, startTime, conversationHistory)
      }
      cleanModelId.startsWith("cerebras/") || _cerebrasCatalogue.value.any { it.id == cleanModelId || it.id == "cerebras/$cleanModelId" } -> {
        callOpenAiCompatible(ProviderSource.CEREBRAS, cleanModelId, prompt, systemInstruction, startTime, conversationHistory)
      }
      googleAiLane -> {
        callOpenAiCompatible(ProviderSource.GOOGLE_AI, cleanModelId, prompt, systemInstruction, startTime, conversationHistory)
      }
      cleanModelId.startsWith("cloudflare/") || _cloudflareCatalogue.value.any { it.id == cleanModelId || it.id == "cloudflare/$cleanModelId" } -> {
        callOpenAiCompatible(ProviderSource.CLOUDFLARE, cleanModelId, prompt, systemInstruction, startTime, conversationHistory)
      }
      // Direct-provider dispatch (all OpenAI-compatible chat completions).
      directPrefix != null -> {
        callOpenAiCompatible(directPrefix, cleanModelId, prompt, systemInstruction, startTime, conversationHistory)
      }
      cleanModelId.startsWith("local/") -> {
        try {
          val cleanId = cleanModelId.removePrefix("local/")
          val localResult = localHost.streamChat(cleanId, prompt) { /* stream */ }
          ProviderExecutionResult(
            content = localResult,
            providerModel = "local/$cleanId",
            tokenCount = localResult.split(" ").size,
            latencyMs = System.currentTimeMillis() - startTime
          )
        } catch (e: Exception) {
          ProviderExecutionResult(
            content = "",
            providerModel = cleanModelId,
            tokenCount = 0,
            latencyMs = System.currentTimeMillis() - startTime,
            errorMessage = "Local Model Error: ${e.message}"
          )
        }
      }
      else -> {
        // ONE ROUTER LAW: unknown/unrouted model IDs never fall through to
        // local ranking or a cloud lane. Honest refusal — core is authority.
        ProviderExecutionResult(
          content = "", providerModel = cleanModelId, tokenCount = 0,
          latencyMs = System.currentTimeMillis() - startTime,
          errorMessage = "NO_ROUTING_AUTHORITY: '$cleanModelId' is not an emergency-pinned model; core owns routing"
        )
      }
    }
  }

  /** Shared OpenAI-compatible chat completion caller for direct providers. */
  private suspend fun callOpenAiCompatible(
    source: ProviderSource,
    modelId: String,
    prompt: String,
    systemInstruction: String,
    startTime: Long,
    conversationHistory: List<Pair<String, String>>
  ): ProviderExecutionResult {
    val apiKey = vault.retrieveSecret(source.vaultKey)
      ?: return ProviderExecutionResult(
        content = "", providerModel = modelId, tokenCount = 0, latencyMs = 0,
        errorMessage = "401 Unauthorized: ${source.vaultKey} missing in KeystoreVault"
      )
    // Strip ONLY this lane's own namespace prefix. Gateway-native ids often
    // contain slashes themselves (groq "openai/gpt-oss-120b", cloudflare
    // "@cf/meta/llama-...") and must reach the wire intact.
    val laneTag = when (source) {
      ProviderSource.NIM -> "nvidia"
      ProviderSource.GOOGLE_AI -> "googleai"
      else -> source.name.lowercase().split(" ")[0].replace("(", "").replace(")", "")
    }
    val actualModel = if (modelId.startsWith("$laneTag/")) modelId.removePrefix("$laneTag/") else modelId
    // Cloudflare Workers AI exposes its OpenAI-compatible surface under
    // /client/v4/accounts/{id}/ai/v1 — the account id lives in the vault.
    val effectiveBase = if (source == ProviderSource.CLOUDFLARE) {
      val accountId = vault.retrieveSecret("CLOUDFLARE_ACCOUNT_ID")
        ?: return ProviderExecutionResult(
          content = "", providerModel = modelId, tokenCount = 0, latencyMs = 0,
          errorMessage = "401 Unauthorized: CLOUDFLARE_ACCOUNT_ID missing in KeystoreVault"
        )
      "${source.baseUrl}/accounts/$accountId/ai/v1"
    } else source.baseUrl
    return try {
      val payload = JSONObject().apply {
        put("model", actualModel)
        put("messages", buildMessagesArray(systemInstruction, conversationHistory, prompt))
        if (pendingTools.any { it.name == "android.file.write" }) put("max_tokens", 4096)
        // FUNCTION-CALLING WIRE: attach `tools:` when caller set pendingTools
        // and wire is enabled. OpenAI-compatible envelope.
        if (toolsWireEnabled && pendingTools.isNotEmpty()) {
          put("tools", canonicalToolsToWire(pendingTools))
          put("tool_choice", pendingToolChoice)
          if (actualModel == "free") {
            put("provider", JSONObject().put("require_parameters", true))
          }
          lastWireTools = pendingTools
        }
      }
      val request = Request.Builder()
        .url("$effectiveBase/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()
      val response = httpClient.newCall(request).execute()
      android.util.Log.i("VoiceTiming", "FIRST_MODEL_TOKEN provider=$source ts=${System.currentTimeMillis()}")
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val errorMsg = "${source.name} HTTP ${response.code}: $body"
        Log.e(TAG, errorMsg)
        val lat = System.currentTimeMillis() - startTime
        persistDispatchReceipt(
          providerLabel = source.name.lowercase(),
          modelIdUsed = modelId,
          latencyMs = lat,
          routingReason = errorMsg,
          fallbackTrace = emptyList(),
          errorCode = errorMsg
        )
        return ProviderExecutionResult(
        content = "", providerModel = modelId, tokenCount = 0,
        latencyMs = lat, errorMessage = errorMsg, statusCode = response.code
        )
      }
      val json = JSONObject(body)
      val (text, reasoning) = parseChoiceMessage(json)
      val usage = json.optJSONObject("usage")
      val lat = System.currentTimeMillis() - startTime
      persistDispatchReceipt(
        providerLabel = source.name.lowercase(),
        modelIdUsed = modelId,
        latencyMs = lat,
        routingReason = "${source.name} chat completion",
        fallbackTrace = emptyList(),
        errorCode = null
      )
      ProviderExecutionResult(
        content = text,
        providerModel = modelId,
        tokenCount = usage?.optInt("total_tokens") ?: (text.split(" ").size + prompt.split(" ").size),
        latencyMs = lat,
        reasoning = reasoning
      )
    } catch (e: Exception) {
      Log.e(TAG, "${source.name} call failed: ${e.message}", e)
      val lat = System.currentTimeMillis() - startTime
      persistDispatchReceipt(
        providerLabel = source.name.lowercase(),
        modelIdUsed = modelId,
        latencyMs = lat,
        routingReason = "${source.name} exception: ${e.message}",
        fallbackTrace = emptyList(),
        errorCode = "EXECUTOR_ERROR"
      )
      ProviderExecutionResult(
        content = "", providerModel = modelId, tokenCount = 0,
        latencyMs = lat,
        errorMessage = "${source.name} error: ${e.message}"
      )
    }
  }

  /**
   * Fetch build.nvidia.com/models and extract the Free Endpoint model names
   * from the embedded Next.js searchResult JSON. This is NVIDIA's editorial
   * "Free Endpoint" filter — the same list the website's UI shows on page 1.
   * The HTML embeds the catalogue as a JS-string-escaped JSON object, which
   * we locate, unescape, and parse.
   *
   * Returns the set of bare model names (e.g. "kimi-k3", "deepseek-v4-pro-0813",
   * "nemotron-3-ultra-550b-a55b"). Caller intersects with /v1/models ids by
   * stripping the namespace prefix (e.g. "moonshotai/kimi-k3" -> "kimi-k3").
   *
   * Truth source: this is not a hardcoded list. Every refresh re-fetches the
   * live build.nvidia.com/models page, so when NVIDIA adds/removes an
   * endpoint from the Free Endpoint filter, it shows up in the next boot.
   * Limit: page 1 only (16 unique endpoints). Pages 2-5 are client-side
   * rendered after the user scrolls in the website UI; a public API for
   * the full 5-page dataset does not exist.
   */
  private suspend fun fetchBuildNvidiaFreeEndpointNames(): Set<String> {
    return withContext(Dispatchers.IO) {
      try {
        val req = Request.Builder()
          .url("https://build.nvidia.com/models")
          .addHeader("Accept", "text/html")
          .addHeader("User-Agent", "PurpClaw/1.0")
          .get()
        val resp = httpClient.newCall(req.build()).execute()
        val html = resp.body?.string().orEmpty()
        resp.close()
        if (html.isBlank()) {
          Log.w(TAG, "build.nvidia.com/models returned empty body")
          return@withContext emptySet()
        }
        val names = parseBuildNvidiaFreeEndpointNames(html)
        Log.i(TAG, "build.nvidia.com Free Endpoint filter: ${names.size} unique names (page 1)")
        names
      } catch (e: Exception) {
        Log.w(TAG, "build.nvidia.com Free Endpoint fetch failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"} — falling back to chat-classifier only")
        emptySet()
      }
    }
  }

  /**
   * Parse the Next.js server-rendered HTML for the Free Endpoint names.
   * The HTML embeds the catalogue as a JS-string-escaped JSON inside a
   * script tag. We anchor on the escaped \"searchResult\":{ key, walk to
   * the matching close brace (counting braces, respecting JS string
   * escapes), then unescape and parse the JSON.
   */
  private fun parseBuildNvidiaFreeEndpointNames(html: String): Set<String> {
    val anchor = "\\\"searchResult\\\":{"
    val anchorIdx = html.indexOf(anchor)
    if (anchorIdx < 0) return emptySet()
    var objStart = anchorIdx + anchor.length - 1  // the {
    if (objStart >= html.length || html[objStart] != '{') return emptySet()
    // Walk forward to find the matching close brace
    var depth = 0
    var i = objStart
    var inString = false
    var escape = false
    var objEnd = -1
    while (i < html.length) {
      val ch = html[i]
      if (escape) {
        escape = false
        i++
        continue
      }
      if (ch == '\\') {
        escape = true
        i++
        continue
      }
      if (ch == '"') {
        inString = !inString
      } else if (!inString) {
        if (ch == '{') depth++
        else if (ch == '}') {
          depth--
          if (depth == 0) { objEnd = i + 1; break }
        }
      }
      i++
    }
    if (objEnd < 0) return emptySet()
    val raw = html.substring(objStart, objEnd)
    // Unescape JS string literal: \" -> ", \\ -> \
    val unescaped = raw.replace("\\\"", "\"").replace("\\\\", "\\")
    return try {
      val root = JSONObject(unescaped)
      val results = root.optJSONArray("results") ?: return emptySet()
      val seen = mutableSetOf<String>()
      for (g in 0 until results.length()) {
        val group = results.optJSONObject(g) ?: continue
        val resources = group.optJSONArray("resources") ?: continue
        for (r in 0 until resources.length()) {
          val resource = resources.optJSONObject(r) ?: continue
          val labels = resource.optJSONArray("labels") ?: continue
          var isFree = false
          for (l in 0 until labels.length()) {
            val label = labels.optJSONObject(l) ?: continue
            if (label.optString("key") == "nimType") {
              val values = label.optJSONArray("values") ?: continue
              for (v in 0 until values.length()) {
                if (values.optString(v) == "Free Endpoint") { isFree = true; break }
              }
              if (isFree) break
            }
          }
          if (isFree) {
            val name = resource.optString("name")
            if (name.isNotBlank()) seen.add(name)
          }
        }
      }
      seen
    } catch (e: Exception) {
      Log.w(TAG, "build.nvidia.com Free Endpoint parse failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
      emptySet()
    }
  }

  /**
   * NVIDIA NIM — integrate.api.nvidia.com/v1 (OpenAI-compatible, free tier).
   * Auto model selection pulls the live /v1/models catalog into the selector,
   * then intersects with build.nvidia.com's editorial Free Endpoint list so
   * the operator sees exactly what NVIDIA's catalogue page shows.
   */
  suspend fun refreshNimCatalogue(): Int {
    val apiKey = vault.retrieveSecret("NVIDIA_NIM_API_KEY")
    if (apiKey.isNullOrBlank()) {
      Log.w(TAG, "NIM catalogue refresh skipped: NVIDIA_NIM_API_KEY missing")
      return 0
    }
    val freeEndpointNames = fetchBuildNvidiaFreeEndpointNames()
    return try {
      // Network call MUST run on IO dispatcher — never block the Main thread
      val models = withContext(Dispatchers.IO) {
        val req = Request.Builder()
          .url("https://integrate.api.nvidia.com/v1/models")
          .addHeader("Authorization", "Bearer $apiKey")
          .get()
        val resp = httpClient.newCall(req.build()).execute()
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          Log.e(TAG, "NIM models HTTP ${resp.code}")
          return@withContext null
        }
        val data = JSONObject(body).optJSONArray("data")
        val list = mutableListOf<CatalogueModel>()
        data ?: return@withContext null
        val rawTotal = data.length()
        // TRANSPARENT-COUNT LAW: log the raw upstream count BEFORE the chat
        // classifier filters anything out. Operator can compare 82 (raw) vs
        // 55+ (chat) vs 39 (NVIDIA website Free Endpoint) and see the truth.
        Log.i(TAG, "NIM /v1/models raw upstream returned $rawTotal entries (chat classifier follows)")
        for (i in 0 until data.length()) {
          val m = data.optJSONObject(i) ?: continue
          val advertisedName = sequenceOf(
            m.optString("name"),
            m.optString("display_name"),
            m.optString("model"),
            m.optString("model_name"),
            m.optString("slug"),
            m.optJSONObject("function")?.optString("name").orEmpty()
          ).firstOrNull { it.isNotBlank() && it.contains('/') }.orEmpty()
          val rawApiId = m.optString("id")
          // Block bare NVCF function UUIDs before resolution — the resolved name
          // from a UUID raw entry still points to a dead NVCF endpoint.
          if (rawApiId.isBlank() || UUID_MODEL_ID.matches(rawApiId)) continue
          val id = callableNimModelId(rawApiId, advertisedName)
          // Internal NVCF function UUIDs are not legal model values for
          // /v1/chat/completions. Never advertise them as healthy chat models.
          if (id.isBlank() || UUID_MODEL_ID.matches(id)) continue
          // FREE-ONLY CHAT LAW: NIM's /v1/models lists every hosted endpoint —
          // embedders, rerankers, guards, translation, video-gen, driving-
          // perception, audio models, AND code-completion models. Only genuine
          // text-chat endpoints may enter the selector pool. Token-aware so
          // 'diffusiongemma' (chat) survives while cosmos/video-gen and other
          // non-chat families die. Code models (starcoder/bigcode/*-coder/
          // *-completion) MUST be excluded — they 404 on /v1/chat/completions
          // and were the source of the NIM HTTP 404 death spiral.
          if (!isNimChatEndpoint(id)) continue
          // EDITORIAL-FREE-ENDPOINT LAW (operator 2026-09-02): if the
          // build.nvidia.com Free Endpoint fetch succeeded, keep only models
          // whose bare name (id after the last "/") is in the editorial
          // list. This produces the exact set the build.nvidia.com/models
          // page shows for the Free Endpoint filter (page 1). If the
          // fetch failed or returned empty, fall back to the chat-classified
          // set so the catalogue is never empty due to a transient web
          // hiccup.
          val bareName = id.substringAfterLast('/')
          if (freeEndpointNames.isNotEmpty() && bareName !in freeEndpointNames) continue
          list.add(
            CatalogueModel(
              id = id,
              name = id.substringAfterLast('/'),
              provider = "nvidia",
              providerType = ProviderType.GATEWAY,
              sourceProvider = "nim",
              description = "NVIDIA NIM · live catalog",
              contextLength = 0,
              // All models from integrate.api.nvidia.com/v1/models are on the free
              // endpoint. Namespace does not determine price — the endpoint does.
              isFree = true,
              isToolCapable = false,
              isVisionCapable = false,
              isReasoningCapable = false,
              modelClass = "chat",
              avgLatencyMs = 0,
              healthStatus = "HEALTHY",
              pricingPrompt = 0.0,
              pricingCompletion = 0.0,
              isQualifiedFree = true,
              configured = true,
              available = true,
              discoveredAtMs = System.currentTimeMillis(),
              endpointSource = "live_catalog",
              vaultKeyName = ProviderSource.NIM.vaultKey
            )
          )
        }
        list
      }
      if (models == null) return 0
      val rawTotal = models.size  // best-effort: the inner withContext's rawTotal is gone after return
      _nimCatalogue.value = models
      // TRANSPARENT-COUNT LAW (operator 2026-09-02): log the chat-classified
      // count, and the build.nvidia.com Free Endpoint editorial intersection
      // so the operator sees the truth. The raw upstream count is logged
      // inside the withContext block before the chat filter runs. The
      // editorial "Free Endpoint" list is the same set the website shows
      // for that filter (page 1 only — pages 2-5 are client-side rendered).
      Log.i(TAG, "NIM catalogue refreshed from live /v1/models: chat=${models.size} callable models (after classifier ∩ build.nvidia.com Free Endpoint editorial filter)")
      Log.i(TAG, "NIM live chat ids=${models.joinToString { it.id }}")
      // Telemetry: record the routing decision so it's queryable later.
      com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
        router = "ProviderRouter",
        kind = "nim_catalogue_refresh",
        decision = "loaded_chat_models",
        success = true,
        durationMs = 0L,
        sessionId = "",
        input = "freeEndpointNames.size=${freeEndpointNames.size}",
        output = "raw=$rawTotal chat=${models.size} ids=${models.joinToString { it.id }}"
      )
      models.size
    } catch (e: Exception) {
      // e.message can be null (e.g. UnknownHostException with no message) — log class name so it's never just "failed: null"
      Log.e(TAG, "NIM catalogue refresh failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
      0
    }
  }

  /**
   * Generic live catalogue refresh for the OpenAI-compatible free gateways
   * (Groq, Cerebras, Google AI Studio, Cloudflare Workers AI — operator
   * order 2026-09-01). GETs {base}/models with the lane's vault key and
   * maps ids into namespaced catalogue entries ("groq/<native-id>", …) so
   * cross-gateway id collisions can never alias a candidate. Handles both
   * the {data:[…]} (Groq/Cerebras/Google) and {result:[…]} (Cloudflare v4)
   * response shapes. Chat-only filter keeps embedders/audio/image/video
   * endpoints out of the pool.
   */
  suspend fun refreshGatewayCatalogue(source: ProviderSource): Int {
    val apiKey = vault.retrieveSecret(source.vaultKey)
    if (apiKey.isNullOrBlank()) {
      Log.w(TAG, "${source.name} catalogue refresh skipped: ${source.vaultKey} missing")
      return 0
    }
    val laneTag = when (source) {
      ProviderSource.NIM -> "nvidia"
      ProviderSource.GOOGLE_AI -> "googleai"
      else -> source.name.lowercase().split(" ")[0].replace("(", "").replace(")", "")
    }
    val base = if (source == ProviderSource.CLOUDFLARE) {
      val accountId = vault.retrieveSecret("CLOUDFLARE_ACCOUNT_ID")
      if (accountId.isNullOrBlank()) {
        Log.w(TAG, "Cloudflare catalogue refresh skipped: CLOUDFLARE_ACCOUNT_ID missing")
        return 0
      }
      // Chat inference uses the OpenAI-compat /ai/v1 surface, but its
      // /models listing answers HTTP 405 on this account. The documented
      // v4 REST listing is GET /accounts/{id}/ai/models/search, which
      // returns the {result:[…]} shape the parser below already handles.
      "${source.baseUrl}/accounts/$accountId/ai"
    } else source.baseUrl
    val listUrl = if (source == ProviderSource.CLOUDFLARE) "$base/models/search" else "$base/models"
    return try {
      val models = withContext(Dispatchers.IO) {
        val req = Request.Builder()
          .url(listUrl)
          .addHeader("Authorization", "Bearer $apiKey")
          .get()
        val resp = httpClient.newCall(req.build()).execute()
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          Log.e(TAG, "${source.name} models HTTP ${resp.code}")
          return@withContext null
        }
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: root.optJSONArray("result") ?: return@withContext null
        val list = mutableListOf<CatalogueModel>()
        for (i in 0 until arr.length()) {
          val m = arr.optJSONObject(i) ?: continue
          // ID-FIELD LAW (2026-09-02 live fix): Cloudflare v4 /models/search
          // returns id=<catalog UUID> and name=@cf/<slug> — sending the UUID
          // produced "No such model eed32bc1-…" on every attempt. For
          // Cloudflare the slug lives in `name`; other gateways keep id-first
          // (their `name` is a display label). A UUID-shaped id is never legal.
          var nativeId = if (source == ProviderSource.CLOUDFLARE) {
            m.optString("name").ifBlank { m.optString("id") }
          } else {
            m.optString("id").ifBlank { m.optString("name") }
          }
          if (UUID_MODEL_ID.matches(nativeId)) nativeId = m.optString("name")
          if (nativeId.isBlank() || UUID_MODEL_ID.matches(nativeId)) continue
          if (!isGatewayChatModelId(nativeId)) continue
          // TASK-METADATA LAW (2026-09-02 live fix): Cloudflare v4 search items
          // carry a live `task.name`. Sending a non-text-generation model to
          // /ai/v1/chat/completions fails with a native-schema error
          // ("required properties at '/audio'…"). Trust the provider's own
          // task metadata over filename-token guessing when it exists.
          if (source == ProviderSource.CLOUDFLARE) {
            val taskName = m.optJSONObject("task")?.optString("name").orEmpty().lowercase()
            if (taskName.isNotBlank() && !taskName.contains("text generation")) continue
          }
          list.add(
            CatalogueModel(
              id = "$laneTag/$nativeId",
              name = nativeId.substringAfterLast('/'),
              provider = laneTag,
              providerType = ProviderType.GATEWAY,
              sourceProvider = laneTag,
              description = "${source.name} · live catalog",
              contextLength = 0,
              isFree = true,
              isToolCapable = false,
              isVisionCapable = false,
              isReasoningCapable = false,
              modelClass = "chat",
              avgLatencyMs = 0,
              healthStatus = "HEALTHY",
              pricingPrompt = 0.0,
              pricingCompletion = 0.0,
              isQualifiedFree = true,
              configured = true,
              available = true,
              discoveredAtMs = System.currentTimeMillis(),
              endpointSource = "live_catalog",
              vaultKeyName = source.vaultKey
            )
          )
        }
        list
      }
      if (models == null) return 0
      when (source) {
        ProviderSource.GROQ -> _groqCatalogue.value = models
        ProviderSource.CEREBRAS -> _cerebrasCatalogue.value = models
        ProviderSource.GOOGLE_AI -> _googleAiCatalogue.value = models
        ProviderSource.CLOUDFLARE -> _cloudflareCatalogue.value = models
        else -> return 0
      }
      Log.i(TAG, "${source.name} catalogue refreshed: ${models.size} chat models")
      models.size
    } catch (e: Exception) {
      Log.e(TAG, "${source.name} catalogue refresh failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
      0
    }
  }

  /**
   * Cost estimator. Pricing is per-million tokens; we use a coarse word-count
   * approximation when the upstream usage payload doesn't carry a real total.
   * Returns null for free lanes so the receipt stays clean.
   */
  private fun computeCostCents(modelId: String, tokens: Int, contentChars: Int): Double? {
    val lookup = (queryAllFreeGateways() + queryAllDirect())
      .firstOrNull { it.id == modelId }
    if (lookup == null || lookup.isFree) return null
    val approxTokens = if (tokens > 0) tokens else (contentChars / 4).coerceAtLeast(1)
    val promptTokens = (approxTokens * 0.6).toInt().coerceAtLeast(1)
    val completionTokens = (approxTokens - promptTokens).coerceAtLeast(1)
    val dollars = (promptTokens * lookup.pricingPrompt + completionTokens * lookup.pricingCompletion) / 1_000_000.0
    return dollars * 100.0
  }

  /** CONTINUITY LAW: system → full prior exchange chain → current user prompt. */
  private fun buildMessagesArray(
    systemInstruction: String,
    conversationHistory: List<Pair<String, String>>,
    prompt: String
  ): JSONArray = JSONArray().apply {
    put(JSONObject().apply {
      put("role", "system")
      put("content", systemInstruction)
    })
    conversationHistory.forEach { (role, content) ->
      // Stored legacy turns have contained display labels/typos (for example
      // "yoo") in the role field. Provider APIs accept only their protocol
      // enum; preserve the content but normalize any unknown human-side role
      // to user so one corrupt historical row cannot poison every new turn.
      val wireRole = when (role.trim().lowercase()) {
        "assistant" -> "assistant"
        "tool" -> "tool"
        "function" -> "function"
        "developer" -> "developer"
        "system" -> "system"
        else -> "user"
      }
      put(JSONObject().apply {
        put("role", wireRole)
        put("content", content)
      })
    }
    put(JSONObject().apply {
      put("role", "user")
      put("content", prompt)
    })
  }

  // ── FUNCTION-CALLING WIRE (2026-08-26) ───────────────────────────────
  // When tools wire is enabled, ProviderRouter attaches an OpenAI-compatible
  // `tools: [...]` array to outgoing requests and parses provider
  // `tool_calls[]` from responses. Backward-compatible: missing fields are
  // ignored, plain text replies still flow through.

  /** Wire-tools enable flag (mirrored from HomeRuntimeBridge). Default off. */
  @Volatile
  var toolsWireEnabled: Boolean = false

  /** Last set of tool descriptors serialized onto the wire (for introspection). */
  @Volatile
  var lastWireTools: List<com.example.core.model.ToolDescriptor> = emptyList()

  /**
   * Tool descriptors to attach to the next outgoing request. Set by the
   * caller before each turn; cleared after the response lands.
   */
  @Volatile
  var pendingTools: List<com.example.core.model.ToolDescriptor> = emptyList()

  @Volatile
  var pendingToolChoice: String = "auto"

  /**
   * Parse tool_calls from a provider response JSON. Returns empty list
   * when no tool_calls are present — never throws, never crashes the chat
   * loop. Backward-compatible: providers that don't support function
   * calling simply omit the field.
   */
  private fun parseToolCalls(json: JSONObject): List<com.example.core.runtime.CanonicalToolCall> {
    val raw: org.json.JSONArray? = json.optJSONArray("tool_calls")
      ?: run {
        val choices = json.optJSONArray("choices") ?: return@run null
        if (choices.length() == 0) return@run null
        val first = choices.optJSONObject(0) ?: return@run null
        val message = first.optJSONObject("message") ?: return@run null
        message.optJSONArray("tool_calls")
      }
    if (raw == null) return emptyList()
    // Reverse the provider-safe function-name adapter before canonical parse.
    // Match only against tools actually offered on this request, so an
    // arbitrary model string can never mint a registry name by substitution.
    val byWireName = pendingTools.associateBy { canonicalToolWireName(it.name) }
    val restored = JSONArray()
    for (i in 0 until raw.length()) {
      val original = raw.optJSONObject(i) ?: continue
      val copy = JSONObject(original.toString())
      val fn = copy.optJSONObject("function")
      val wireName = fn?.optString("name").orEmpty()
      byWireName[wireName]?.let { descriptor -> fn?.put("name", descriptor.name) }
      restored.put(copy)
    }
    return com.example.core.runtime.CanonicalToolCall.parseArray(restored, "openai_compatible")
  }

  private suspend fun callNvidiaNim(
    prompt: String,
    modelName: String,
    systemInstruction: String,
    conversationHistory: List<Pair<String, String>> = emptyList()
  ): ProviderExecutionResult {
    val startTime = System.currentTimeMillis()
    // Catalogue IDs are namespaced ("nvidia/moonshotai/kimi-k3") — strip the
    // lane prefix; the bare id is what NIM expects on the wire. If what
    // remains is a bare NVCF function UUID, resolve it to the catalogue's
    // advertised name; unresolvable UUIDs fail fast (they 404 as "Function
    // not found for account" and only waste a rotation slot).
    val apiKey = vault.retrieveSecret("NVIDIA_NIM_API_KEY")
    if (apiKey.isNullOrBlank()) {
      return ProviderExecutionResult(
        content = "", providerModel = modelName, tokenCount = 0, latencyMs = 0,
        errorMessage = "401 Unauthorized: NVIDIA_NIM_API_KEY missing in KeystoreVault"
      )
    }
    return try {
      val wireModel = modelName.removePrefix("nvidia/").let { bare ->
        if (UUID_MODEL_ID.matches(bare)) {
          val resolved = _nimCatalogue.value.firstOrNull { it.id == modelName }?.name.orEmpty()
          if (resolved.isBlank() || UUID_MODEL_ID.matches(resolved)) {
            return ProviderExecutionResult(
              content = "", providerModel = modelName, tokenCount = 0, latencyMs = 0,
              errorMessage = "400 Bad Request: NIM function UUID '$bare' not invocable (stale NVCF id) — model_not_found"
            )
          }
          resolved
        } else bare
      }
      val payload = JSONObject().apply {
        put("model", wireModel)
        put("messages", buildMessagesArray(systemInstruction, conversationHistory, prompt))
        if (pendingTools.any { it.name == "android.file.write" }) put("max_tokens", 4096)
        if (toolsWireEnabled && pendingTools.isNotEmpty()) {
          put("tools", canonicalToolsToWire(pendingTools))
          put("tool_choice", pendingToolChoice)
          lastWireTools = pendingTools
        }
      }
      val request = Request.Builder()
        .url("https://integrate.api.nvidia.com/v1/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()
      val response = httpClient.newCall(request).execute()
      android.util.Log.i("VoiceTiming", "FIRST_MODEL_TOKEN provider=nim ts=${System.currentTimeMillis()}")
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val errorMsg = "NIM HTTP ${response.code}: $body"
        Log.e(TAG, errorMsg)
        val lat = System.currentTimeMillis() - startTime
        persistDispatchReceipt(
          providerLabel = "nim",
          modelIdUsed = modelName,
          latencyMs = lat,
          routingReason = errorMsg,
          fallbackTrace = emptyList(),
          errorCode = errorMsg
        )
        return ProviderExecutionResult(
          content = "", providerModel = modelName, tokenCount = 0,
          latencyMs = lat, errorMessage = errorMsg, statusCode = response.code
        )
      }
      val json = JSONObject(body)
      val (text, reasoning) = parseChoiceMessage(json)
      val usage = json.optJSONObject("usage")
      val lat = System.currentTimeMillis() - startTime
      persistDispatchReceipt(
        providerLabel = "nim",
        modelIdUsed = modelName,
        latencyMs = lat,
        routingReason = "$modelName chat completion",
        fallbackTrace = emptyList(),
        errorCode = null
      )
      ProviderExecutionResult(
        content = text,
        providerModel = modelName,
        tokenCount = usage?.optInt("total_tokens") ?: (text.split(" ").size + prompt.split(" ").size),
        latencyMs = lat,
        toolCalls = parseToolCalls(json),
        reasoning = reasoning
      )
    } catch (e: Exception) {
      val lat = System.currentTimeMillis() - startTime
      persistDispatchReceipt(
        providerLabel = "nim",
        modelIdUsed = modelName,
        latencyMs = lat,
        routingReason = "NIM exception: ${e.message}",
        fallbackTrace = emptyList(),
        errorCode = "EXECUTOR_ERROR"
      )
      ProviderExecutionResult(
        content = "", providerModel = modelName, tokenCount = 0,
        latencyMs = lat,
        errorMessage = "NIM Error: ${e.message}"
      )
    }
  }

  /**
   * OpenRouter API Call Execution
   */
  private suspend fun callOpenRouter(
    prompt: String,
    modelName: String,
    systemInstruction: String,
    conversationHistory: List<Pair<String, String>> = emptyList()
  ): ProviderExecutionResult {
    val startTime = System.currentTimeMillis()
    // `openrouter/free` is itself the canonical model id. Removing its prefix
    // produced the ambiguous id `free`. Concrete catalogue ids such as
    // `openai/gpt-oss-120b:free` are already wire-ready.
    val actualModel = if (modelName == "openrouter/free") modelName
      else if (modelName.startsWith("openrouter/")) modelName.removePrefix("openrouter/")
      else modelName
    val apiKey = vault.retrieveSecret("OPENROUTER_API_KEY")

    // Fail closed before HTTP. A free selection may only call an explicit
    // :free model or OpenRouter's documented zero-cost free router. Stale
    // metadata, aliases and paid ids never get a chance to spend balance.
    val strictFreeId = actualModel == "openrouter/free" || actualModel.endsWith(":free")
    if (!strictFreeId) {
      return ProviderExecutionResult(
        content = "", providerModel = "openrouter/$actualModel", tokenCount = 0, latencyMs = 0,
        errorMessage = "PAID_ROUTE_BLOCKED: OpenRouter model '$actualModel' is not explicitly free"
      )
    }

    if (apiKey.isNullOrBlank() && !actualModel.contains(":free")) {
      return ProviderExecutionResult(
        content = "",
        providerModel = "openrouter/$actualModel",
        tokenCount = 0,
        latencyMs = 0,
        errorMessage = "401 Unauthorized: OPENROUTER_API_KEY missing in KeystoreVault"
      )
    }

    return try {
      val payload = JSONObject().apply {
        put("model", actualModel)
        put("messages", buildMessagesArray(systemInstruction, conversationHistory, prompt))
        if (pendingTools.any { it.name == "android.file.write" }) put("max_tokens", 4096)
        if (toolsWireEnabled && pendingTools.isNotEmpty()) {
          put("tools", canonicalToolsToWire(pendingTools))
          put("tool_choice", pendingToolChoice)
          lastWireTools = pendingTools
        }
      }

      val requestBuilder = Request.Builder()
        .url("https://openrouter.ai/api/v1/chat/completions")
        .addHeader("HTTP-Referer", "https://purpclaw.os")
        .addHeader("X-Title", "PurpClaw Sovereign OS")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

      if (!apiKey.isNullOrBlank()) {
        requestBuilder.addHeader("Authorization", "Bearer $apiKey")
      }

      val response = httpClient.newCall(requestBuilder.build()).execute()
      android.util.Log.i("VoiceTiming", "FIRST_MODEL_TOKEN provider=openrouter ts=${System.currentTimeMillis()}")
      val body = response.body?.string().orEmpty()

      if (!response.isSuccessful) {
        val errorMsg = "OpenRouter HTTP ${response.code}: $body"
        Log.e(TAG, errorMsg)
        val lat = System.currentTimeMillis() - startTime
        persistDispatchReceipt(
          providerLabel = "openrouter",
          modelIdUsed = "openrouter/$actualModel",
          latencyMs = lat,
          routingReason = errorMsg,
          fallbackTrace = emptyList(),
          errorCode = errorMsg
        )
        return ProviderExecutionResult(
          content = "",
          providerModel = "openrouter/$actualModel",
          tokenCount = 0,
          latencyMs = lat,
          errorMessage = errorMsg,
          statusCode = response.code
        )
      }

      val json = JSONObject(body)
      val choices = json.optJSONArray("choices")
      val text = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
      val usage = json.optJSONObject("usage")
      val totalTokens = usage?.optInt("total_tokens") ?: (text.split(" ").size + prompt.split(" ").size)
      val lat = System.currentTimeMillis() - startTime
      persistDispatchReceipt(
        providerLabel = "openrouter",
        modelIdUsed = "openrouter/$actualModel",
        latencyMs = lat,
        routingReason = "openrouter/$actualModel chat completion",
        fallbackTrace = emptyList(),
        errorCode = null
      )
      ProviderExecutionResult(
        content = text,
        providerModel = "openrouter/$actualModel",
        tokenCount = totalTokens,
        latencyMs = lat,
        toolCalls = parseToolCalls(json)
      )
    } catch (e: Exception) {
      Log.e(TAG, "OpenRouter call failed: ${e.message}", e)
      val lat = System.currentTimeMillis() - startTime
      persistDispatchReceipt(
        providerLabel = "openrouter",
        modelIdUsed = "openrouter/$actualModel",
        latencyMs = lat,
        routingReason = "OpenRouter exception: ${e.message}",
        fallbackTrace = emptyList(),
        errorCode = "EXECUTOR_ERROR"
      )
      ProviderExecutionResult(
        content = "",
        providerModel = "openrouter/$actualModel",
        tokenCount = 0,
        latencyMs = lat,
        errorMessage = "Network Exception: ${e.message}"
      )
    }
  }

  /**
   * MiniMax Text API Call Execution
   */
  suspend fun callMiniMaxText(
    prompt: String,
    modelName: String = "MiniMax-M2.7",
    systemInstruction: String = "",
    conversationHistory: List<Pair<String, String>> = emptyList()
  ): ProviderExecutionResult {
    val startTime = System.currentTimeMillis()
    val apiKey = vault.retrieveSecret("MINIMAX_API_KEY")
      ?: return ProviderExecutionResult(
        content = "",
        providerModel = "minimax/text",
        tokenCount = 0,
        latencyMs = 0,
        errorMessage = "401 Unauthorized: MINIMAX_API_KEY missing in KeystoreVault"
      )

    val actualModel = if (modelName.startsWith("minimax/")) modelName.removePrefix("minimax/") else modelName

    return try {
      val payload = JSONObject().apply {
        put("model", actualModel)
        // MiniMax native subscription (api.minimax.io) speaks the OpenAI
        // role/content message shape. Legacy sender_type/BOT payload belonged
        // to the retired api.minimax.chat lane.
        put("messages", JSONArray().apply {
          if (systemInstruction.isNotBlank()) {
            put(JSONObject().apply { put("role", "system"); put("content", systemInstruction) })
          }
          conversationHistory.forEach { (role, content) ->
            put(JSONObject().apply { put("role", role); put("content", content) })
          }
          put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        })
        // FUNCTION-CALLING WIRE (parity with NIM/OpenAI-compatible lanes).
        // BUG 2026-09-02: this lane never attached `tools`, so MiniMax — the
        // owner-first route — could only answer WORK creation prompts in
        // prose, and every build turn died with MODEL_DID_NOT_EMIT_REQUIRED_
        // TOOL_CALL. The endpoint is OpenAI-compatible; M-series support
        // function calling. Same envelope, same guards as the other lanes.
        if (pendingTools.any { it.name == "android.file.write" }) put("max_tokens", 4096)
        if (toolsWireEnabled && pendingTools.isNotEmpty()) {
          put("tools", canonicalToolsToWire(pendingTools))
          put("tool_choice", pendingToolChoice)
          lastWireTools = pendingTools
        }
      }

      // FIX 2026-09-05: /v1/text/chatcompletion_v2 is legacy and only accepts M2-her.
      // M-series (MiniMax-M3, MiniMax-M2.7) require the OpenAI-compatible endpoint.
      // WORK artifact turns (tools envelope with android.file.write) generate
      // up to 4096 tokens of HTML — the shared 18s callTimeout killed them
      // mid-generation. Extend the deadline only for those tool-bearing calls.
      val artifactClient = if (pendingTools.any { it.name == "android.file.write" }) {
        httpClient.newBuilder()
          .callTimeout(120, TimeUnit.SECONDS)
          .readTimeout(120, TimeUnit.SECONDS)
          .build()
      } else httpClient
      val request = Request.Builder()
        .url("https://api.minimax.io/v1/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

      val response = artifactClient.newCall(request).execute()
      val body = response.body?.string().orEmpty()

      if (!response.isSuccessful) {
        val errorMsg = "MiniMax Text HTTP ${response.code}: $body"
        Log.e(TAG, errorMsg)
        return ProviderExecutionResult(
          content = "",
          providerModel = "minimax/$actualModel",
          tokenCount = 0,
          latencyMs = System.currentTimeMillis() - startTime,
          errorMessage = errorMsg
        )
      }

      val json = JSONObject(body)
      // FIX 2026-09-05: OpenAI-compatible endpoint returns choices[0].message.content, NOT .text
      val reply = json.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?: json.optString("reply")

      ProviderExecutionResult(
        content = reply,
        providerModel = "minimax/$actualModel",
        tokenCount = reply.split(" ").size + prompt.split(" ").size,
        latencyMs = System.currentTimeMillis() - startTime,
        // FUNCTION-CALLING WIRE: structured tool_calls are first-class on this
        // lane now that the envelope goes out. parseToolCalls maps wire names
        // back to canonical registry names (see byWireName guard).
        toolCalls = parseToolCalls(json)
      )
    } catch (e: Exception) {
      Log.e(TAG, "MiniMax text call failed: ${e.message}", e)
      ProviderExecutionResult(
        content = "",
        providerModel = "minimax/$actualModel",
        tokenCount = 0,
        latencyMs = System.currentTimeMillis() - startTime,
        errorMessage = "Network Exception: ${e.message}"
      )
    }
  }

  suspend fun callMiniMaxTts(text: String, voiceId: String = "male-qn-qingse"): MiniMaxMediaResult = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    val apiKey = vault.retrieveSecret("MINIMAX_API_KEY")
      ?: return@withContext MiniMaxMediaResult(
        taskId = "",
        status = "FAILED",
        rawResponse = "",
        latencyMs = 0,
        errorMessage = "MINIMAX_API_KEY missing in Vault for TTS adapter"
      )

    try {
      val payload = JSONObject().apply {
        put("model", "speech-02-turbo")
        put("text", text)
        put("stream", false)
        put("voice_setting", JSONObject().apply {
          put("voice_id", voiceId)
          put("speed", 1.0)
          put("vol", 1.0)
          put("pitch", 0)
        })
        put("audio_setting", JSONObject().apply {
          put("sample_rate", 32000)
          put("bitrate", 128000)
          put("format", "mp3")
          put("channel", 1)
        })
      }

      val request = Request.Builder()
        .url("https://api.minimax.io/v1/t2a_v2")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

      val response = httpClient.newCall(request).execute()
      val body = response.body?.string().orEmpty()

      if (!response.isSuccessful) {
        return@withContext MiniMaxMediaResult(
          taskId = "",
          status = "HTTP_${response.code}",
          rawResponse = body,
          latencyMs = System.currentTimeMillis() - startTime,
          errorMessage = "MiniMax TTS HTTP ${response.code}: $body"
        )
      }

      val json = JSONObject(body)
      val audioUrl = json.optJSONObject("data")?.optString("audio")
      MiniMaxMediaResult(
        taskId = json.optJSONObject("base_resp")?.optString("status_msg") ?: "tts_success",
        status = "SUCCESS",
        mediaUrl = audioUrl,
        rawResponse = body,
        latencyMs = System.currentTimeMillis() - startTime
      )
    } catch (e: Exception) {
      MiniMaxMediaResult(
        taskId = "",
        status = "EXCEPTION",
        rawResponse = "",
        latencyMs = System.currentTimeMillis() - startTime,
        errorMessage = e.message
      )
    }
  }

  suspend fun callMiniMaxVideoStatus(taskId: String): MiniMaxMediaResult = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    val apiKey = vault.retrieveSecret("MINIMAX_API_KEY")
      ?: return@withContext MiniMaxMediaResult(
        taskId = taskId,
        status = "FAILED",
        rawResponse = "",
        latencyMs = 0,
        errorMessage = "MINIMAX_API_KEY missing in Vault for Video adapter"
      )

    try {
      // FIX 2026-09-05: v2 API uses path param /v2/query/video_generation/{task_id}
      val request = Request.Builder()
        .url("https://api.minimax.io/v2/query/video_generation/$taskId")
        .addHeader("Authorization", "Bearer $apiKey")
        .get()
        .build()

      val response = httpClient.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      val json = JSONObject(body)

      // FIX 2026-09-05: v2 response puts video URL in content[0].url (not top-level file_id)
      val videoUrl = json.optJSONArray("content")
        ?.optJSONObject(0)
        ?.optString("url")
        ?: json.optString("file_id")

      MiniMaxMediaResult(
        taskId = taskId,
        status = json.optString("status", "PROCESSING"),
        mediaUrl = videoUrl,
        rawResponse = body,
        latencyMs = System.currentTimeMillis() - startTime
      )
    } catch (e: Exception) {
      MiniMaxMediaResult(
        taskId = taskId,
        status = "EXCEPTION",
        rawResponse = "",
        latencyMs = System.currentTimeMillis() - startTime,
        errorMessage = e.message
      )
    }
  }

  suspend fun callMiniMaxMusicStatus(taskId: String): MiniMaxMediaResult = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    val apiKey = vault.retrieveSecret("MINIMAX_API_KEY")
      ?: return@withContext MiniMaxMediaResult(
        taskId = taskId,
        status = "FAILED",
        rawResponse = "",
        latencyMs = 0,
        errorMessage = "MINIMAX_API_KEY missing in Vault for Music adapter"
      )

    try {
      val request = Request.Builder()
        .url("https://api.minimax.io/v1/query/music_generation?task_id=$taskId")
        .addHeader("Authorization", "Bearer $apiKey")
        .get()
        .build()

      val response = httpClient.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      val json = JSONObject(body)

      MiniMaxMediaResult(
        taskId = taskId,
        status = json.optString("status", "PROCESSING"),
        mediaUrl = json.optString("audio_url"),
        rawResponse = body,
        latencyMs = System.currentTimeMillis() - startTime
      )
    } catch (e: Exception) {
      MiniMaxMediaResult(
        taskId = taskId,
        status = "EXCEPTION",
        rawResponse = "",
        latencyMs = System.currentTimeMillis() - startTime,
        errorMessage = e.message
      )
    }
  }

  private suspend fun callNoProviderStub(
    prompt: String,
    systemInstruction: String,
    startTime: Long
  ): ProviderExecutionResult {
    // ANTI-FRAUD: this lane has NO real provider behind it (no Gemini key path).
    // It must never fabricate a success reply. Fail honestly so callers fall
    // through to the local Gemma host or surface a truthful error.
    return ProviderExecutionResult(
      content = "",
      providerModel = "unavailable/canned-lane-removed",
      tokenCount = 0,
      latencyMs = System.currentTimeMillis() - startTime,
      isFallback = false,
      errorMessage = "NO_PROVIDER: canned sovereign lane removed — configure OpenRouter/MiniMax key in Settings → AI & Models, or attach Home runtime"
    )
  }

  /**
   * Live Key Validation against OpenRouter Auth Endpoint
   */
  suspend fun validateOpenRouterKey(apiKey: String): Boolean = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext false
    try {
      val request = Request.Builder()
        .url("https://openrouter.ai/api/v1/auth/key")
        .addHeader("Authorization", "Bearer $apiKey")
        .get()
        .build()

      val response = httpClient.newCall(request).execute()
      response.isSuccessful
    } catch (e: Exception) {
      Log.e(TAG, "Error validating OpenRouter key: ${e.message}")
      false
    }
  }

  /**
   * Live Key Validation against MiniMax API
   */
  suspend fun checkMiniMaxInstalledCapabilities() = withContext(Dispatchers.IO) {
    val apiKey = vault.retrieveSecret("MINIMAX_API_KEY")
    if (apiKey.isNullOrBlank()) {
      _miniMaxStatus.value = MiniMaxCapabilityStatus(
        textReasoning = "NOT_INSTALLED",
        speech = "NOT_INSTALLED",
        video = "NOT_INSTALLED",
        music = "NOT_INSTALLED"
      )
      return@withContext
    }

    // Probe text model
    val textResult = callMiniMaxText("ping", "MiniMax-M2.7-highspeed", "")
    val textLive = textResult.errorMessage == null

    _miniMaxStatus.value = MiniMaxCapabilityStatus(
      textReasoning = if (textLive) "LIVE" else "DEGRADED",
      speech = if (textLive) "LIVE" else "NOT_INSTALLED",
      video = if (textLive) "LIVE" else "NOT_INSTALLED",
      music = if (textLive) "LIVE" else "NOT_INSTALLED"
    )
  }

  /**
   * Benchmark a specific model
   */
  suspend fun benchmarkModel(modelId: String): ModelBenchmarkReport = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    val testPrompt = "Return the exact string 'PURPCLAW_BENCHMARK_OK' with no commentary."
    val result = executeSpecificModel(modelId, testPrompt, "", startTime)

    val duration = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
    val tokens = result.tokenCount.coerceAtLeast(10)
    val tps = (tokens.toFloat() / (duration.toFloat() / 1000f))

    ModelBenchmarkReport(
      modelId = modelId,
      success = result.errorMessage == null,
      latencyMs = duration,
      tokensPerSecond = tps,
      outputSample = result.content.take(60),
      error = result.errorMessage
    )
  }

  /**
   * Refresh OpenRouter Catalogue with live models or fallback
   */
  suspend fun refreshOpenRouterCatalogue() = withContext(Dispatchers.IO) {
    val apiKey = vault.retrieveSecret("OPENROUTER_API_KEY")
    try {
      val requestBuilder = Request.Builder()
        .url("https://openrouter.ai/api/v1/models")
        .get()

      if (!apiKey.isNullOrBlank()) {
        requestBuilder.addHeader("Authorization", "Bearer $apiKey")
      }

      val response = httpClient.newCall(requestBuilder.build()).execute()
      val body = response.body?.string()

      if (response.isSuccessful && !body.isNullOrBlank()) {
        val json = JSONObject(body)
        val data = json.optJSONArray("data")
        if (data != null && data.length() > 0) {
          val fetchedModels = mutableListOf<CatalogueModel>()
          for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val id = obj.optString("id")
            val name = obj.optString("name", id)
            val description = obj.optString("description", "")
            val contextLength = obj.optInt("context_length", 32768)
            val pricing = obj.optJSONObject("pricing")
            val promptPrice = pricing?.optDouble("prompt", -1.0) ?: -1.0
            val completionPrice = pricing?.optDouble("completion", -1.0) ?: -1.0
            // Live price data outranks names and suffix folklore. AUTO only
            // admits a route when today's upstream catalogue explicitly says
            // both token prices are zero. Missing/unknown pricing fails closed.
            val isFree = pricing != null && promptPrice == 0.0 && completionPrice == 0.0
            val architecture = obj.optJSONObject("architecture")
            val modality = architecture?.optString("modality", "text->text") ?: "text->text"
            val isVision = modality.contains("image")
            val isReasoning = id.contains("r1") || id.contains("reasoner") || id.contains("thinking")

            val modelClass = when {
              id.contains("embed", ignoreCase = true) -> "embedding"
              id.contains("rerank", ignoreCase = true) -> "reranker"
              id.contains("guard", ignoreCase = true) || id.contains("safety", ignoreCase = true) ||
                id.contains("moderation", ignoreCase = true) || id.contains("classifier", ignoreCase = true) -> "classifier"
              id.contains("tts", ignoreCase = true) ||
                id.startsWith("fish-audio/") || id.startsWith("deepgram/") ||
                modality.contains("text->speech", ignoreCase = true) ||
                modality.contains("text->audio", ignoreCase = true) -> "tts"
              id.contains("flux", ignoreCase = true) || id.contains("diffusion", ignoreCase = true) -> "image_gen"
              else -> "chat"
            }

            val supportedParameters = obj.optJSONArray("supported_parameters")
            val isTool = modelClass == "chat" && supportedParameters != null &&
              (0 until supportedParameters.length()).any {
                supportedParameters.optString(it) == "tools"
              }

            val isQualified = isFree && modelClass == "chat" && contextLength >= 32768
            val exclusionReason = when {
              !isFree -> "Paid model"
              modelClass == "classifier" -> "excluded: classifier"
              modelClass == "embedding" -> "excluded: embedder"
              modelClass == "reranker" -> "excluded: reranker"
              contextLength < 32768 -> "excluded: context < 32K"
              else -> null
            }

            fetchedModels.add(
              CatalogueModel(
                id = id,
                name = name,
                provider = "openrouter",
                providerType = ProviderType.GATEWAY,
                sourceProvider = "openrouter",
                description = description,
                contextLength = contextLength,
                isFree = isFree,
                isToolCapable = isTool,
                isVisionCapable = isVision,
                isReasoningCapable = isReasoning,
                modelClass = modelClass,
                avgLatencyMs = if (isFree) 850L else 420L,
                healthStatus = "HEALTHY",
                pricingPrompt = promptPrice,
                pricingCompletion = completionPrice,
                isQualifiedFree = isQualified,
                exclusionReason = exclusionReason,
                isUserPreferredInAuto = userPreferredModels.contains(id),
                isUserExcludedFromAuto = userExcludedModels.contains(id),
                configured = !apiKey.isNullOrBlank(),
                available = true,
                discoveredAtMs = System.currentTimeMillis(),
                endpointSource = "live_catalog",
                vaultKeyName = ProviderSource.OPENROUTER.vaultKey
              )
            )
          }

          // FREE-ONLY LAW: purge paid lanes entirely — the selector must never
          // see a paid model, not even tagged as excluded.
          _openRouterCatalogue.value = fetchedModels.filter { it.isFree }
          Log.i(TAG, "OpenRouter catalogue refreshed from live /api/v1/models: ${_openRouterCatalogue.value.size} free of ${fetchedModels.size} total")
          Log.i(TAG, "OpenRouter live free ids=${_openRouterCatalogue.value.joinToString { it.id }}")
          return@withContext
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load live OpenRouter catalogue (${e.message}); retaining last live snapshot, never promoting stale seeds")
    }

    // No stale model ids are promoted on refresh failure. The durable UI can
    // still expose openrouter/free as its documented meta-router, but concrete
    // daily models must come from the successful live endpoint response.
  }

  // REMOVED (operator 2026-09-02): initializeDefaultCatalogue() and its 8
  // hardcoded OpenRouter default_seed models. The function only ever set the
  // catalogue to emptyList(), and the hardcoded list was a back-door that
  // violated the NO HARDCODED LISTS law. The live /api/v1/models fetch is
  // the sole authority — empty fetch → empty catalogue, never a fallback.
}
