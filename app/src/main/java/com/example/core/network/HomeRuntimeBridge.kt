package com.example.core.network

import android.util.Log
import com.example.core.model.DecisionSource
import com.example.core.model.DispatchInitiator
import com.example.core.model.DispatchReceipt
import com.example.core.model.ModelMode
import com.example.core.runtime.DispatchRecorder
import com.example.core.runtime.RoutingDecisionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HomeRuntimeBridge — the ONE adapter between this Android node and the
 * canonical PurpClaw main runtime (unified_api.js, default port 7780).
 *
 * LAW: Android attaches to the canonical runtime. It never impersonates it,
 * never fabricates its health, and never relabels a cloud call as a Home call.
 * All home-session traffic flows through this single typed client (UI
 * integration law: one typed client per surface).
 */
object HomeRuntimeBridge {
  private const val TAG = "HomeRuntimeBridge"
  private const val DEFAULT_PORT = 7780

  private val jsonMedia = "application/json; charset=utf-8".toMediaType()

  private var baseUrl: String = "http://192.168.55.203:$DEFAULT_PORT" // PC LAN IP — phone and PC are on same 192.168.55.x subnet; override via configure() for other networks

  private val client = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(240, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .build()

  fun configure(host: String, port: Int = DEFAULT_PORT) {
    baseUrl = if (host.startsWith("http")) host.trimEnd('/') else "http://$host:$port"
  }

  fun currentBaseUrl(): String = baseUrl

  /**
   * HOME-LINK DORMANCY LAW (2026-09-02): Home is NOT a provider. It is a link
   * to the user's home PC that exists ONLY while the user has opted in via
   * Settings (RoutingState.useHomeRouting). While disabled, every bridge
   * method fails fast WITHOUT touching the network — no probes, no roster
   * fetches, no telemetry pushes. Inference runs on the phone's own AUTO
   * router catalogue (real cloud providers) meanwhile. MainViewModel keeps
   * this flag in lockstep with the Settings toggle and persists it.
   */
  @Volatile
  var homeLinkEnabled: Boolean = false
    private set

  fun setHomeLinkEnabled(enabled: Boolean) {
    if (homeLinkEnabled == enabled) return
    homeLinkEnabled = enabled
    Log.i(
      TAG,
      if (enabled) "Home link ACTIVE (settings opt-in on)"
      else "Home link DORMANT (settings opt-in off) — all bridge calls fail fast, zero network"
    )
    // TELEMETRY (operator 2026-09-02): every home link state change is recorded.
    com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
      router = "HomeRuntimeBridge",
      kind = "link_state",
      decision = if (enabled) "ACTIVE" else "DORMANT",
      success = true,
      input = "enabled=$enabled",
      output = "homeLinkEnabled=$enabled"
    )
  }

  // STEP 12 (2026-08-27): durable routing-decision ledger.
  // chatWithTools() persists a DispatchReceipt per call with
  // decisionSource = CORE_RELAY (home core made the routing decision).
  // Default no-op so legacy callers keep compiling.
  @Volatile
  var dispatchRecorder: DispatchRecorder? = null

  @Volatile
  var routingContextProvider: (() -> RoutingDecisionContext?) = { null }

  /**
   * Persist a CORE_RELAY DispatchReceipt. Best-effort — recorder failure
   * never poisons the chat reply. Returns the dispatchId for diagnostics.
   */
  private suspend fun persistRelayReceipt(
    providerLabel: String?,
    modelId: String?,
    latencyMs: Long,
    ok: Boolean,
    errorCode: String?
  ): String? {
    val recorder = dispatchRecorder ?: return null
    val ctx = routingContextProvider() ?: RoutingDecisionContext.UNKNOWN
    val provider = providerLabel ?: "core"
    val mid = modelId ?: "core-relay"
    val receipt = DispatchReceipt.now(
      timestampMs = System.currentTimeMillis(),
      initiator = ctx.initiator.takeIf { it != DispatchInitiator.UNKNOWN }
        ?: DispatchInitiator.ROUTER,
      sourceTurnId = ctx.sourceTurnId,
      sourceAgentId = ctx.sourceAgentId,
      provider = provider,
      modelId = mid,
      routeMode = ctx.routeMode.takeIf { it != ModelMode.AUTO } ?: ModelMode.AUTO,
      routerEnabled = ctx.routerEnabled || true,        // CORE_RELAY implies router is "on" from core's POV
      decisionSource = DecisionSource.CORE_RELAY,
      reason = if (ok) "core-relay $provider/$mid" else "core-relay failed: $errorCode",
      leaseSnapshotId = ctx.leaseSnapshotId,
      capabilitySnapshotId = ctx.capabilitySnapshotId,
      latencyMs = latencyMs,
      routingComputeMs = ctx.routingComputeMs,
      estimatedCostCents = ctx.estimatedCostCents,
      errorCode = errorCode
    )
    return try {
      recorder.record(receipt)
    } catch (e: Exception) {
      Log.w(TAG, "DispatchReceipt persist failed: ${e.message}")
      receipt.dispatchId
    }
  }

