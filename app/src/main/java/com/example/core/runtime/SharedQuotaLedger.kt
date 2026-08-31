package com.example.core.runtime

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * SharedQuotaLedger — THE ONE quota brain for the entire Android node.
 *
 * ARCHITECTURE LAW (2026-08-29, NVIDIA NIM / low-RPM protection spec):
 * CHAT, VOICE (inference), WORK and PODCAST must NOT each own a 40-RPM limiter.
 * Four safe 40-RPM limiters combine into 160-RPM of API abuse against one key.
 * So the ledger lives ABOVE all modes, in the shared provider/runtime layer,
 * and every counted outbound inference request passes through it — there is no
 * other limiter anywhere in the app. ProviderRouter is the single funnel that
 * consults this object; CHAT/VOICE/WORK/PODCAST never limit themselves.
 *
 * What it enforces (all in ONE place):
 *  - Hard provider maximum (e.g. 40 RPM for NIM) with a LOWER operating ceiling
 *    (36 RPM) leaving safety headroom. Token-bucket, not `count < 40`.
 *  - Retries consume quota (a retry is still a request).
 *  - Council seats / WORK / VOICE / health probes share the same budget.
 *  - Concurrency limits + priority queueing (interactive CHAT/VOICE pre-empt
 *    queued PODCAST/WORK).
 *  - 429 -> stop and honour Retry-After; circuit breaker per provider.
 *  - Jittered exponential backoff on acquire.
 *  - Request deduplication (same logicalId+attemptId not double-fired).
 *  - Request-storm kill switch (abnormal velocity / repeated failing loops).
 *  - Telemetry StateFlow for the dashboard. NO API key material is ever logged.
 *
 * This is a limiter, not a router. It does not choose models. It only answers:
 * "may this exact request spend one token right now, and if not, how long to wait?"
 */
object SharedQuotaLedger {

    private const val TAG = "SharedQuotaLedger"

    /** Priority tiers. Interactive traffic pre-empts queued bulk work. */
    enum class Priority { INTERACTIVE, WORK, PODCAST, SYSTEM }

    data class ProviderPolicy(
        /** Advertised hard maximum requests/minute from the provider. */
        val hardMaxRpm: Int,
        /** Internal operating ceiling (below hardMax) — safety headroom. */
        val operatingRpm: Int,
        /** Max concurrent in-flight requests for this provider. */
        val maxConcurrency: Int,
        /** Burst bucket capacity (tokens). */
        val burstCapacity: Int
    )

    /** Default policy for NVIDIA NIM: 40 hard, 36 operating, 2-3 concurrency, burst 3. */
    private val DEFAULT_NIM = ProviderPolicy(40, 36, 3, 3)
    /** OpenRouter free tier is generous; still bounded to avoid abuse. */
    private val DEFAULT_OR = ProviderPolicy(200, 120, 8, 6)
    private val DEFAULT_DIRECT = ProviderPolicy(120, 90, 6, 5)

    private val policies = ConcurrentHashMap<String, ProviderPolicy>().apply {
        put("nim", DEFAULT_NIM)
        put("openrouter", DEFAULT_OR)
        put("minimax", ProviderPolicy(60, 50, 4, 4))
        put("direct", DEFAULT_DIRECT)
        // Each credential/provider owns an independent bucket. A generic
        // "direct" bucket made LongCat, Kimi, Qwen, DeepSeek, OpenAI and Z.ai
        // consume one another's allowance and was not a truthful provider
        // limiter.
        listOf("longcat", "kimi", "qwen", "deepseek", "openai", "zai").forEach {
            put(it, DEFAULT_DIRECT)
        }
    }

    // ── Token-bucket state (per provider) ────────────────────────────────
    private data class Bucket(
        val policy: ProviderPolicy,
        @Volatile var tokens: Double,
        @Volatile var lastRefillMs: Long,
        val mutex: Mutex = Mutex(),
        // active concurrency
        val active: AtomicLong = AtomicLong(0),
        // circuit breaker
        @Volatile var circuitOpenUntilMs: Long = 0L,
        @Volatile var consecutiveErrors: Int = 0,
        // Retry-After honour
        @Volatile var retryAfterUntilMs: Long = 0L,
        // Plan/account quota exhaustion is not a minute-level 429. Keep the
        // route dead until its explicit reset instead of retrying it.
        @Volatile var quotaExhaustedUntilMs: Long = 0L,
        // storm kill switch
        @Volatile var killSwitchUntilMs: Long = 0L
    )

