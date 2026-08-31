---
name: AGENT_NOTES_TASK92_HOME_FAILOVER_AND_RETRY
description: Three-tier mobile inference (Home Relay → Mobile Remote → Pinned Emergency) + ProviderRouter retry chain + truthful exhausted-retry card + empty-state composer banner. Closes the gap found in #10/#66 where the rescue chain is in source but never runs because the composer is not drawn when Home is unreachable.
type: project
---

# Mobile Home Failover + Retry — Lane #92 (2026-08-28 02:50Z)

## Why this lane exists

Two related debts:

1. **Task #10 — home failover repair.** Source analysis PASS (HOME-FAILOVER LAW at `MainViewModel.kt:1481-1552`). Runtime exercise IMPOSSIBLE because the chat composer does not render when Home Relay at `192.168.55.203:7780` is unreachable — the OS accessibility tree on S25 shows no EditText, no send button, no message list (only the avatar SurfaceView + a nine-tab bottom nav). The rescue chain is unreachable because the UI to invoke it doesn't exist.
2. **Task #66 — empty-visible-response recovery.** Same source PASS, same on-glass gap. The truth card string `"Home unreachable. Mobile provider returned no content. Tap to retry."` exists in source but never displays because the dispatch path is never entered.

The fix is **not just "wire the rescue chain"** — it is **"make the empty state render the composer + banner so the rescue chain has something to run inside"**. That requires two coupled changes:

1. **`CommandScreen.kt`** must render the chat composer + a "Home offline — using mobile" banner when `!homeReachable`. Currently nothing renders, which is a runtime-rendering bug, not a policy issue.
2. **`ProviderRouter.kt`** must accept an empty-route fallback chain when the operator pin is unavailable, and `MainViewModel.kt` must dispatch through that chain.

## Scope (in)

1. **Empty-state composer + banner.** When Home is unreachable, `CommandScreen` shows:
   - The chat composer (typed input + send button).
   - A non-blocking banner above the composer: "Home offline — replies will use mobile provider".
   - The current avatar mood stays IDLE; no aggressive "offline" state machines.
2. **Three-tier mobile inference contract.**
   - **Tier 1 — HOME RELAY** (preferred): `http://<home_ip>:7780/api/chat`. Live E2E shown working in earlier sessions.
   - **Tier 2 — MOBILE REMOTE** (fallback when Home unreachable): operator pin OR first OpenRouter free model.
   - **Tier 3 — PINNED EMERGENCY** (last resort): a single pinned model declared in settings, no failover, honest refusal if it also fails.
3. **ProviderRouter retry chain.** On `providerResult.content.isBlank()` after Tier 1 attempts Home (and Home returns no content or fails):
   - Try Tier 2 with operator pin if set.
   - If no pin, try first OpenRouter free model.
   - If still empty, try Tier 3 pinned emergency.
   - If still empty, return `DispatchReceipt` with `exhausted: true` and the truthful error card.
4. **Truthful exhausted-retry card.** On exhaustion, render:
   ```
   Home unreachable. Mobile provider returned no content. Tap to retry.
   ```
   NOT `[no visible reply from model]` as assistant content. The original turn ID is preserved; no ghost assistant turns.
5. **Receipt integration.** Every Tier 1/2/3 attempt emits a `DispatchReceipt` with `attemptedRoute`, `homeServed`, `mobileFallbackFired`, `tier`, `durationMs`, `errorClass`. Three receipts max per user turn; surface in turn telemetry footer.
6. **HOME-FAILOVER LAW persistence.** The existing law at `MainViewModel.kt:1481-1552` becomes the dispatcher for the three-tier chain rather than a single rescue call.

## Out of scope (deferred)

