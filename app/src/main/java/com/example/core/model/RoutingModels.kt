package com.example.core.model

enum class ModelMode(val label: String, val description: String) {
  AUTO("AUTO", "PurpClaw automatically selects the best model based on capability fit, tools, latency, cost, and health."),
  MANUAL("MANUAL", "Pinned provider and model. Zero silent semantic switch; explicit failure on error.")
}

enum class RoutingProfile(val label: String, val description: String) {
  BALANCED("Balanced", "Optimal balance of intelligence, speed, and cost efficiency."),
  FAST("Fast", "Prioritize lowest latency and rapid time-to-first-token."),
  DEEP("Deep", "High reasoning effort and maximum context window for deep problem solving."),
  FREE_FIRST("Free First", "Prioritize verified qualified free models before paid providers."),
  QUALITY_FIRST("Quality First", "Prioritize top-tier benchmarked reasoning and tool precision."),
  LOCAL_FIRST("Local First", "Prioritize on-device or LAN local host compute for sovereignty.")
}

enum class ReasoningEffort(val label: String) {
  AUTO("Auto"),
  LOW("Low"),
  MEDIUM("Medium"),
  HIGH("High")
}

data class RoutingState(
  val modelMode: ModelMode = ModelMode.AUTO,
  val selectedProvider: String = "AUTO",
  val selectedModel: String = "AUTO",
  // NULL-TRUTHFUL LAW: no fabricated resolution before core mints one.
  val resolvedProvider: String = "none",
  val resolvedModel: String = "none",
  val routingProfile: RoutingProfile = RoutingProfile.FREE_FIRST,
  val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
  val sessionAffinity: Boolean = true,
  val fallbackEnabled: Boolean = true,
  // FREE-ONLY LAW: no paid lanes exist in the catalogue anymore.
  // Kept as a field because routing logic filters on it every pass.
  val freeOnly: Boolean = true,
  val localOnly: Boolean = false,
  val lastRoutingReason: String = "Balanced latency & tool support",
  val lastFallbackPath: List<String> = emptyList(),

  // AUTO Routing Controls
  val preferHealthy: Boolean = true,
  val preferToolCapableWhenRequired: Boolean = true,
  val preferVisionCapableWhenRequired: Boolean = true,
  val preferLongContextWhenRequired: Boolean = true,
  val preferPreviousSessionModel: Boolean = true,
  val avoidClassifiers: Boolean = true,
  val avoidEmbedders: Boolean = true,
  val avoidRerankers: Boolean = true,
  val avoidNonChat: Boolean = true,
  val avoidMalformedOutput: Boolean = true,
  val avoidEmptyResponses: Boolean = true,
  val qualityGateEnabled: Boolean = true,

  // Free Model Routing
  val freeModelsEnabled: Boolean = true,
  val preferFreeModels: Boolean = false,
  val minFreeContextK: Int = 32,
  val freeToolsRequired: String = "Auto",       // Auto, Yes, No
  val freeReasoningRequired: String = "Auto",   // Auto, Yes, No
  val freeVisionRequired: String = "Auto",      // Auto, Yes, No

  // Home Routing
  // Home is an optional compute/tool node, not a prerequisite for chat.
  val useHomeRouting: Boolean = false,
  val preferHomeCompute: Boolean = false,
  val homeProvider: String = "AUTO",
  // ONE ROUTER LAW: resolution is minted by core, never defaulted on-device.
  val homeResolvedModel: String? = null,

  // SPEND LAW (2026-08-26): operator-controlled cap on paid traffic.
  // Default mode OFF → AUTO FREE router never accidentally spends.
  val spendPolicy: SpendPolicy = SpendPolicy()
)

/**
 * Attempt outcome — maps to desktop routing-receipt.js OUTCOMES + classifyAttempt().
 * NULL-TRUTHFUL: UNKNOWN when not classifiable, never a guess.
 */
enum class AttemptOutcome {
  SERVED, AUTH_REQUIRED, RATE_LIMITED, QUOTA_EXHAUSTED, SERVER_ERROR,
  NETWORK_ERROR, FIRST_TOKEN_TIMEOUT, STREAM_STALLED, CONTEXT_OVERFLOW,
  QUALITY_REJECTED, CANCELLED, SKIPPED, UNKNOWN_FAILURE,
  // keep-working fallback path (agent-loop.js stamps this on the first attempt)
  REQUESTED_MODEL_FAILED
}