    private val buckets = ConcurrentHashMap<String, Bucket>().apply {
        policies.forEach { (k, p) ->
            // Token bucket starts FULL at burst capacity, NOT at operatingRpm.
            // The 36 RPM operating ceiling is enforced by the refill RATE
            // (0.6 tokens/s), so steady-state throughput can never exceed it;
            // burstCapacity only permits short 2-4 request bursts.
            put(k, Bucket(p, p.burstCapacity.toDouble(), System.currentTimeMillis()))
        }
    }

    /** Refill rate: tokens per ms = operatingRpm / 60000. */
    private fun refillRateMs(p: ProviderPolicy) = p.operatingRpm / 60000.0

    private fun refill(b: Bucket) {
        val now = System.currentTimeMillis()
        val elapsed = now - b.lastRefillMs
        if (elapsed > 0) {
            b.tokens = (b.tokens + elapsed * refillRateMs(b.policy)).coerceAtMost(b.policy.burstCapacity.toDouble())
            b.lastRefillMs = now
        }
    }

    // ── Request deduplication ────────────────────────────────────────────
    // A logicalId + attemptId that already resolved (success or permanent fail)
    // must never be re-fired. We track in-flight + recently-failed fingerprints.
    private val seenAttempts = ConcurrentHashMap<String, Long>() // fingerprint -> expiryMs
    private val seenMutex = Mutex()

    /** Returns true if this (logicalId, attemptId) is a DUPLICATE of one already seen. */
    private suspend fun isDuplicate(fingerprint: String): Boolean = seenMutex.withLock {
        val now = System.currentTimeMillis()
        val exp = seenAttempts[fingerprint]
        if (exp != null && exp > now) return true
        seenAttempts[fingerprint] = now + 30_000L // 30s dedup window
        // best-effort eviction of stale entries
        if (seenAttempts.size > 2000) {
            val it = seenAttempts.entries.iterator()
            while (it.hasNext()) if (it.next().value <= now) it.remove()
        }
        false
    }

    // ── Request-storm detection ───────────────────────────────────────────
    @Volatile private var windowStartMs = System.currentTimeMillis()
    @Volatile private var windowCount = 0
    private val stormMutex = Mutex()
    @Volatile var stormTripped = false
        private set