  data class HealthResult(
    // REACHABILITY LAW: online == we got a real HTTP answer from the runtime.
    // Subsystem degradation (tower down etc.) does NOT mean Home is unusable —
    // capability truth lives in `status`/`chatCapable`, never in this flag.
    val online: Boolean,
    val latencyMs: Long,
    val status: String? = null,
    val towerStatus: String? = null,
    val chatCapable: Boolean = false,
    val agentCount: Int = 0,
    val activeAgents: Int = 0,
    val runtimeId: String? = null,
    val runtimeVersion: String? = null,
    val uptimeSeconds: Double? = null,
    val raw: String? = null,
    val error: String? = null
  )

  /** GET /api/health — REAL values only, straight from the canonical runtime. */
  suspend fun probeHealth(): HealthResult = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext HealthResult(
      online = false, latencyMs = 0, error = "HOME_LINK_DISABLED"
    ).also {
      com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
        router = "HomeRuntimeBridge",
        kind = "probe_health",
        decision = "skipped",
        success = false,
        durationMs = 0L,
        input = "baseUrl=$baseUrl",
        output = "skipped reason=HOME_LINK_DISABLED"
      )
    }
    val start = System.currentTimeMillis()
    try {
      val request = Request.Builder()
        .url("$baseUrl/api/health")
        .header("X-PurpClaw-Node", "phone-android-node-01")
        .build()
      val response = client.newCall(request).execute()
      val latency = System.currentTimeMillis() - start
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        return@withContext HealthResult(
          online = false, latencyMs = latency,
          error = "HTTP ${response.code}", raw = body.take(400)
        )
      }
      val json = JSONObject(body)
      val runtime = json.optJSONObject("runtime")
      HealthResult(
        online = true, // reachable — HTTP answered; status string carries the health truth
        chatCapable = json.optString("status") != "OFFLINE",
        latencyMs = latency,
        status = json.optString("status"),
        towerStatus = json.optString("tower"),
        agentCount = json.optInt("agents", 0),
        activeAgents = json.optInt("activeAgents", 0),
        runtimeId = runtime?.optString("runtimeId"),
        runtimeVersion = runtime?.optString("version"),
        uptimeSeconds = if (json.has("uptime")) json.getDouble("uptime") else null,
        raw = body.take(400)
      ).also { healthResult ->
        // TELEMETRY: every home probe decision is recorded.
        com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
          router = "HomeRuntimeBridge",
          kind = "probe_health",
          decision = if (healthResult.online) "online:${healthResult.status}" else "offline",
          success = healthResult.online,
          durationMs = healthResult.latencyMs,
          input = "baseUrl=$baseUrl",
          output = "status=${healthResult.status} tower=${healthResult.towerStatus} agents=${healthResult.agentCount} runtimeId=${healthResult.runtimeId}",
          errorClass = if (healthResult.online) "" else "PROBE_FAILED",
          errorMessage = healthResult.error.orEmpty()
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Home health probe failed: ${e.message}")
      HealthResult(
        online = false,
        latencyMs = System.currentTimeMillis() - start,
        error = "${e.javaClass.simpleName}: ${e.message}"
      ).also { failed ->
        com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
          router = "HomeRuntimeBridge",
          kind = "probe_health",
          decision = "exception",
          success = false,
          durationMs = failed.latencyMs,
          input = "baseUrl=$baseUrl",
          output = "exception=${e.javaClass.simpleName}",
          errorClass = e.javaClass.simpleName,
          errorMessage = e.message ?: "probe exception"
        )
      }
    }
  }

  data class HomeProvider(val id: String, val model: String?, val healthy: Boolean)

  data class CapabilitiesResult(
    val reachable: Boolean,
    val runtimeId: String? = null,
    val providers: List<HomeProvider> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val healthStatus: String? = null,
    val error: String? = null
  )

  /** GET /api/capabilities — the PC's own advertised provider/model truth. */
  suspend fun probeCapabilities(): CapabilitiesResult = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext CapabilitiesResult(
      reachable = false, error = "HOME_LINK_DISABLED"
    )
    try {
      val request = Request.Builder()
        .url("$baseUrl/api/capabilities")
        .header("X-PurpClaw-Node", "phone-android-node-01")
        .build()
      val response = client.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        return@withContext CapabilitiesResult(reachable = false, error = "HTTP ${response.code}")
      }
      val json = JSONObject(body)
      val node = json.optJSONObject("node")
      val targets = json.optJSONObject("executionTargets")
      val pc = targets?.optJSONObject("home_pc")
      val providersJson = pc?.optJSONArray("providers")
      val providers = buildList {
        if (providersJson != null) for (i in 0 until providersJson.length()) {
          val p = providersJson.optJSONObject(i) ?: continue
          add(HomeProvider(p.optString("id"), p.optString("model").ifEmpty { null }, p.optBoolean("healthy", false)))
        }
      }
      val capsJson = pc?.optJSONArray("capabilities")
      val caps = buildList {
        if (capsJson != null) for (i in 0 until capsJson.length()) add(capsJson.optString(i))
      }
      CapabilitiesResult(
        reachable = true,
        runtimeId = node?.optString("runtimeId"),
        providers = providers,
        capabilities = caps,
        healthStatus = json.optJSONObject("health")?.optString("status")
      )
    } catch (e: Exception) {
      CapabilitiesResult(reachable = false, error = "${e.javaClass.simpleName}: ${e.message}")
    }
  }

  data class ChatResult(
    val ok: Boolean,
    val reply: String,
    val provider: String?,
    val model: String?,
    val sessionId: String?,
    val toolCalls: Int,
    val agentCalls: Int,
    val fallbackCount: Int,
    val durationMs: Long,
    val route: String?,
    val error: String? = null
  )

  /**
   * POST /api/chat — executes through the canonical runtime's own provider
   * router, steering stack and event spine. The phone does not re-route.
   *
   * @param model Optional model override. When set, forces the canonical runtime
   *              to use this specific model instead of AUTO routing. Used by
   *              podcast to pin all seats to MiniMax-M2.7 from Ted's subscription.
   */
  suspend fun chat(
    message: String,
    sessionId: String,
    source: String = "android-mesh",
    mode: String? = null,
    model: String? = null,
    executionIntent: Boolean = false,
    executionAction: String? = null,
    fullSystemScope: Boolean = false,
    requestTimeoutMs: Long? = null
  ): ChatResult = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext ChatResult(
      ok = false, reply = "", provider = null, model = null, sessionId = null,
      toolCalls = 0, agentCalls = 0, fallbackCount = 0, durationMs = 0,
      route = null, error = "HOME_LINK_DISABLED"
    )
    val start = System.currentTimeMillis()
    try {
      val payload = JSONObject().apply {
        put("message", message)
        put("session_id", sessionId)
        put("source", source)
        put("spawnAgents", false)
        if (mode != null) {
          put("interactionMode", mode)
          put("mode", mode)
        }
        // model override: body.model is wired in unified_api.js at priority 2
        // (overrides > data.model > ctx.model). Podcast pins MiniMax-M2.7 here.
        if (model != null) put("model", model)
        // GESTURE LAW: trusted UI execution gesture from the operator's mode
        // selection. Canonical runtime mints a UI_ACTION lease only when these
        // fields are present and allowlisted (lib/execution-lease.js).
        if (executionIntent && executionAction != null) {
          put("executionIntent", true)
          put("executionAction", executionAction)
        }
        // FULL AUTHORITY: WORK turns run under the dangerous profile — no
        // review gates, no approval timeouts. The operator already decided.
        val envelope = JSONObject()
          .putOpt("access", if (fullSystemScope) "full-system" else "review")
          .putOpt("mode", (mode ?: "chat").lowercase())
          .putOpt("memory", "persistent")
        put("envelope", envelope)
      }
      val request = Request.Builder()
        .url("$baseUrl/api/chat")
        .header("X-PurpClaw-Node", "phone-android-node-01")
        .post(payload.toString().toRequestBody(jsonMedia))
        .build()
      // A caller-visible deadline must also reach OkHttp. A coroutine timeout
      // alone cannot reliably interrupt a blocking execute(), which is how a
      // dead provider previously left Android showing THINKING indefinitely.
      val requestClient = if (requestTimeoutMs != null) {
        client.newBuilder()
          .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
          .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
          .build()
      } else client
      val response = requestClient.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        return@withContext ChatResult(
          ok = false, reply = "", provider = null, model = null,
          sessionId = sessionId, toolCalls = 0, agentCalls = 0,
          fallbackCount = 0, durationMs = System.currentTimeMillis() - start,
          route = null, error = "HTTP ${response.code}: ${body.take(300)}"
        )
      }
      val json = JSONObject(body)
      val telemetry = json.optJSONObject("telemetry")
      // REASONING HYGIENE: strip raw chain-of-thought (<think>…</think>) from
      // visible content. Reasoning belongs in a collapsed trace, never chat.
      var reply = json.optString("reply", "")
        .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^\\s*<think>[\\s\\S]*", RegexOption.IGNORE_CASE), "") // unclosed think at start
        .trim()
      // NULL-BUBBLE LAW: org.json renders JSON null as the literal string
      // "null". A null/absent reply is NO reply — coerce to empty so the UI
      // shows an honest empty/error state instead of printing "null".
      if (reply == "null") reply = ""
      fun optText(key: String): String? =
        if (json.isNull(key)) null else json.optString(key, null)
      ChatResult(
        ok = json.optBoolean("ok", false),
        reply = reply,
        provider = optText("provider"),
        model = optText("model"),
        sessionId = json.optString("sessionId", sessionId),
        toolCalls = telemetry?.optInt("toolCalls", 0) ?: 0,
        agentCalls = telemetry?.optInt("agentCalls", 0) ?: 0,
        fallbackCount = telemetry?.optInt("fallbackCount", 0) ?: 0,
        durationMs = System.currentTimeMillis() - start,
        route = telemetry?.optString("route")
      )
    } catch (e: Exception) {
      Log.e(TAG, "Home chat failed: ${e.message}")
      ChatResult(
        ok = false, reply = "", provider = null, model = null,
        sessionId = sessionId, toolCalls = 0, agentCalls = 0,
        fallbackCount = 0, durationMs = System.currentTimeMillis() - start,
        route = null, error = "${e.javaClass.simpleName}: ${e.message}"
      )
    }
  }

  // ── FUNCTION-CALLING WIRE (2026-08-26) ───────────────────────────────
  // chatWithTools sends a `tools: [...]` JSON array to /api/chat and reads
  // back both `reply` and `tool_calls[]`. Feature-flagged behind
  // tools_wire_enabled so the path is a no-op until the canonical core
  // (unified_api.js) accepts the new schema.

  /**
   * Feature flag — read once at chat time. Default false until
   * :7780 unified_api.js is verified to accept `tools:` in /api/chat.
   * Operators can flip this from Settings.
   */
  @Volatile
  var toolsWireEnabled: Boolean = false

  /**
   * Wire shape — matches ToolDescriptor.parametersSchema and OpenAI's
   * `function` block. The phone never invents a tool name; it only
   * echoes descriptors produced by ToolRuntimeEngine.
   */
  data class ToolWireSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject
  )

  data class ChatWithToolsResult(
    val ok: Boolean,
    val reply: String,
    val provider: String?,
    val model: String?,
    val sessionId: String?,
    val toolCalls: List<com.example.core.runtime.CanonicalToolCall>,
    val agentCalls: Int,
    val fallbackCount: Int,
    val durationMs: Long,
    val route: String?,
    val error: String? = null
  )

  /**
   * POST /api/chat with a `tools: [...]` array attached. Returns both the
   * textual reply and the parsed CanonicalToolCall list. Falls back to
   * plain ChatResult-style behaviour (empty toolCalls) when the provider
   * did not emit any function calls or when wire is disabled.
   */
  suspend fun chatWithTools(
    message: String,
    sessionId: String,
    tools: List<ToolWireSpec>,
    source: String = "android-mesh",
    mode: String? = null,
    model: String? = null,
    executionIntent: Boolean = false,
    executionAction: String? = null,
    fullSystemScope: Boolean = false,
    toolChoice: String = "auto"
  ): ChatWithToolsResult = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext ChatWithToolsResult(
      ok = false, reply = "", provider = null, model = null, sessionId = null,
      toolCalls = emptyList(), agentCalls = 0, fallbackCount = 0, durationMs = 0,
      route = null, error = "HOME_LINK_DISABLED"
    )
    val start = System.currentTimeMillis()
    try {
      val payload = JSONObject().apply {
        put("message", message)
        put("session_id", sessionId)
        put("source", source)
        put("spawnAgents", false)
        if (mode != null) {
          put("interactionMode", mode)
          put("mode", mode)
        }
        if (model != null) put("model", model)
        if (executionIntent && executionAction != null) {
          put("executionIntent", true)
          put("executionAction", executionAction)
        }
        // FEATURE FLAG: skip tools wire unless explicitly enabled. Core
        // returns 400 on unknown fields and we want graceful degradation.
        if (toolsWireEnabled && tools.isNotEmpty()) {
          val arr = org.json.JSONArray()
          tools.forEach { spec ->
            arr.put(org.json.JSONObject().apply {
              put("type", "function")
              put("function", org.json.JSONObject().apply {
                put("name", spec.name)
                put("description", spec.description)
                put("parameters", spec.parameters)
              })
            })
          }
          put("tools", arr)
          put("tool_choice", toolChoice)
        }
        val envelope = JSONObject()
          .putOpt("access", if (fullSystemScope) "full-system" else "review")
          .putOpt("mode", (mode ?: "chat").lowercase())
          .putOpt("memory", "persistent")
        put("envelope", envelope)
      }
      val request = Request.Builder()
        .url("$baseUrl/api/chat")
        .header("X-PurpClaw-Node", "phone-android-node-01")
        .post(payload.toString().toRequestBody(jsonMedia))
        .build()
      val response = client.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val lat = System.currentTimeMillis() - start
        persistRelayReceipt(
          providerLabel = null,
          modelId = null,
          latencyMs = lat,
          ok = false,
          errorCode = "HTTP ${response.code}"
        )
        return@withContext ChatWithToolsResult(
          ok = false, reply = "", provider = null, model = null,
          sessionId = sessionId, toolCalls = emptyList(),
          agentCalls = 0, fallbackCount = 0,
          durationMs = lat,
          route = null, error = "HTTP ${response.code}: ${body.take(300)}"
        )
      }
      val json = JSONObject(body)
      val telemetry = json.optJSONObject("telemetry")
      var reply = json.optString("reply", "")
        .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^\\s*<think>[\\s\\S]*", RegexOption.IGNORE_CASE), "")
        .trim()
      if (reply == "null") reply = ""
      fun optText(key: String): String? =
        if (json.isNull(key)) null else json.optString(key, null)

      // Tool-call response parsing — same envelope on every supported provider.
      val rawToolCalls = json.optJSONArray("tool_calls")
        ?: json.optJSONObject("choices")?.optJSONObject("0")?.optJSONObject("message")?.optJSONArray("tool_calls")
      val toolCalls = if (rawToolCalls != null) {
        com.example.core.runtime.CanonicalToolCall.parseArray(rawToolCalls, "openrouter_or_nim")
      } else emptyList()

      ChatWithToolsResult(
        ok = json.optBoolean("ok", false),
        reply = reply,
        provider = optText("provider"),
        model = optText("model"),
        sessionId = json.optString("sessionId", sessionId),
        toolCalls = toolCalls,
        agentCalls = telemetry?.optInt("agentCalls", 0) ?: 0,
        fallbackCount = telemetry?.optInt("fallbackCount", 0) ?: 0,
        durationMs = System.currentTimeMillis() - start,
        route = telemetry?.optString("route"),
        error = optText("error")
      ).also { result ->
        persistRelayReceipt(
          providerLabel = result.provider,
          modelId = result.model,
          latencyMs = result.durationMs,
          ok = result.ok,
          errorCode = result.error
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Home chatWithTools failed: ${e.message}")
      val lat = System.currentTimeMillis() - start
      persistRelayReceipt(
        providerLabel = null,
        modelId = null,
        latencyMs = lat,
        ok = false,
        errorCode = "${e.javaClass.simpleName}: ${e.message}"
      )
      ChatWithToolsResult(
        ok = false, reply = "", provider = null, model = null,
        sessionId = sessionId, toolCalls = emptyList(),
        agentCalls = 0, fallbackCount = 0,
        durationMs = lat,
        route = null, error = "${e.javaClass.simpleName}: ${e.message}"
      )
    }
  }

  /** POST /api/steer — operator steering / DRIVE delegation into the canonical resolver. */
  suspend fun steer(action: String, directive: String = "", sessionId: String? = null): String =
    withContext(Dispatchers.IO) {
      if (!homeLinkEnabled) return@withContext "{\"ok\":false,\"error\":\"HOME_LINK_DISABLED\"}"
      try {
        val payload = JSONObject().apply {
          put("action", action)
          if (directive.isNotBlank()) put("directive", directive)
          if (sessionId != null) put("session_id", sessionId)
          put("source", "android-drive")
        }
        val request = Request.Builder()
          .url("$baseUrl/api/steer")
          .post(payload.toString().toRequestBody(jsonMedia))
          .build()
        val response = client.newCall(request).execute()
        response.body?.string().orEmpty().take(2000)
      } catch (e: Exception) {
        "{\"ok\":false,\"error\":\"${e.message}\"}"
      }
    }

  /** GET /api/memory — canonical runtime memory snapshot (facts + notes). */
  suspend fun fetchCanonicalMemory(): String = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext "{\"error\":\"HOME_LINK_DISABLED\"}"
    try {
      val request = Request.Builder().url("$baseUrl/api/memory").build()
      val response = client.newCall(request).execute()
      response.body?.string().orEmpty().take(8000)
    } catch (e: Exception) {
      "{\"error\":\"${e.message}\"}"
    }
  }

  data class RosterAgent(
    val id: String,
    val name: String,
    val desc: String,
    val icon: String,
    val model: String,
    val division: String,
    /** STEP 13 (2026-08-27) — full soul identity. Null-tolerant: old cores don't send these. */
    val role: String? = null,
    val wants: String? = null,
    val needs: String? = null,
    val goals: String? = null,
    val wishes: String? = null,
    val soulDescription: String? = null,
    val voiceProfile: com.example.core.runtime.VoiceProfile? = null
  )

  /** GET /api/registry/agents — canonical soul roster from the main stack. */
  suspend fun fetchAgentRoster(): List<RosterAgent> = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext emptyList()
    try {
      val request = Request.Builder().url("$baseUrl/api/registry/agents").build()
      val response = client.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) return@withContext emptyList()
      val json = JSONObject(body)
      val arr = json.optJSONArray("agents") ?: return@withContext emptyList()
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        RosterAgent(
          id = o.optString("id"),
          name = o.optString("name", o.optString("id")),
          desc = o.optString("desc"),
          icon = o.optString("icon", "🤖"),
          model = o.optString("model", "AUTO"),
          division = o.optString("division", "UNASSIGNED"),
          role = o.optString("role").ifBlank { null },
          wants = o.optString("wants").ifBlank { null },
          needs = o.optString("needs").ifBlank { null },
          goals = o.optString("goals").ifBlank { null },
          wishes = o.optString("wishes").ifBlank { null },
          soulDescription = o.optString("soulDescription").ifBlank { null },
          voiceProfile = o.optJSONObject("voiceProfile")?.let { v ->
            val voiceId = v.optString("voice_id")
            if (voiceId.isBlank()) null else com.example.core.runtime.VoiceProfile(
              soulId = o.optString("name", o.optString("id")),
              kokoroVoiceId = voiceId,
              platformVoiceSlot = v.optInt("platform_voice_slot", v.optInt("voice_slot", 0)),
              rate = v.optDouble("speaking_rate", 0.95).toFloat(),
              pitch = v.optDouble("pitch", 1.0).toFloat(),
              pauseStyle = v.optString("pause_style", "natural"),
              energy = v.optDouble("energy", 0.64).toFloat(),
              fallbackVoiceId = v.optString("fallback_voice", "af_heart")
            )
          }
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Agent roster fetch failed: ${e.message}")
      emptyList()
    }
  }

  data class AgentJobResult(
    val ok: Boolean,
    val jobId: String? = null,
    val status: String? = null,
    val result: String? = null,
    val error: String? = null
  )

  /**
   * Delegate through the canonical child-job coordinator and wait for its
   * durable terminal state. Android is only an adapter; it never invents a
   * second sub-agent runtime when Home is unavailable.
   */
  suspend fun delegateAgentAndAwait(
    soulId: String,
    task: String,
    parentSessionId: String,
    timeoutMs: Long = 120_000L
  ): AgentJobResult = withContext(Dispatchers.IO) {
    if (soulId.isBlank() || task.isBlank()) {
      return@withContext AgentJobResult(false, error = "agentId and task are required")
    }
    if (!homeLinkEnabled) {
      return@withContext AgentJobResult(false, error = "HOME_LINK_DISABLED")
    }
    var activeJobId: String? = null
    try {
      val createBody = JSONObject().apply {
        put("task", task)
        put("taskClass", "CHAT")
        put("soul", soulId)
        put("sessionId", parentSessionId)
        put("autostart", true)
        put("mutating", false)
      }
      val create = Request.Builder().url("$baseUrl/api/children/create")
        .post(createBody.toString().toRequestBody(jsonMedia)).build()
      val createdResponse = client.newCall(create).execute()
      val createdBody = createdResponse.body?.string().orEmpty()
      if (!createdResponse.isSuccessful) {
        return@withContext AgentJobResult(false, error = "HTTP ${createdResponse.code}: ${createdBody.take(300)}")
      }
      val created = JSONObject(createdBody)
      val jobId = created.optJSONObject("job")?.optString("job_id").orEmpty()
      if (jobId.isBlank()) return@withContext AgentJobResult(false, error = "child job id missing")
      activeJobId = jobId

      val deadline = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < deadline) {
        val statusRequest = Request.Builder()
          .url("$baseUrl/api/children/status?jobId=$jobId")
          .get().build()
        val statusResponse = client.newCall(statusRequest).execute()
        val statusBody = statusResponse.body?.string().orEmpty()
        if (statusResponse.isSuccessful) {
          val job = JSONObject(statusBody).optJSONObject("job")
          val status = job?.optString("status").orEmpty().lowercase()
          when (status) {
            "completed" -> return@withContext AgentJobResult(
              ok = true, jobId = jobId, status = status,
              result = job?.optString("result").orEmpty()
            )
            "failed", "cancelled", "blocked", "interrupted" -> return@withContext AgentJobResult(
              ok = false, jobId = jobId, status = status,
              error = job?.optJSONObject("last_event")?.optString("error")
                ?.ifBlank { null } ?: "child job $status"
            )
          }
        }
        kotlinx.coroutines.delay(750)
      }
      AgentJobResult(false, jobId = jobId, status = "timeout", error = "child job did not finish within ${timeoutMs}ms")
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      // Parent turn owns child lifetime. Best-effort remote cancellation,
      // then rethrow so the parent reaches CANCELLED rather than FAILED.
      activeJobId?.let { jobId ->
        runCatching {
          val body = JSONObject().put("jobId", jobId).put("op", "cancel")
          val request = Request.Builder().url("$baseUrl/api/children/control")
            .post(body.toString().toRequestBody(jsonMedia)).build()
          client.newCall(request).execute().close()
        }
      }
      throw cancelled
    } catch (e: Exception) {
      AgentJobResult(false, error = "${e.javaClass.simpleName}: ${e.message}")
    }
  }

  data class RosterSkill(val name: String, val description: String)

  /** GET /api/skills/registry — canonical skills from the main stack. */
  suspend fun fetchSkillRoster(): List<RosterSkill> = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext emptyList()
    try {
      val request = Request.Builder().url("$baseUrl/api/skills/registry").build()
      val response = client.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) return@withContext emptyList()
      val json = JSONObject(body)
      val arr = json.optJSONArray("skills") ?: return@withContext emptyList()
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name")
        if (name.isBlank()) return@mapNotNull null
        RosterSkill(name = name, description = o.optString("description"))
      }
    } catch (e: Exception) {
      Log.w(TAG, "Skill roster fetch failed: ${e.message}")
      emptyList()
    }
  }

  // ── CANONICAL VOICE SETTINGS (core wins; web parity contract) ──
  // GET /api/settings — hydrate voice.* keys at boot. Server is source of
  // truth; local SharedPreferences are an offline cache only.
  data class VoiceSettings(
    val mode: String? = null,            // ptt | voice-in | voice-inout | hands-free
    val visualizerType: String? = null,
    val opacity: Float? = null,
    val intensity: Float? = null,
    val showState: Boolean? = null,
    val reactInput: Boolean? = null,
    val reactOutput: Boolean? = null,
  )

  suspend fun fetchVoiceSettings(): VoiceSettings? = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext null
    try {
      val request = Request.Builder().url("$baseUrl/api/settings").build()
      val response = client.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) return@withContext null
      val json = JSONObject(body)
      val settings = json.optJSONObject("settings") ?: json
      fun v(k: String) = settings.opt("voice.$k")
      VoiceSettings(
        mode = v("mode")?.toString(),
        visualizerType = v("visualizer.type")?.toString(),
        opacity = (v("visualizer.opacity") as? Number)?.toFloat(),
        intensity = (v("visualizer.intensity") as? Number)?.toFloat(),
        showState = v("visualizer.showState")?.toString()?.toBooleanStrictOrNull(),
        reactInput = v("visualizer.reactInput")?.toString()?.toBooleanStrictOrNull(),
        reactOutput = v("visualizer.reactOutput")?.toString()?.toBooleanStrictOrNull(),
      )
    } catch (e: Exception) {
      Log.w(TAG, "Voice settings fetch failed: ${e.message}")
      null
    }
  }

  /** POST /api/settings — push voice.* changes so web + android stay one truth. */
  suspend fun pushVoiceSettings(s: VoiceSettings): Boolean = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext false
    try {
      val payload = JSONObject()
      s.mode?.let { payload.put("voice.mode", it) }
      s.visualizerType?.let { payload.put("voice.visualizer.type", it) }
      s.opacity?.let { payload.put("voice.visualizer.opacity", it.toDouble()) }
      s.intensity?.let { payload.put("voice.visualizer.intensity", it.toDouble()) }
      s.showState?.let { payload.put("voice.visualizer.showState", it) }
      s.reactInput?.let { payload.put("voice.visualizer.reactInput", it) }
      s.reactOutput?.let { payload.put("voice.visualizer.reactOutput", it) }
      val media = "application/json; charset=utf-8".toMediaType()
      val request = Request.Builder().url("$baseUrl/api/settings")
        .post(payload.toString().toRequestBody(media)).build()
      client.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
      Log.w(TAG, "Voice settings push failed: ${e.message}")
      false
    }
  }

  /**
   * ONE ROUTER LAW write-through: mirror a model pin to the canonical core.
   * Core owns routing authority; this only reports the operator's choice.
   * Returns true when core acknowledged the pin.
   */
  suspend fun pinRouter(provider: String, model: String): Boolean = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext false
    try {
      val payload = JSONObject()
        .put("provider", provider)
        .put("model", model)
        .put("source", "android")
        .put("ts", System.currentTimeMillis())
      val media = "application/json; charset=utf-8".toMediaType()
      val request = Request.Builder().url("$baseUrl/api/llm/pin")
        .post(payload.toString().toRequestBody(media)).build()
      client.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
      Log.w(TAG, "Core pin push failed (offline?): ${e.message}")
      false
    }
  }

  /* ── Per-bubble action rail (spec: PURPCLAW_ACTION_RAIL_RENDERERS_SPEC) ──
     Mobile renderer = long-press bubble → bottom sheet. Both calls proxy
     through the same canonical dispatcher as CLI /act and TUI :act. */

  data class MessageActionRow(
    val action: String,
    val label: String,
    val group: String? = null,
    val gated: Boolean = false,
    val gateReason: String? = null,
  )

  data class MessageActionResult(
    val ok: Boolean,
    val hint: String? = null,
    val path: String? = null,
    val newBubbleId: String? = null,
    val newSessionId: String? = null,
    val pinned: Boolean? = null,
    val text: String? = null,
    val errorReason: String? = null,
  )

  /** GET /api/message-action — picker rows for the mobile surface. */
  suspend fun listMessageActions(
    bubbleRole: String = "assistant",
    ttsState: String = "IDLE",
  ): List<MessageActionRow> = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext emptyList()
    try {
      val request = Request.Builder()
        .url("$baseUrl/api/message-action?surface=mobile&bubbleRole=$bubbleRole&ttsState=$ttsState")
        .build()
      val response = client.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) return@withContext emptyList()
      val rowsJson = JSONObject(body).optJSONArray("rows") ?: return@withContext emptyList()
      val out = mutableListOf<MessageActionRow>()
      for (i in 0 until rowsJson.length()) {
        val r = rowsJson.optJSONObject(i) ?: continue
        out.add(
          MessageActionRow(
            action = r.optString("action"),
            label = r.optString("label", r.optString("action")),
            group = if (r.has("group")) r.optString("group") else null,
            gated = r.optBoolean("gated", false),
            gateReason = if (r.has("gateReason")) r.optString("gateReason") else null,
          )
        )
      }
      out
    } catch (e: Exception) {
      Log.w(TAG, "Action rail list failed: ${e.message}")
      emptyList()
    }
  }

  /** POST /api/message-action — fire one action on one bubble. */
  suspend fun dispatchMessageAction(
    action: String,
    bubbleId: String,
    sessionId: String,
    bubbleRole: String = "assistant",
    bubbleText: String = "",
  ): MessageActionResult = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext MessageActionResult(
      ok = false, errorReason = "HOME_LINK_DISABLED"
    )
    try {
      val bubble = JSONObject().put("id", bubbleId).put("role", bubbleRole).put("text", bubbleText)
      val payload = JSONObject()
        .put("surface", "mobile")
        .put("action", action)
        .put("bubbleId", bubbleId)
        .put("sessionId", sessionId)
        .put("bubble", bubble)
      val media = "application/json; charset=utf-8".toMediaType()
      val request = Request.Builder().url("$baseUrl/api/message-action")
        .post(payload.toString().toRequestBody(media)).build()
      val response = client.newCall(request).execute()
      val body = response.body?.string().orEmpty()
      val json = if (body.isNotEmpty()) JSONObject(body) else JSONObject()
      if (!json.optBoolean("ok", false)) {
        val err = json.optJSONObject("error")
        return@withContext MessageActionResult(
          ok = false,
          errorReason = err?.optString("reason") ?: err?.optString("code") ?: "HTTP ${response.code}",
        )
      }
      val r = json.optJSONObject("result") ?: JSONObject()
      // EXPORT nests its bridge return inside result.path as an object.
      val pathObj = r.optJSONObject("path")
      MessageActionResult(
        ok = true,
        hint = if (r.has("hint")) r.optString("hint") else null,
        path = when {
          pathObj != null -> pathObj.optString("path")
          r.has("path") && !r.isNull("path") -> r.optString("path")
          else -> null
        },
        newBubbleId = if (r.has("newBubbleId")) r.optString("newBubbleId") else null,
        newSessionId = if (r.has("newSessionId")) r.optString("newSessionId") else null,
        pinned = if (r.has("pinned") && !r.isNull("pinned")) r.optBoolean("pinned") else null,
        text = if (r.has("text")) r.optString("text") else null,
      )
    } catch (e: Exception) {
      Log.w(TAG, "Action rail dispatch failed: ${e.message}")
      MessageActionResult(ok = false, errorReason = e.message ?: "network error")
    }
  }

  // ── TTS ROUTING TELEMETRY (2026-09-01) ─────────────────────────────────
  // Observable TTS engine state so the canonical stack knows whether Kokoro or
  // native TTS handled each utterance. The PC-side router can surface this as
  // a /api/tts/routing status endpoint.

  /**
   * Push one TTS routing event to the canonical core. Core stores it in an
   * in-memory ring buffer keyed by nodeId so /api/tts/routing can serve the
   * last N events per source.
   *
   * @param engine "kokoro" | "native_tts"
   * @param success true if audio was produced (Kokoro WAV played or native speak() succeeded)
   * @param reason null on success; short failure token on fallback
   * @param latencyMs Kokoro synthesis latency (null when engine==native_tts)
   * @param voiceId Kokoro voice ID that was selected
   * @param soulId Soul that requested this utterance
   * @param detail Extended error detail (null on success)
   */
  suspend fun pushTtsTelemetry(
    engine: String,
    success: Boolean,
    reason: String?,
    latencyMs: Long?,
    voiceId: String,
    soulId: String,
    detail: String? = null
  ): Boolean = withContext(Dispatchers.IO) {
    if (!homeLinkEnabled) return@withContext false
    try {
      val payload = JSONObject().apply {
        put("node", "phone-android")
        put("engine", engine)
        put("success", success)
        put("voiceId", voiceId)
        put("soulId", soulId)
        reason?.let { put("reason", it) }
        latencyMs?.let { put("latencyMs", it) }
        detail?.let { put("detail", it) }
        put("ts", System.currentTimeMillis())
      }
      val request = Request.Builder()
        .url("$baseUrl/api/tts/routing")
        .post(payload.toString().toRequestBody(jsonMedia))
        .build()
      client.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
      Log.w(TAG, "pushTtsTelemetry failed (offline?): ${e.message}")
      false
    }
  }
}