/**
 * One inference attempt in the failover chain.
 * Mirrors the `attempted[]` entries produced by routing-receipt.js buildReceipt().
 * Every field is null-truthful: null means "not observed", never "unknown-but-fabricated".
 */
data class AttemptRecord(
  val attemptIndex: Int,
  val provider: String? = null,
  val model: String? = null,
  val outcome: AttemptOutcome = AttemptOutcome.UNKNOWN_FAILURE,
  val failureClass: String? = null,        // raw transport/failure-class stamp (e.g. "QUOTA", "SSE_DISCONNECT")
  val statusCode: Int? = null,
  val latencyMs: Long? = null,
  val cooldownMs: Long = 0,                // cooldown applied after this attempt
  val cooldownUntilMs: Long? = null,       // epoch-ms when provider cools down
  val startedAtMs: Long? = null,
  val endedAtMs: Long? = null,
  val skippedReason: String? = null,       // non-null when this attempt was SKIPPED before dispatch
  val errorDetail: String? = null          // human-readable failure detail (no secrets)
)

/**
 * Canonical routing receipt — parity with desktop routing-receipt.js buildReceipt().
 * Core runtime (desktop/mobile bridge) is the authority; Android mirrors for UI.
 */
data class RoutingReceipt(
  val modelMode: ModelMode = ModelMode.AUTO,
  val routingProfile: RoutingProfile = RoutingProfile.BALANCED,
  val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
  val requestedProvider: String = "AUTO",
  val requestedModel: String = "AUTO",
  val resolvedProvider: String = "none",
  val resolvedModel: String = "none",
  val isHomeAttached: Boolean = false,
  val homeResolvedModel: String? = null,
  val routingReason: String = "Qualified candidate selected",
  val fallbackPath: List<String> = emptyList(),
  val sessionAffinityBefore: String? = null,
  val sessionAffinityAfter: String? = null,
  val providerLatencyMs: Long = 0,
  val tokenCount: Int = 0,
  val qualityGateResult: String = "PASS",
  // ROUTE vs EXECUTION: where the turn ran vs. where inference was served.
  // Render-only on Android — core mints these in its route record.
  val executionNode: String? = null,
  val inferenceNode: String? = null,
  // SPEND LAW (2026-08-26): provenance + cost tracking for paid runs.
  // providerType + sourceProvider tell the UI which lane served the turn.
  // costCents is computed from pricingPrompt/Completion × tokens, never guessed.
  // spendMode mirrors the policy that authorised the call (or blocked it).
  val providerType: ProviderType? = null,
  val sourceProvider: String? = null,
  val costCents: Double? = null,
  val spendMode: SpendMode? = null,

  // ── ATTEMPT CHAIN PARITY (routing-receipt.js buildReceipt output) ──────────
  // Canonical contract (2026-08-30): the receipt MUST carry the structured
  // attempt chain so the UI can render "OpenRouter/free (QUOTA) → NIM/starcoder2 (404)
  // → OpenRouter/another_free (SERVED)" instead of a flat error string.
  // A turn belongs to the runtime, not to an individual model invocation.
  val servedProvider: String? = null,     // what actually produced output (may differ from resolved)
  val servedModel: String? = null,        // what actually produced output
  val attempts: List<AttemptRecord> = emptyList(),
  val routingMode: String = "AUTO",       // "MANUAL" | "AUTO" — mirrors desktop routing_mode
  val manualOverrideApplied: String? = null,  // provider id if a manual pin was honored this turn
  val poolId: String? = null,             // scored-pool id (null for MANUAL, 'global' for default AUTO)
  val fallbackPolicy: String = "none",    // "none" | "global-scored" | "keep-working"
  val fallbackOccurred: Boolean = false,  // true when resolved != served (failover evidence)
  val fallbackFromAttempt: Int? = null,   // attempt_index of the failure that triggered fallback
  val fallbackReason: String? = null,     // outcome/failure_class of the triggering attempt
  val manualPin: String? = null,          // the pinned model id (if MANUAL), for UI display
  val schema: String = "purpclaw.routing-receipt/1",
  val turnId: String? = null,
  val sessionId: String? = null,
  val routeId: String? = null,
  val createdAtIso: String? = null
)

data class MiniMaxCapabilityStatus(
  val textReasoning: String = "NOT_INSTALLED", // NOT_INSTALLED, LIVE, DEGRADED
  val speech: String = "NOT_INSTALLED",
  val video: String = "NOT_INSTALLED",
  val music: String = "NOT_INSTALLED"
)