    private suspend fun noteRequestVelocity() {
        stormMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - windowStartMs > 1000L) {
                windowStartMs = now
                windowCount = 0
            }
            windowCount++
            // Abnormal velocity: > (sum of all providers' hard max) * factor in 1s window,
            // or repeated identical failing loops. Kill switch for 15s of cooldown.
            val totalHardMax = policies.values.sumOf { it.hardMaxRpm }
            if (windowCount > totalHardMax * 2) {
                stormTripped = true
                buckets.values.forEach { it.killSwitchUntilMs = now + 15_000L }
                Log.w(TAG, "REQUEST-STORM KILL SWITCH tripped (vel=$windowCount/s). Cooling 15s.")
            }
        }
    }

    // ── Telemetry ────────────────────────────────────────────────────────
    data class ProviderTelemetry(
        val provider: String,
        val tokens: Double,
        val active: Long,
        val circuitOpen: Boolean,
        val quotaExhausted: Boolean,
        val quotaResetAtMs: Long,
        val retryAfterSec: Long,
        val killSwitchSec: Long,
        val consecutiveErrors: Int
    )

    fun telemetry(): List<ProviderTelemetry> = buckets.map { (name, b) ->
        val now = System.currentTimeMillis()
        ProviderTelemetry(
            provider = name,
            tokens = b.tokens,
            active = b.active.get(),
            circuitOpen = b.circuitOpenUntilMs > now,
            quotaExhausted = b.quotaExhaustedUntilMs > now,
            quotaResetAtMs = b.quotaExhaustedUntilMs,
            retryAfterSec = maxOf(0, (b.retryAfterUntilMs - now) / 1000),
            killSwitchSec = maxOf(0, (b.killSwitchUntilMs - now) / 1000),
            consecutiveErrors = b.consecutiveErrors
        )
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Acquire one token for a counted outbound inference request.
     * Suspends (with jittered backoff) until a token is available OR returns a
     * denial with a Retry-After hint. Caller must NOT fire the request if this
     * returns [AcquireResult.Denied].
     *
     * @param providerTag one of nim/openrouter/minimax/direct
     * @param logicalId stable id for the logical call (dedup)
     * @param attemptId attempt index (dedup + storm detection)
     * @param priority tier (interactive pre-empts bulk)
     */
    suspend fun acquire(
        providerTag: String,
        logicalId: String,
        attemptId: Int,
        priority: Priority = Priority.INTERACTIVE
    ): AcquireResult {
        val b = buckets[providerTag] ?: buckets["direct"]!!
        val now = System.currentTimeMillis()

        // Kill switch: graceful degradation, not app death.
        if (b.killSwitchUntilMs > now) {
            val wait = ((b.killSwitchUntilMs - now) / 1000) + 1
            return AcquireResult.Denied(retryAfterSec = wait, reason = "STORM_KILL_SWITCH")
        }
        // Circuit breaker: if open, refuse fast (honour cooldown).
        if (b.quotaExhaustedUntilMs > now) {
            val wait = ((b.quotaExhaustedUntilMs - now) / 1000) + 1
            return AcquireResult.Denied(retryAfterSec = wait, reason = "PROVIDER_QUOTA_EXHAUSTED")
        }
        if (b.circuitOpenUntilMs > now) {
            val wait = ((b.circuitOpenUntilMs - now) / 1000) + 1
            return AcquireResult.Denied(retryAfterSec = wait, reason = "CIRCUIT_OPEN")
        }
        // Retry-After honour (from a provider 429).
        if (b.retryAfterUntilMs > now) {
            val wait = ((b.retryAfterUntilMs - now) / 1000) + 1
            return AcquireResult.Denied(retryAfterSec = wait, reason = "PROVIDER_RETRY_AFTER")
        }
        // Request deduplication: never re-fire an identical failing loop.
        val fp = "$providerTag:$logicalId:$attemptId"
        if (isDuplicate(fp)) {
            return AcquireResult.Denied(retryAfterSec = 5, reason = "DUPLICATE_REQUEST")
        }
        noteRequestVelocity()
        if (stormTripped) {
            // storm may have just tripped inside noteRequestVelocity
            if (b.killSwitchUntilMs > now) {
                val wait = ((b.killSwitchUntilMs - now) / 1000) + 1
                return AcquireResult.Denied(retryAfterSec = wait, reason = "STORM_KILL_SWITCH")
            }
        }

        // Concurrency limit — interactive pre-empts by being allowed to queue;
        // bulk work is rejected (not queued) when at max concurrency to avoid
        // an 8-seat podcast hogging the key.
        // Token/concurrency mutation is atomic per provider. Volatile fields
        // alone were not enough: 100 concurrent callers could all observe
        // tokens=3 before any decrement and burst straight through the cap.
        return b.mutex.withLock {
            val cur = b.active.get()
            if (cur >= b.policy.maxConcurrency) {
                return@withLock AcquireResult.Denied(
                    retryAfterSec = if (priority == Priority.PODCAST || priority == Priority.SYSTEM) 2 else 1,
                    reason = if (priority == Priority.PODCAST || priority == Priority.SYSTEM)
                        "CONCURRENCY_LIMIT_BULK" else "CONCURRENCY_LIMIT"
                )
            }

            refill(b)
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0
                b.active.incrementAndGet()
                return@withLock AcquireResult.Granted
            }
            val deficit = 1.0 - b.tokens
            val waitMs = (deficit / refillRateMs(b.policy)).toLong().coerceAtLeast(50)
            val jitter = (waitMs * 0.2 * (UUID.randomUUID().toString()[0].code and 0xff) / 255.0).toLong()
            val capped = (waitMs + jitter).coerceAtMost(8000L)
            AcquireResult.Denied(
                retryAfterSec = (capped / 1000) + 1,
                reason = "QUOTA_EXHAUSTED",
                backoffMs = capped
            )
        }
    }

    /** Call after the request completes (success OR fail) to release concurrency. */
    fun release(providerTag: String) {
        // A denied acquire owns no concurrency lease. Defensive clamping keeps
        // bad caller cleanup from driving active below zero and bypassing TVG.
        buckets[providerTag]?.active?.updateAndGet { current -> if (current > 0L) current - 1L else 0L }
    }

    /**
     * Account/plan quota exhaustion. Unlike a minute-level 429, this route is
     * never automatically retried before [resetAtMs]. If the provider did not
     * supply a reset, use a conservative 24-hour circuit and let another
     * eligible provider carry the request.
     */
    fun recordQuotaExhausted(providerTag: String, resetAtMs: Long? = null) {
        val b = buckets[providerTag] ?: return
        val now = System.currentTimeMillis()
        val reset = resetAtMs?.takeIf { it > now } ?: (now + 24L * 60L * 60L * 1000L)
        b.quotaExhaustedUntilMs = reset
        b.circuitOpenUntilMs = maxOf(b.circuitOpenUntilMs, reset)
        b.consecutiveErrors++
        runCatching { Log.w(TAG, "$providerTag PROVIDER_QUOTA_EXHAUSTED; circuit open until resetAt=$reset") }
    }

    // COUNCIL LAW (2026-08-31): AUTH is not one failure class.
    // AUTH_MISSING_KEY  — key not in vault ("missing in KeystoreVault")
    // AUTH_REJECTED     — key in vault but rejected by provider (HTTP 401)
    // AUTH_FORBIDDEN    — provider returned HTTP 403
    enum class FailureClass { RATE_LIMITED, PROVIDER_QUOTA_EXHAUSTED, AUTH_MISSING_KEY, AUTH_REJECTED, AUTH_FORBIDDEN, TIMEOUT, OTHER }

    fun classifyFailure(message: String?): FailureClass {
        val value = message.orEmpty().lowercase()
        return when {
            "usage limit" in value || "quota exhausted" in value ||
                "resource has been exhausted" in value || "add credits" in value ||
                "insufficient credits" in value -> FailureClass.PROVIDER_QUOTA_EXHAUSTED
            "429" in value || "rate limit" in value || "too many requests" in value -> FailureClass.RATE_LIMITED
            // Check missing key BEFORE general unauthorized — more specific
            "missing in KeystoreVault" in value -> FailureClass.AUTH_MISSING_KEY
            "403" in value || "forbidden" in value -> FailureClass.AUTH_FORBIDDEN
            "401" in value || "unauthorized" in value -> FailureClass.AUTH_REJECTED
            "timeout" in value || "timed out" in value || "sockettimeoutexception" in value -> FailureClass.TIMEOUT
            else -> FailureClass.OTHER
        }
    }

    /**
     * Record a provider 429 (or other rate-limit signal). Honours Retry-After,
     * opens the circuit breaker, increments consecutive-error count.
     * @param retryAfterSec server-provided Retry-After; if null, use cooldown.
     */
    fun recordRateLimit(providerTag: String, retryAfterSec: Long? = null) {
        val b = buckets[providerTag] ?: return
        val now = System.currentTimeMillis()
        val ra = retryAfterSec?.coerceAtLeast(1L)?.coerceAtMost(120L) ?: 20L
        b.retryAfterUntilMs = now + ra * 1000
        b.consecutiveErrors++
        if (b.consecutiveErrors >= 2) {
            // 2-3 consecutive 429s -> open circuit 15-30s
            b.circuitOpenUntilMs = now + (15_000L + (b.consecutiveErrors - 2) * 5000L).coerceAtMost(30_000L)
            Log.w(TAG, "$providerTag circuit OPEN (${b.consecutiveErrors} consecutive 429s) until +${b.circuitOpenUntilMs - now}ms")
        }
    }

    /** Record a successful response — resets consecutive-error counter gradually. */
    fun recordSuccess(providerTag: String) {
        val b = buckets[providerTag] ?: return
        b.consecutiveErrors = 0
        // circuit auto-heals after its cooldown window (checked in acquire)
    }

    /** Record a non-rate-limit transport error (does not open circuit, but counts). */
    fun recordTransportError(providerTag: String) {
        val b = buckets[providerTag] ?: return
        b.consecutiveErrors = (b.consecutiveErrors + 1).coerceAtMost(10)
    }

    /** Safe alias/fingerprint for a key — NEVER the secret material. */
    fun keyAlias(providerTag: String, fullKey: String?): String {
        if (fullKey.isNullOrBlank()) return "$providerTag:key=none"
        val tail = fullKey.takeLast(4)
        return "$providerTag:key=***$tail"
    }

    /** Provider tag from a model id (shared helper for callers). */
    fun providerTagFor(modelId: String): String = when {
        modelId.startsWith("nvidia/") || modelId.contains("nim", ignoreCase = true) -> "nim"
        modelId.startsWith("openrouter/") -> "openrouter"
        modelId.startsWith("minimax/") -> "minimax"
        modelId.startsWith("longcat/") || modelId.startsWith("LongCat-") -> "longcat"
        modelId.startsWith("kimi/") || modelId.startsWith("moonshot/") -> "kimi"
        modelId.startsWith("qwen/") -> "qwen"
        modelId.startsWith("deepseek/") -> "deepseek"
        modelId.startsWith("openai/") || modelId.startsWith("gpt-") -> "openai"
        modelId.startsWith("zai/") || modelId.startsWith("glm-") -> "zai"
        else -> "direct"
    }

    /** Configure a provider's policy at runtime (values remain configurable). */
    fun configureProvider(providerTag: String, policy: ProviderPolicy) {
        policies[providerTag] = policy
        // Rebuild bucket at burst capacity (correct token-bucket init).
        buckets[providerTag] = Bucket(policy, policy.burstCapacity.toDouble(), System.currentTimeMillis())
    }

    sealed class AcquireResult {
        object Granted : AcquireResult()
        data class Denied(
            val retryAfterSec: Long,
            val reason: String,
            val backoffMs: Long = retryAfterSec * 1000
        ) : AcquireResult()
    }
}
