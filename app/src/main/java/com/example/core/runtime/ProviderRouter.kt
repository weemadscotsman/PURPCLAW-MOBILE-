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
  val toolCalls: List<CanonicalToolCall> = emptyList()
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
  private val appContext: android.content.Context
) {

  companion object {
    private const val TAG = "ProviderRouter"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    // MODEL PIN STICKINESS LAW: manual provider/model pick survives process death.
    private const val PREFS = "purpclaw_routing"
    private const val KEY_PROVIDER = "pinned_provider"
    private const val KEY_MODEL = "pinned_model"
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

    /** Pure classifier used by both live catalogue ingestion and TVG tests. */
    internal fun isNimChatEndpoint(id: String): Boolean {
      val lower = id.lowercase()
      val tokens = lower.split('-', '_', '/').filter { it.isNotBlank() }.toSet()
      val nonChatTokens = setOf(
        "embed", "embedcode", "embedqa", "rerank", "retriev", "nemoretriever",
        "guard", "safety", "moderation", "ocr", "riva", "translate", "tts",
        "asr", "whisper", "clip", "voicechat", "ising", "cosmos", "streampetr",
        "sparsedrive", "bevformer", "paligemma", "synthetic", "vl", "video",
        "audio", "denois", "speech", "starcoder", "bigcode", "coder", "completion"
      )
      val hasNonChatToken = tokens.any { token ->
        nonChatTokens.any { stem -> token == stem || (stem.length > 4 && token.startsWith(stem)) }
      }
      val isDiffusionChat = lower.contains("diffusiongemma")
      return (isDiffusionChat || !lower.contains("diffusion")) &&
        !lower.contains("cosmos") &&
        !hasNonChatToken &&
        !lower.endsWith("-vl")
    }

    /** Pure bounded phone-route selection used by AUTO and unit tests. */
    internal fun buildPhoneCandidates(
      gateways: List<CatalogueModel>,
      requestedModel: String?,
      toolsRequired: Boolean,
      openRouterConfigured: Boolean,
      quarantined: (String) -> Boolean
    ): List<String> {
      fun toolEligible(model: CatalogueModel): Boolean = !toolsRequired ||
        model.isToolCapable ||
        model.id == "openrouter/free" ||
        // NIM's live /models response does not publish a reliable tools flag.
        // Runtime invocation with the tools envelope is the truthful probe;
        // unsupported endpoints fail and rotate instead of disappearing as NONE.
        model.sourceProvider == "nim"

      val live = gateways.filter {
        it.modelClass == "chat" && it.isFree && it.available && it.configured &&
          isCallableAutoModelId(it.id) && !it.isUserExcludedFromAuto &&
          !quarantined(it.id) && toolEligible(it)
      }
      val requestedRecord = live.firstOrNull { it.id == requestedModel }
      val openRouterConcrete = live.filter {
        it.sourceProvider == "openrouter" && it.id != "openrouter/free"
      }.sortedWith(compareByDescending<CatalogueModel> { it.isToolCapable }.thenByDescending { it.contextLength })
      val nimConcrete = live.filter {
        it.sourceProvider == "nvidia" || it.sourceProvider == "nim"
      }.sortedWith(compareByDescending<CatalogueModel> { it.isToolCapable }.thenByDescending { it.contextLength })
      return buildList {
        // AUTO's generic alias is not a real capability-qualified model. In
        // WORK it must not jump ahead of concrete live models that explicitly
        // advertise tool calling; retain it as the final gateway fallback.
        if (requestedRecord != null && !(toolsRequired && requestedRecord.id == "openrouter/free")) {
          add(requestedRecord.id)
        }
        // Reserve space across gateways. A bounded list filled entirely by
        // one provider is not failover; it is six variations of the same
        // outage. Alternate concrete OpenRouter and NIM routes before using
        // the generic free alias.
        repeat(maxOf(openRouterConcrete.size, nimConcrete.size)) { index ->
          openRouterConcrete.getOrNull(index)?.let { add(it.id) }
          nimConcrete.getOrNull(index)?.let { add(it.id) }
        }
        if (openRouterConfigured && !quarantined("openrouter/free")) add("openrouter/free")
      }.distinct().take(6)
    }
  }

  // Restored pin survives app kill/relaunch until operator picks AUTO.
  private val prefs = appContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

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
  fun quarantineModel(id: String) {
    recentFailureQuarantine[id] = System.currentTimeMillis() + QUARANTINE_MS
  }
  fun isQuarantined(id: String): Boolean {
    val until = recentFailureQuarantine[id] ?: return false
    return System.currentTimeMillis() < until
  }
  fun clearQuarantine(id: String) = recentFailureQuarantine.remove(id)

  // STEP 12 (2026-08-27): durable routing-decision ledger. Every dispatch
  // through this router mints a DispatchReceipt and writes it here via the
  // optional recorder setter. Default no-op so old callers keep compiling.
  @Volatile
  var dispatchRecorder: DispatchRecorder? = null

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
    val recorder = dispatchRecorder ?: return receipt.dispatchId
    return try {
      recorder.record(receipt)
    } catch (e: Exception) {
      Log.w(TAG, "DispatchReceipt persist failed: ${e.message}")
      receipt.dispatchId
    }
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
    source: ProviderSource,
    knownChatModelIds: List<String>
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
        // Always seed known chat models so the selector isn't empty even when
        // the upstream /models listing is empty/stale. Tagged as default_seed.
        if (list.isEmpty()) list.addAll(seedDirectProvider(source, knownChatModelIds))
        list
      }
      if (models == null) {
        // Fall back to seeds so the operator has something selectable.
        seedDirectFlow(source, knownChatModelIds)
        return knownChatModelIds.size
      }
      writeDirectFlow(source, models)
      models.size
    } catch (e: Exception) {
      Log.w(TAG, "${source.name} catalogue refresh failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
      seedDirectFlow(source, knownChatModelIds)
      knownChatModelIds.size
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

  private fun seedDirectFlow(source: ProviderSource, knownChatModelIds: List<String>) {
    writeDirectFlow(source, seedDirectProvider(source, knownChatModelIds))
  }

  private fun seedDirectProvider(source: ProviderSource, ids: List<String>): List<CatalogueModel> =
    ids.map { id ->
      CatalogueModel(
        id = "$source/${id}",
        name = id,
        provider = source.name,
        providerType = ProviderType.DIRECT,
        sourceProvider = source.name.lowercase().split(" ")[0].replace("(", "").replace(")", ""),
        description = "${source.name} · default seed (key configured)",
        contextLength = 0,
        isFree = false,
        isToolCapable = true,
        isVisionCapable = false,
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
        endpointSource = "default_seed",
        vaultKeyName = source.vaultKey
      )
    }

  // DIRECT PROVIDER REFRESH ENTRY POINTS — each is a no-op when the key is
  // absent. Call from MainViewModel after vault writes.
  suspend fun refreshKimiCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(
      ProviderSource.KIMI,
      listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-k2-0711-preview", "kimi-latest")
    )
  suspend fun refreshQwenCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(
      ProviderSource.QWEN,
      listOf("qwen-plus", "qwen-turbo", "qwen-max", "qwen-long", "qwen2.5-72b-instruct")
    )
  suspend fun refreshDeepseekCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(
      ProviderSource.DEEPSEEK,
      listOf("deepseek-chat", "deepseek-reasoner", "deepseek-coder")
    )
  suspend fun refreshOpenaiCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(
      ProviderSource.OPENAI,
      listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "o3-mini", "o1", "o1-mini")
    )
  suspend fun refreshZaiCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(
      ProviderSource.ZAI,
      listOf("glm-4.5", "glm-4.5-air", "glm-4.5-flash", "glm-4-plus", "glm-4-flash")
    )
  // LONGCAT (2026-08-29): Meituan OpenAI-compatible provider, direct BYO key.
  // Live production proof (2026-08-29): both model discovery and chat are
  // served below /openai/v1. The docs' bare /v1/models path returned HTML 404;
  // /openai/v1/models returned the authenticated LongCat-2.0 catalogue.
  suspend fun refreshLongcatCatalogue(): Int =
    refreshOpenAiCompatibleCatalogue(
      ProviderSource.LONGCAT,
      listOf("LongCat-2.0")
    )

  /**
   * MiniMax refresh — bespoke because their /v1/models shape differs from
   * OpenAI-compatible. We probe a small allowlist of known chat models
   * (text/general purpose) and seed them as DIRECT + configured.
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
        endpointSource = "default_seed",
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

  // SPEND LAW: rolling counter in cents, persisted by caller (MainViewModel).
  // We expose getters so the UI can show "spent today / cap".
  private var dailySpendCents: Double = 0.0
  private var dailySpendDayKey: String = ""

  /**
   * AUTO FREE POOL: every model the AUTO router is allowed to pick.
   * Strict predicate — providerType=GATEWAY, sourceProvider in
   * {openrouter, nim}, free=true, configured=true. If the operator's
   * OpenRouter key is missing the OpenRouter slice is empty; if NIM key is
   * missing the NIM slice is empty. AUTO NEVER touches a direct provider,
   * no matter what the operator pinned before.
   */
  fun queryAllFreeGateways(): List<CatalogueModel> {
    val orConfigured = !vault.retrieveSecret(ProviderSource.OPENROUTER.vaultKey).isNullOrBlank()
    val nimConfigured = !vault.retrieveSecret(ProviderSource.NIM.vaultKey).isNullOrBlank()
    return buildList {
      if (orConfigured) addAll(_openRouterCatalogue.value)
      if (nimConfigured) addAll(_nimCatalogue.value)
    }.filter {
      it.providerType == ProviderType.GATEWAY &&
        it.isFree &&
        it.configured &&
        it.available &&
        (it.sourceProvider == "openrouter" || it.sourceProvider == "nim")
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
  }.filter { it.configured && it.available }

  /** SPEND LAW: would running modelId right now exceed the operator's cap? */
  fun isSpendAllowed(model: CatalogueModel, estimatedCostCents: Double = 0.0): SpendVerdict {
    val policy = _routingState.value.spendPolicy
    if (model.providerType != ProviderType.DIRECT || model.isFree) {
      // Gateway traffic and direct free lanes never trip the cap.
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
    if (model.providerType != ProviderType.DIRECT || model.isFree) return
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
    initializeDefaultCatalogue()
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
      quarantined = ::isQuarantined
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
        routingMode = "AUTO",
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
      )
    }

    // Attempt chain tracking for canonical receipt
    val attemptRecords = mutableListOf<com.example.core.model.AttemptRecord>()
    var servedProvider: String? = null
    var servedModel: String? = null

    var lastResult: ProviderExecutionResult? = null
    for ((attemptIdx, cand) in candidates.withIndex()) {
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
          routingMode = "AUTO",
          resolvedProvider = cProvider,
          resolvedModel = cand,
          servedProvider = cProvider,
          servedModel = cand,
          fallbackOccurred = attemptIdx > 0,
          fallbackPath = candidates.subList(0, attemptIdx).toList(),
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
        SharedQuotaLedger.FailureClass.AUTH -> com.example.core.model.AttemptOutcome.AUTH_REQUIRED
        SharedQuotaLedger.FailureClass.TIMEOUT -> com.example.core.model.AttemptOutcome.FIRST_TOKEN_TIMEOUT
        SharedQuotaLedger.FailureClass.OTHER -> com.example.core.model.AttemptOutcome.UNKNOWN_FAILURE
      }
      // Provider/credential state is route-local. Record it and advance to a
      // different eligible provider; never kill the whole AUTO turn because
      // one credential hit quota/auth/rate limits.
      if (failureClass in setOf(SharedQuotaLedger.FailureClass.AUTH_MISSING_KEY, SharedQuotaLedger.FailureClass.AUTH_REJECTED, SharedQuotaLedger.FailureClass.AUTH_FORBIDDEN, SharedQuotaLedger.FailureClass.RATE_LIMITED, SharedQuotaLedger.FailureClass.PROVIDER_QUOTA_EXHAUSTED)) {
        attemptRecords.add(com.example.core.model.AttemptRecord(
          attemptIndex = attemptIdx, provider = cProvider, model = cand,
          outcome = fcOutcome, failureClass = failureClass.name, statusCode = res.statusCode,
          startedAtMs = attemptStart, endedAtMs = resEnd,
          latencyMs = res.latencyMs,
          errorDetail = (cErr ?: "failure").take(200)
        ))
        fallbackTrace.add("candidate '$cand' unavailable: ${failureClass.name} — rotating provider")
        continue
      }
      // Transport/dead failure: quarantine + rotate to next healthy route.
      quarantineModel(cand)
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
      routingMode = "AUTO",
      resolvedProvider = servedProvider ?: "none",
      resolvedModel = servedModel ?: "none",
      servedProvider = servedProvider,
      servedModel = servedModel,
      fallbackOccurred = true,
      fallbackPath = candidates.toList(),
      routingReason = "AUTO_ALL_CANDIDATES_EXHAUSTED",
      qualityGateResult = "FAIL",
      attempts = attemptRecords
    )
    return@withContext (lastResult ?: ProviderExecutionResult(
      content = "", providerModel = "none", tokenCount = 0,
      latencyMs = System.currentTimeMillis() - startTime, errorMessage = finalErr
    )).copy(routingReceipt = receipt, latencyMs = System.currentTimeMillis() - startTime)
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
    // GOOGLE PURGE LAW — execute-layer guard: even a pinned/manual google lane refuses.
    if (modelId.startsWith("google/") || modelId.contains("gemini", ignoreCase = true)) {
      return ProviderExecutionResult(
        content = "", providerModel = modelId, tokenCount = 0, latencyMs = 0,
        errorMessage = "Google cloud lanes are purged from this build (local Gemma is download-only, never routed)"
      )
    }
    // Resolve a direct provider by its prefixed id so the manual-only lanes
    // have an executor. AUTO never reaches here for a DIRECT provider.
    val directPrefix = listOf(
      ProviderSource.MINIMAX, ProviderSource.KIMI, ProviderSource.QWEN,
      ProviderSource.DEEPSEEK, ProviderSource.OPENAI, ProviderSource.ZAI,
      ProviderSource.LONGCAT
    ).firstOrNull { modelId.startsWith("${it.name.lowercase().split(" ")[0].replace("(", "").replace(")", "")}/") }
    return when {
      modelId.startsWith("openrouter/") || _openRouterCatalogue.value.any { it.id == modelId && it.provider == "openrouter" } -> {
        callOpenRouter(prompt, modelId, systemInstruction, conversationHistory)
      }
      modelId.startsWith("minimax/") || modelId.contains("abab") -> {
        callMiniMaxText(prompt, modelId, systemInstruction, conversationHistory)
      }
      modelId.startsWith("nvidia/") || _nimCatalogue.value.any { it.id == modelId } -> {
        callNvidiaNim(prompt, modelId, systemInstruction, conversationHistory)
      }
      // Direct-provider dispatch (all OpenAI-compatible chat completions).
      directPrefix != null -> {
        callOpenAiCompatible(directPrefix, modelId, prompt, systemInstruction, startTime, conversationHistory)
      }
      modelId.startsWith("local/") -> {
        try {
          val cleanId = modelId.removePrefix("local/")
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
            providerModel = modelId,
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
          content = "", providerModel = modelId, tokenCount = 0,
          latencyMs = System.currentTimeMillis() - startTime,
          errorMessage = "NO_ROUTING_AUTHORITY: '$modelId' is not an emergency-pinned model; core owns routing"
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
    val actualModel = if (modelId.contains("/")) modelId.substringAfter("/") else modelId
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
        .url("${source.baseUrl}/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()
      val response = httpClient.newCall(request).execute()
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
      val text = json.optJSONArray("choices")?.optJSONObject(0)
        ?.optJSONObject("message")?.optString("content").orEmpty()
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
        latencyMs = lat
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
   * NVIDIA NIM — integrate.api.nvidia.com/v1 (OpenAI-compatible, free tier).
   * Auto model selection pulls the live /v1/models catalog into the selector.
   */
  suspend fun refreshNimCatalogue(): Int {
    val apiKey = vault.retrieveSecret("NVIDIA_NIM_API_KEY")
    if (apiKey.isNullOrBlank()) {
      Log.w(TAG, "NIM catalogue refresh skipped: NVIDIA_NIM_API_KEY missing")
      return 0
    }
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
          val id = callableNimModelId(m.optString("id"), advertisedName)
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
          list.add(
            CatalogueModel(
              id = id,
              name = id.substringAfterLast('/'),
              provider = "nvidia",
              providerType = ProviderType.GATEWAY,
              sourceProvider = "nim",
              description = "NVIDIA NIM · live catalog",
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
              vaultKeyName = ProviderSource.NIM.vaultKey
            )
          )
        }
        // The catalogue may contain callable-looking aliases which later map
        // to account-private NVCF UUIDs. Always merge NVIDIA's bounded,
        // documented chat ids so AUTO has a real public endpoint after those
        // dynamic entries fail. Live discovery still supplies the wider list.
        listOf(
          "nvidia/nemotron-3.5-lightning-30b-a3b",
          "nvidia/nemotron-3-super-120b-a12b",
          "deepseek-ai/deepseek-v4-flash-0731",
          "moonshotai/kimi-k2-instruct"
        ).filterNot { fallbackId -> list.any { it.id == fallbackId } }
          .forEach { id ->
            list.add(
              CatalogueModel(
                id = id,
                name = id.substringAfterLast('/'),
                provider = "nvidia",
                providerType = ProviderType.GATEWAY,
                sourceProvider = "nim",
                description = "NVIDIA NIM · documented chat fallback",
                contextLength = 0,
                isFree = true,
                isToolCapable = false,
                isVisionCapable = false,
                isReasoningCapable = id.contains("thinking") || id.contains("deepseek"),
                modelClass = "chat",
                avgLatencyMs = 0,
                healthStatus = "UNPROBED",
                pricingPrompt = 0.0,
                pricingCompletion = 0.0,
                isQualifiedFree = true,
                configured = true,
                available = true,
                discoveredAtMs = System.currentTimeMillis(),
                endpointSource = "official_docs_fallback",
                vaultKeyName = ProviderSource.NIM.vaultKey
              )
            )
          }
        list
      }
      if (models == null) return 0
      _nimCatalogue.value = models
      Log.i(TAG, "NIM catalogue refreshed: ${models.size} models")
      models.size
    } catch (e: Exception) {
      // e.message can be null (e.g. UnknownHostException with no message) — log class name so it's never just "failed: null"
      Log.e(TAG, "NIM catalogue refresh failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
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
    val actualModel = if (modelName.startsWith("nvidia/")) modelName.removePrefix("nvidia/") else modelName
    val apiKey = vault.retrieveSecret("NVIDIA_NIM_API_KEY")
    if (apiKey.isNullOrBlank()) {
      return ProviderExecutionResult(
        content = "", providerModel = "nvidia/$actualModel", tokenCount = 0, latencyMs = 0,
        errorMessage = "401 Unauthorized: NVIDIA_NIM_API_KEY missing in KeystoreVault"
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
      val request = Request.Builder()
        .url("https://integrate.api.nvidia.com/v1/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()
      val response = httpClient.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val errorMsg = "NIM HTTP ${response.code}: $body"
        Log.e(TAG, errorMsg)
        val lat = System.currentTimeMillis() - startTime
        persistDispatchReceipt(
          providerLabel = "nim",
          modelIdUsed = "nvidia/$actualModel",
          latencyMs = lat,
          routingReason = errorMsg,
          fallbackTrace = emptyList(),
          errorCode = errorMsg
        )
        return ProviderExecutionResult(
          content = "", providerModel = "nvidia/$actualModel", tokenCount = 0,
          latencyMs = lat, errorMessage = errorMsg, statusCode = response.code
        )
      }
      val json = JSONObject(body)
      val text = json.optJSONArray("choices")?.optJSONObject(0)
        ?.optJSONObject("message")?.optString("content").orEmpty()
      val usage = json.optJSONObject("usage")
      val lat = System.currentTimeMillis() - startTime
      persistDispatchReceipt(
        providerLabel = "nim",
        modelIdUsed = "nvidia/$actualModel",
        latencyMs = lat,
        routingReason = "nvidia/$actualModel chat completion",
        fallbackTrace = emptyList(),
        errorCode = null
      )
      ProviderExecutionResult(
        content = text,
        providerModel = "nvidia/$actualModel",
        tokenCount = usage?.optInt("total_tokens") ?: (text.split(" ").size + prompt.split(" ").size),
        latencyMs = lat,
        toolCalls = parseToolCalls(json)
      )
    } catch (e: Exception) {
      val lat = System.currentTimeMillis() - startTime
      persistDispatchReceipt(
        providerLabel = "nim",
        modelIdUsed = "nvidia/$actualModel",
        latencyMs = lat,
        routingReason = "NIM exception: ${e.message}",
        fallbackTrace = emptyList(),
        errorCode = "EXECUTOR_ERROR"
      )
      ProviderExecutionResult(
        content = "", providerModel = "nvidia/$actualModel", tokenCount = 0,
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
    val actualModel = if (modelName.startsWith("openrouter/")) modelName.removePrefix("openrouter/") else modelName
    val apiKey = vault.retrieveSecret("OPENROUTER_API_KEY")

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
      }

      val request = Request.Builder()
        .url("https://api.minimax.io/v1/text/chatcompletion_v2")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

      val response = httpClient.newCall(request).execute()
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
      val reply = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("text")
        ?: json.optString("reply")

      ProviderExecutionResult(
        content = reply,
        providerModel = "minimax/$actualModel",
        tokenCount = reply.split(" ").size + prompt.split(" ").size,
        latencyMs = System.currentTimeMillis() - startTime
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
        put("model", "speech-01-turbo")
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
      val request = Request.Builder()
        .url("https://api.minimax.io/v1/query/video_generation?task_id=$taskId")
        .addHeader("Authorization", "Bearer $apiKey")
        .get()
        .build()

      val response = httpClient.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      val json = JSONObject(body)

      MiniMaxMediaResult(
        taskId = taskId,
        status = json.optString("status", "PROCESSING"),
        mediaUrl = json.optString("file_id"),
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
            val promptPrice = pricing?.optDouble("prompt", 0.0) ?: 0.0
            val completionPrice = pricing?.optDouble("completion", 0.0) ?: 0.0
            // FREE-ONLY LAW: a model is free ONLY if the id carries :free or
            // BOTH prompt AND completion pricing are exactly 0. A missing
            // pricing object defaults to 0.0 on OpenRouter, so require the
            // :free suffix when pricing is absent — never guess paid → free.
            val isFree = id.endsWith(":free") ||
              (pricing != null && promptPrice == 0.0 && completionPrice == 0.0)
            val architecture = obj.optJSONObject("architecture")
            val modality = architecture?.optString("modality", "text->text") ?: "text->text"
            val isVision = modality.contains("image")
            val isReasoning = id.contains("r1") || id.contains("reasoner") || id.contains("thinking")

            val modelClass = when {
              id.contains("embed", ignoreCase = true) -> "embedding"
              id.contains("rerank", ignoreCase = true) -> "reranker"
              id.contains("guard", ignoreCase = true) || id.contains("moderation", ignoreCase = true) || id.contains("classifier", ignoreCase = true) -> "classifier"
              id.contains("flux") || id.contains("diffusion") -> "image_gen"
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
          Log.i(TAG, "OpenRouter catalogue refreshed: ${_openRouterCatalogue.value.size} free of ${fetchedModels.size} total")
          return@withContext
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load live OpenRouter catalogue (${e.message}), using verified default catalogue.")
    }

    // Refresh default list with current user preferences
    initializeDefaultCatalogue()
  }

  private fun initializeDefaultCatalogue() {
    val defaultList = listOf(
      CatalogueModel(
        id = "deepseek/deepseek-chat-v3-0324:free",
        name = "DeepSeek: V3 0324 (Free)",
        provider = "openrouter",
        providerType = ProviderType.GATEWAY,
        sourceProvider = "openrouter",
        description = "Open-weights MoE generalist, strong tool-calling.",
        contextLength = 163840,
        isFree = true,
        isToolCapable = true,
        isVisionCapable = false,
        isReasoningCapable = false,
        modelClass = "chat",
        avgLatencyMs = 900L,
        healthStatus = "HEALTHY",
        pricingPrompt = 0.0,
        pricingCompletion = 0.0,
        isQualifiedFree = true,
        exclusionReason = null,
        endpointSource = "default_seed",
        vaultKeyName = ProviderSource.OPENROUTER.vaultKey
      ),
      CatalogueModel(
        id = "deepseek/deepseek-r1-0528:free",
        name = "DeepSeek: R1 0528 (Free)",
        provider = "openrouter",
        providerType = ProviderType.GATEWAY,
        sourceProvider = "openrouter",
        description = "Open-weights reasoning model with chain-of-thought.",
        contextLength = 163840,
        isFree = true,
        isToolCapable = true,
        isVisionCapable = false,
        isReasoningCapable = true,
        modelClass = "chat",
        avgLatencyMs = 1800L,
        healthStatus = "HEALTHY",
        pricingPrompt = 0.0,
        pricingCompletion = 0.0,
        isQualifiedFree = true,
        exclusionReason = null,
        endpointSource = "default_seed",
        vaultKeyName = ProviderSource.OPENROUTER.vaultKey
      ),
      CatalogueModel(
        id = "meta-llama/llama-3.3-70b-instruct:free",
        name = "Meta: Llama 3.3 70B Instruct (Free)",
        provider = "openrouter",
        providerType = ProviderType.GATEWAY,
        sourceProvider = "openrouter",
        description = "Flagship open-weights generalist instruct model.",
        contextLength = 131072,
        isFree = true,
        isToolCapable = true,
        isVisionCapable = false,
        isReasoningCapable = false,
        modelClass = "chat",
        avgLatencyMs = 850L,
        healthStatus = "HEALTHY",
        pricingPrompt = 0.0,
        pricingCompletion = 0.0,
        isQualifiedFree = true,
        exclusionReason = null,
        endpointSource = "default_seed",
        vaultKeyName = ProviderSource.OPENROUTER.vaultKey
      ),
      CatalogueModel(
        id = "qwen/qwen-2.5-72b-instruct:free",
        name = "Qwen: Qwen 2.5 72B Instruct (Free)",
        provider = "openrouter",
        providerType = ProviderType.GATEWAY,
        sourceProvider = "openrouter",
        description = "Large open-weights instruct model, strong multilingual.",
        contextLength = 32768,
        isFree = true,
        isToolCapable = true,
        isVisionCapable = false,
        isReasoningCapable = false,
        modelClass = "chat",
        avgLatencyMs = 800L,
        healthStatus = "HEALTHY",
        pricingPrompt = 0.0,
        pricingCompletion = 0.0,
        isQualifiedFree = true,
        exclusionReason = null,
        endpointSource = "default_seed",
        vaultKeyName = ProviderSource.OPENROUTER.vaultKey
      ),
      CatalogueModel(
        id = "mistralai/mistral-small-24b-instruct-2501:free",
        name = "Mistral: Small 24B Instruct 2501 (Free)",
        provider = "openrouter",
        providerType = ProviderType.GATEWAY,
        sourceProvider = "openrouter",
        description = "Efficient mid-size instruct from Mistral.",
        contextLength = 32768,
        isFree = true,
        isToolCapable = true,
        isVisionCapable = false,
        isReasoningCapable = false,
        modelClass = "chat",
        avgLatencyMs = 700L,
        healthStatus = "HEALTHY",
        pricingPrompt = 0.0,
        pricingCompletion = 0.0,
        isQualifiedFree = true,
        exclusionReason = null,
        endpointSource = "default_seed",
        vaultKeyName = ProviderSource.OPENROUTER.vaultKey
      ),
      CatalogueModel(
        id = "google/gemma-3-27b-it:free",
        name = "Google: Gemma 3 27B (Free)",
        provider = "openrouter",
        providerType = ProviderType.GATEWAY,
        sourceProvider = "openrouter",
        description = "Open-weights Gemma 3 instruct, multimodal-capable family.",
        contextLength = 131072,
        isFree = true,
        isToolCapable = true,
        isVisionCapable = false,
        isReasoningCapable = false,
        modelClass = "chat",
        avgLatencyMs = 750L,
        healthStatus = "HEALTHY",
        pricingPrompt = 0.0,
        pricingCompletion = 0.0,
        isQualifiedFree = true,
        exclusionReason = null,
        endpointSource = "default_seed",
        vaultKeyName = ProviderSource.OPENROUTER.vaultKey
      ),
      CatalogueModel(
        id = "microsoft/phi-4:free",
        name = "Microsoft: Phi 4 (Free)",
        provider = "openrouter",
        providerType = ProviderType.GATEWAY,
        sourceProvider = "openrouter",
        description = "Small high-quality reasoning-focused model.",
        contextLength = 16384,
        isFree = true,
        isToolCapable = true,
        isVisionCapable = false,
        isReasoningCapable = false,
        modelClass = "chat",
        avgLatencyMs = 500L,
        healthStatus = "HEALTHY",
        pricingPrompt = 0.0,
        pricingCompletion = 0.0,
        isQualifiedFree = false,
        exclusionReason = "excluded: context < 32K",
        endpointSource = "default_seed",
        vaultKeyName = ProviderSource.OPENROUTER.vaultKey
      ),

      // Verified against live https://openrouter.ai/api/v1/models on 2026-08-26.
      // GEMINI PURGED: no Google cloud lanes, ever — local Gemma lane only.
    )

    _openRouterCatalogue.value = defaultList.map { model ->
      model.copy(
        isUserPreferredInAuto = userPreferredModels.contains(model.id),
        isUserExcludedFromAuto = userExcludedModels.contains(model.id)
      )
    }
  }
}