/**
 * PROVENANCE CONTRACT: every model in every catalogue carries these fields.
 * - providerType: GATEWAY (OpenRouter/NIM — multi-tenant free/paid pool) vs
 *   DIRECT (single-vendor BYO-key, always operator-paid when not free).
 * - sourceProvider: canonical upstream id — "openrouter", "nim",
 *   "minimax", "kimi", "qwen", "deepseek", "openai", "z-ai".
 * - free: true ONLY when the live catalogue marks this id as free at fetch time.
 * - configured: true when the vault holds a working API key for sourceProvider.
 *   Unconfigured direct providers stay visible as "Add API key" tiles.
 * - available: live health flag (mirrors healthStatus but here for routing).
 * - discoveredAtMs: when this id was last seen in the live upstream catalogue.
 *   Lets us auto-retire ids that vanish between refreshes (no hard-coded lists).
 * - endpointSource: "live_catalog" (pulled from upstream API just now) or
 *   "default_seed" (boot-time fallback so the selector is never empty).
 * - vaultKeyName: the vault key this provider reads (null for gateways when
 *   the key is optional, populated for every direct provider).
 */
data class CatalogueModel(
  val id: String,
  val name: String,
  val provider: String,
  val providerType: ProviderType,
  val sourceProvider: String,
  val description: String,
  val contextLength: Int,
  val isFree: Boolean,
  val isToolCapable: Boolean,
  val isVisionCapable: Boolean,
  val isReasoningCapable: Boolean,
  val modelClass: String, // "chat", "classifier", "embedding", "reranker", "image_gen"
  val avgLatencyMs: Long,
  val healthStatus: String, // "HEALTHY", "DEGRADED", "OFFLINE"
  val pricingPrompt: Double,
  val pricingCompletion: Double,
  val isQualifiedFree: Boolean,
  val exclusionReason: String? = null,
  val isUserPreferredInAuto: Boolean = false,
  val isUserExcludedFromAuto: Boolean = false,
  val lastUsedMs: Long? = null,
  // Provenance (provider-law 2026-08-26)
  val configured: Boolean = false,
  val available: Boolean = true,
  val discoveredAtMs: Long = System.currentTimeMillis(),
  val endpointSource: String = "live_catalog",
  val vaultKeyName: String? = null
)

enum class ProviderType { GATEWAY, DIRECT }

enum class ProviderSource(val displayName: String, val vaultKey: String, val baseUrl: String) {
  OPENROUTER("OpenRouter", "OPENROUTER_API_KEY", "https://openrouter.ai/api/v1"),
  NIM("NVIDIA NIM", "NVIDIA_NIM_API_KEY", "https://integrate.api.nvidia.com/v1"),
  // MiniMax native subscription (2026-08-29): api.minimax.io serves chat +
  // image gen + video gen + understanding under the one MINIMAX_API_KEY.
  MINIMAX("MiniMax", "MINIMAX_API_KEY", "https://api.minimax.io/v1"),
  LONGCAT("LongCat", "LONGCAT_API_KEY", "https://api.longcat.chat/openai/v1"),
  KIMI("Kimi (Moonshot)", "KIMI_API_KEY", "https://api.moonshot.cn/v1"),
  QWEN("Qwen (DashScope)", "QWEN_API_KEY", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
  DEEPSEEK("DeepSeek", "DEEPSEEK_API_KEY", "https://api.deepseek.com/v1"),
  OPENAI("OpenAI", "OPENAI_API_KEY", "https://api.openai.com/v1"),
  ZAI("Z.ai (GLM)", "ZAI_API_KEY", "https://api.z.ai/v1");

  val isGateway: Boolean get() = this == OPENROUTER || this == NIM
}

/**
 * SPEND POLICY: operator-controlled cap on paid traffic.
 * - OFF: paid routes blocked entirely. AUTO FREE only.
 * - ASK: each paid call requires explicit operator approval per turn.
 * - AUTO_WITH_LIMIT: paid calls auto-proceed if under daily/per-job cap.
 * dailyCentsSpent resets when the local date rolls over (operator prefs).
 */
enum class SpendMode { OFF, ASK, AUTO_WITH_LIMIT }

data class SpendPolicy(
  val mode: SpendMode = SpendMode.OFF,
  val dailyCapCents: Int = 0,
  val perJobCapCents: Int = 0
)