- Provider health circuit breaker — separate lane (`AGENT_NOTES_TASK85_PROVIDER_HEALTH`).
- Streaming retry mid-stream — separate lane (Task #67).
- Cross-session authority refresh — separate lane (Task #68).
- Auto-routing scoring — already in `streamChatAuto` for the AUTO mode; this lane only extends the manual-pin path.

## Hard rules

- **NEVER create ghost assistant turns** — preserve original turn ID across retries.
- **NEVER treat `[no visible reply from model]` as content** — it is a placeholder for the error card.
- **NEVER switch from MANUAL pin silently** — Tier 2 attempts must log `requestedRoute=X finalRoute=Y` in the receipt.
- **NEVER retry more than 3 times per user turn** — Tier 1 + Tier 2 + Tier 3 = 3 attempts. No fourth.
- **NEVER retry on a deterministic error class** — 4xx (except 408/429) means the request is malformed; do not retry.
- **NEVER fail closed without telling the user** — every exhausted path emits the truthful card.
- **NEVER block the composer** — the composer must always accept input, even when all tiers are exhausted. The user can edit and resend.
- **NEVER depend on a specific home IP** — read from `purpclaw_home_runtime_prefs.xml` or equivalent, fall back to last-known-good with a "stale config" warning.

## Files in scope (read-only this session; builder lane implements)

### Must read first

- `app/src/main/java/com/example/ui/MainViewModel.kt` — HOME-FAILOVER LAW at lines 1481-1552.
- `app/src/main/java/com/example/core/runtime/ProviderRouter.kt` — single-pin execution at lines 707-714; NO_ROUTING_AUTHORITY refusal.
- `app/src/main/java/com/example/ui/screens/CommandScreen.kt` — chat composer host; current behavior when `!homeReachable` is "render nothing".
- `app/src/main/java/com/example/core/runtime/HomeRuntimeBridge.kt` — `Action rail list failed` logcat evidence; runtime reachability check.
- `app/src/main/java/com/example/core/model/DispatchReceipt.kt` — extend with `tier`, `mobileFallbackFired`, `errorClass` fields. (Spec note 2026-08-28: previously referenced as `core/runtime/DispatchReceipt.kt`; actual path is `core/model/`.)

### To write / modify

- `app/src/main/java/com/example/ui/screens/CommandScreen.kt` — add empty-state composer + banner when `!homeReachable`.
- `app/src/main/java/com/example/ui/MainViewModel.kt` — replace HOME-FAILOVER LAW single rescue call with the three-tier dispatcher.
- `app/src/main/java/com/example/core/runtime/ProviderRouter.kt` — add `generateResponseWithFailover(input, tierChain)` that tries each tier in order, returns the first non-empty result or the exhausted receipt.
- `app/src/main/java/com/example/core/runtime/MobileInferenceTier.kt` — new enum + config struct for the three tiers.
- `app/src/main/java/com/example/core/runtime/RetryClassifier.kt` — classifies `ProviderResult` failures into `Transient | RateLimit | Timeout | Empty | UnsupportedCapability | StaleAuthority | ProviderDead | Deterministic`.
- `app/src/main/java/com/example/ui/components/TruthfulErrorCard.kt` — Compose component that renders the exhausted-retry card with the canonical copy.
- `app/src/main/java/com/example/ui/components/HomeOfflineBanner.kt` — Compose component for the empty-state banner.

## Three-tier dispatcher contract

```kotlin
enum class MobileInferenceTier(val label: String, val timeoutMs: Long) {
  HOME_RELAY("Home Relay", 15_000),
  MOBILE_REMOTE("Mobile Remote", 30_000),
  PINNED_EMERGENCY("Pinned Emergency", 45_000),
}

data class TierAttempt(
  val tier: MobileInferenceTier,
  val requestedRoute: String,        // e.g. "openrouter:meta-llama/llama-3.3-70b-instruct:free"
  val finalRoute: String?,            // resolved at receipt time
  val durationMs: Long,
  val errorClass: RetryClassifier.ErrorClass?,
  val content: String?,               // null if failed
)

suspend fun ProviderRouter.generateResponseWithFailover(
  input: TurnInput,
  tierChain: List<MobileInferenceTier>,
): Pair<String?, List<TierAttempt>>  // (content, attempt history)
```

### Dispatch algorithm (MainViewModel)

```kotlin
suspend fun dispatchWithFailover(input: TurnInput): DispatchReceipt {
  val attempts = mutableListOf<TierAttempt>()
  var firstContent: String? = null

  // Tier 1 — Home Relay (preferred when reachable)
  if (homeRuntime.isReachable()) {
    val result = providerRouter.generateResponseViaTier(input, MobileInferenceTier.HOME_RELAY)
    attempts += result
    if (!result.content.isNullOrBlank()) {
      firstContent = result.content
      // Don't try tier 2 if home served — that defeats "prefer home"
    }
  }

  // Tier 2 — Mobile Remote (operator pin OR OpenRouter free)
  if (firstContent.isNullOrBlank()) {
    val pinnedOrFirst = resolveOperatorPin() ?: firstOpenRouterFreeModel()
    if (pinnedOrFirst != null) {
      val result = providerRouter.generateResponseViaTier(
        input.copy(requestedRoute = pinnedOrFirst),
        MobileInferenceTier.MOBILE_REMOTE,
      )
      attempts += result
      if (!result.content.isNullOrBlank()) {
        firstContent = result.content
        // Don't try tier 3 if mobile served
      }
    }
  }

  // Tier 3 — Pinned Emergency (settings-configured fallback only)
  if (firstContent.isNullOrBlank()) {
    val emergency = settings.emergencyModel
    if (emergency != null) {
      val result = providerRouter.generateResponseViaTier(
        input.copy(requestedRoute = emergency),
        MobileInferenceTier.PINNED_EMERGENCY,
      )
      attempts += result
      if (!result.content.isNullOrBlank()) {
        firstContent = result.content
      }
    }
  }

  val exhausted = firstContent.isNullOrBlank()
  return DispatchReceipt(
    turnId = input.turnId,
    exhausted = exhausted,
    attempts = attempts,
    content = firstContent ?: "",
    truthfulCard = if (exhausted) "Home unreachable. Mobile provider returned no content. Tap to retry." else null,
  )
}
```

## Empty-state UI contract

```kotlin
@Composable
fun CommandScreen(homeReachable: Boolean, viewModel: MainViewModel) {
  Box {
    // Avatar SurfaceView layer — z = 0
    PurpAngolinOverlayActor(...)

    // Chat layer — z = 1, ALWAYS VISIBLE regardless of homeReachable
    Column {
      Spacer(Modifier.weight(1f))
      if (!homeReachable) {
        HomeOfflineBanner()  // "Home offline — replies will use mobile provider"
      }
      MessageList(messages = viewModel.messages)
      Composer(
        onSend = { text -> viewModel.dispatch(text) },
        enabled = true,  // ALWAYS enabled
      )
    }
  }
}
```

The composer is **never disabled**. The user can always type, edit, resend.

## Retry classifier

```kotlin
object RetryClassifier {
  enum class ErrorClass {
    TRANSIENT,            // 5xx, socket reset, DNS fail → retry next tier
    RATE_LIMIT,           // 429 → wait + retry, but only ONCE per tier
    TIMEOUT,              // deadline exceeded → retry next tier
    EMPTY,                // 200 OK but content.isBlank() → retry next tier
    UNSUPPORTED_CAPABILITY, // model can't do vision/audio → skip, try next
    STALE_AUTHORITY,      // 401/403 → refresh + retry once
    PROVIDER_DEAD,        // 5xx across multiple models → tier is dead, skip
    DETERMINISTIC,        // 4xx (not 408/429) → DO NOT RETRY
  }

  fun classify(httpStatus: Int?, exception: Throwable?, content: String?): ErrorClass {
    if (!content.isNullOrBlank()) return ErrorClass.EMPTY  // shouldn't happen here
    when {
      httpStatus == 429 -> return ErrorClass.RATE_LIMIT
      httpStatus == 408 -> return ErrorClass.TIMEOUT
      httpStatus in 500..599 -> return ErrorClass.TRANSIENT
      httpStatus in 400..499 -> return ErrorClass.DETERMINISTIC
      httpStatus == 401 || httpStatus == 403 -> return ErrorClass.STALE_AUTHORITY
      exception is SocketTimeoutException -> return ErrorClass.TIMEOUT
      exception is IOException -> return ErrorClass.TRANSIENT
      exception is HttpException -> return ErrorClass.TRANSIENT
    }
    return ErrorClass.EMPTY
  }
}
```

## Acceptance battery (on-glass, S25)

1. **Empty-state renders** — with Home at `192.168.55.203:7780` unreachable, app launch shows:
   - The avatar SurfaceView (z = 0)
   - The chat composer (z = 1)
   - The "Home offline — using mobile provider" banner above the composer
   - An empty message list below the banner
2. **Compose + send** — type "ping" in the composer → tap send → message appears in the list as a user turn.
3. **Tier 1 attempt** — `MainViewModel:V` logcat line `home_offline=true tier=HOME_RELAY skipped=reason=unreachable`.
4. **Tier 2 attempt** — logcat line `tier=MOBILE_REMOTE attemptedRoute=openrouter:<model>:free durationMs=<N>`.
5. **Tier 2 success** — assistant turn appears with content (assuming OpenRouter returns content for "ping"). Telemetry footer shows `tier=MOBILE_REMOTE`.
6. **Tier 2 empty → Tier 3** — if OpenRouter returns empty, logcat line `tier=PINNED_EMERGENCY attemptedRoute=<settings.emergencyModel>`.
7. **Exhausted** — if Tier 3 also empty, the **truthful error card** appears in place of the assistant turn:
   ```
   Home unreachable. Mobile provider returned no content. Tap to retry.
   ```
   NOT `[no visible reply from model]` as content.
8. **Receipt emission** — every attempt emits a `DispatchReceipt` with `tier`, `requestedRoute`, `finalRoute`, `durationMs`, `errorClass`. Receipt is persisted to Room and surfaces in the turn telemetry footer.
9. **Tap retry** — tap the truth card → re-runs the tier chain with the same input but fresh attempts. UI does NOT create a new turn; it reuses the existing turn ID.
10. **No ghost turns** — verify in DB: each user turn maps to at most one assistant turn, regardless of how many retry attempts occurred.
11. **Composer never disabled** — even when all tiers are exhausted, the composer accepts input. Type "test" → send → new user turn appears, fresh dispatch begins.
12. **No SIGSEGV / no crash loops** — full battery does not crash the app or trigger `AndroidRuntime: FATAL EXCEPTION`.

Until 12/12 PASS on glass, this lane is **NOT VERIFIED**.

## Risks / known unknowns

- **OpenRouter free pool churn** — operator pin or first free model may rotate; builder lane must re-resolve on every dispatch, not cache.
- **Vision / audio multimodal** — current ProviderRouter accepts text only. If Task #90 lands first, this lane must include attachment pass-through in Tier 2 / Tier 3.
- **ProviderRouter threading** — `generateResponse` is currently called from `viewModelScope`. The three-tier dispatcher must keep that contract or risk main-thread blocking.
- **Receipt persistence cost** — three receipts per turn × N turns = 3N DB writes. Use batch insert.
- **The 192.168.55.203 home IP** — currently hard-coded in the runtime config. Spec assumes builder lane reads from prefs and treats the literal IP as the default.

## Definition of done

- All 12 acceptance points PASS on S25 (RZCY9172MDP).
- Empty-state composer + banner render when `!homeReachable`.
- Three-tier dispatcher runs Tier 1 → Tier 2 → Tier 3 in order.
- ProviderRouter exposes `generateResponseWithFailover(input, tierChain)`.
- Truthful error card renders on exhaustion.
- No ghost assistant turns; original turn ID preserved.
- Composer never disabled.
- Triple-verified by an independent verifier agent with on-glass artifacts (logcat + DB inspection of receipts).
