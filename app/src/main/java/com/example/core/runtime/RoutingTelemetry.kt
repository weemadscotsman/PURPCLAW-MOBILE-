package com.example.core.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * RoutingTelemetry — append-only telemetry for every routing decision across
 * every router in PurpClaw mobile. Distinct from AuditLedger (which records
 * capability operations with sha256 arg digests): RoutingTelemetry records
 * the full routing decision with input/output snapshots so an operator can
 * answer "what did the model router actually decide on this turn?", "why
 * did the tool router skip the calendar tool?", "when did the home link
 * go offline?" without developer logs.
 *
 * LAWS (operator 2026-09-02):
 *  - Append-only: entries are never edited or deleted by this class.
 *  - NEVER persists raw secrets (api keys, auth tokens) — callers must
 *    strip those before passing args.
 *  - NEVER emits null in JSON. Strings default "", booleans false, numbers 0.
 *  - Per-day JSONL file: telemetry-YYYY-MM-DD.jsonl
 *  - Cheap synchronous append; failure logged, never crashes the caller.
 *  - Hot path: every router calls record() on every decision.
 *
 * Coverable routers (any class that has a routing decision can call
 * RoutingTelemetry.of(context).record(...) ):
 *   - ProviderRouter (model lane selection: AUTO/MANUAL pin, free-gateway
 *     rotation, fallback path, when a candidate is skipped for context
 *     length, when OpenRouter is excluded for cost, when the build.nvidia.com
 *     Free Endpoint filter is applied)
 *   - ToolRuntimeEngine (every tool call, every approval gate, every
 *     artefact registration, every fallback)
 *   - WorkSession + WorkSessionForegroundService (every state transition:
 *     QUEUED, RUNNING, OBSERVING, SYNTHESIZING, COMPLETED, BLOCKED,
 *     CHECKPOINT, etc.)
 *   - HomeRuntimeBridge (every home probe, every link state change, every
 *     tool/workflow routed through home)
 *   - IntentResolver (every user intent decision: which tool, which
 *     provider, which mode)
 *   - SessionMeshCoordinator (every mesh status change: HOME_ONLINE,
 *     HOME_OFFLINE, PHONE_LOCAL, CLOUD_RELAY)
 *   - CouncilPodcastEngine (every seat pick, every debate round, every
 *     rotation, every close decision)
 *   - CapabilityTruthRegistry (every capability grant/revoke, every
 *     health sync)
 *   - AgentTowerManager (every agent pick, every mission dispatch)
 *   - WorkSessionForegroundService (every foreground service start/stop)
 */
class RoutingTelemetry private constructor(private val context: Context) {

  data class Event(
    val id: String = "",
    val seq: Long = 0L,
    val timestamp: Long = 0L,
    val router: String = "",
    val kind: String = "",
    val decision: String = "",
    val success: Boolean = false,
    val durationMs: Long = 0L,
    val sessionId: String = "",
    val input: String = "",
    val output: String = "",
    val errorClass: String = "",
    val errorMessage: String = ""
  )

  companion object {
    private const val TAG = "RoutingTelemetry"
    private const val DIR = "telemetry"
    private const val LOG_TAG = "RouteTel"
    private const val MAX_RECENT = 500

    @Volatile
    private var instance: RoutingTelemetry? = null

    /** Singleton accessor; returns null if not yet initialised. */
    fun getOrNull(): RoutingTelemetry? = instance

    fun of(context: Context): RoutingTelemetry {
      instance?.let { return it }
      synchronized(this) {
        instance?.let { return it }
        val t = RoutingTelemetry(context.applicationContext)
        instance = t
        return t
      }
    }

    private fun dayStamp(millis: Long): String =
      SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
  }

  // Monotonic per-device sequence, seeded from today's file so restarts never reuse a seq.
  private val seqCounter: AtomicLong = AtomicLong(seedSeqFromDisk())

  // Hot-recent ring buffer for the in-app debug surface.
  private val _recent = MutableStateFlow<List<Event>>(emptyList())
  val recent: StateFlow<List<Event>> = _recent.asStateFlow()

  private fun telemetryDir(): File = File(context.filesDir, DIR).apply { mkdirs() }

  private fun fileFor(millis: Long): File =
    File(telemetryDir(), "telemetry-${dayStamp(millis)}.jsonl")

  private fun seedSeqFromDisk(): Long = try {
    val f = fileFor(System.currentTimeMillis())
    if (!f.isFile) 0L else {
      var last = ""
      f.forEachLine { if (it.isNotBlank()) last = it }
      if (last.isBlank()) 0L else runCatching {
        JSONObject(last).optLong("seq", 0L)
      }.getOrDefault(0L)
    }
  } catch (_: Exception) { 0L }

  /**
   * Record one routing event. Safe to call from any thread; the file append
   * is synchronised so concurrent calls don't tear lines.
   */
  @Synchronized
  fun record(
    router: String,
    kind: String,
    decision: String,
    success: Boolean,
    durationMs: Long = 0L,
    sessionId: String = "",
    input: String = "",
    output: String = "",
    errorClass: String = "",
    errorMessage: String = ""
  ): Event {
    val now = System.currentTimeMillis()
    val event = Event(
      id = UUID.randomUUID().toString(),
      seq = seqCounter.incrementAndGet(),
      timestamp = now,
      router = router.ifBlank { "unknown" },
      kind = kind,
      decision = decision,
      success = success,
      durationMs = durationMs.coerceAtLeast(0L),
      sessionId = sessionId,
      input = truncate(input),
      output = truncate(output),
      errorClass = errorClass,
      errorMessage = truncate(errorMessage, 500)
    )
    val line = event.toJsonLine()
    try {
      fileFor(now).appendText(line + "\n")
    } catch (e: Exception) {
      Log.e(TAG, "telemetry append failed: ${e.message}")
    }
    // Always echo to logcat so the operator can grep LOG_TAG=RouteTel.
    val preview = "${event.router}#${event.kind} decision=${event.decision.take(40)} " +
      "success=${event.success} durMs=${event.durationMs}"
    if (event.success) Log.i(LOG_TAG, preview) else Log.w(LOG_TAG, preview)
    // Hot recent ring buffer (newest first, cap MAX_RECENT)
    synchronized(_recent) {
      _recent.value = (listOf(event) + _recent.value).take(MAX_RECENT)
    }
    return event
  }

  /**
   * Convenience for "I'm about to do something" → record at the end. Returns
   * a Recorder that the caller invokes with .end(decision, success, output,
   * errorClass, errorMessage). Use try/finally at the call site.
   */
  fun begin(router: String, kind: String, sessionId: String = "", input: String = ""): Recorder =
    Recorder(this, router, kind, sessionId, input, System.currentTimeMillis())

  class Recorder internal constructor(
    private val telemetry: RoutingTelemetry,
    private val router: String,
    private val kind: String,
    private val sessionId: String,
    private val input: String,
    private val startMs: Long
  ) {
    fun end(
      decision: String,
      success: Boolean,
      output: String = "",
      errorClass: String = "",
      errorMessage: String = ""
    ): Event = telemetry.record(
      router = router,
      kind = kind,
      decision = decision,
      success = success,
      durationMs = System.currentTimeMillis() - startMs,
      sessionId = sessionId,
      input = input,
      output = output,
      errorClass = errorClass,
      errorMessage = errorMessage
    )
  }

  suspend fun tail(n: Int): List<Event> = withContext(Dispatchers.IO) {
    if (n <= 0) return@withContext emptyList()
    val f = fileFor(System.currentTimeMillis())
    if (!f.isFile) return@withContext emptyList()
    try {
      f.readLines().filter { it.isNotBlank() }
        .mapNotNull { parseLine(it) }
        .takeLast(n)
    } catch (_: Exception) { emptyList() }
  }

  /** Newest N entries, all routers. Synchronous (small files only). */
  fun tailSync(n: Int): List<Event> {
    if (n <= 0) return emptyList()
    val f = fileFor(System.currentTimeMillis())
    if (!f.isFile) return emptyList()
    return try {
      f.readLines().filter { it.isNotBlank() }
        .mapNotNull { parseLine(it) }
        .takeLast(n)
    } catch (_: Exception) { emptyList() }
  }

  private fun parseLine(line: String): Event? = try {
    val o = JSONObject(line)
    Event(
      id = o.optString("id", ""),
      seq = o.optLong("seq", 0L),
      timestamp = o.optLong("timestamp", 0L),
      router = o.optString("router", ""),
      kind = o.optString("kind", ""),
      decision = o.optString("decision", ""),
      success = o.optBoolean("success", false),
      durationMs = o.optLong("durationMs", 0L),
      sessionId = o.optString("sessionId", ""),
      input = o.optString("input", ""),
      output = o.optString("output", ""),
      errorClass = o.optString("errorClass", ""),
      errorMessage = o.optString("errorMessage", "")
    )
  } catch (_: Exception) { null }

  /** Truncate long strings so a single 10MB blob doesn't blow up the JSONL. */
  private fun truncate(s: String, max: Int = 2000): String =
    if (s.length <= max) s else s.substring(0, max) + "...<truncated ${s.length - max} chars>"

  private fun Event.toJsonLine(): String = JSONObject().apply {
    put("id", id)
    put("seq", seq)
    put("timestamp", timestamp)
    put("router", router)
    put("kind", kind)
    put("decision", decision)
    put("success", success)
    put("durationMs", durationMs)
    put("sessionId", sessionId)
    put("input", input)
    put("output", output)
    put("errorClass", errorClass)
    put("errorMessage", errorMessage)
  }.toString()
}
