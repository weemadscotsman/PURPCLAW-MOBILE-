---
name: AGENT_NOTES_TASK93_TELEMETRY_TRACE_SPINE
description: PurpClaw-wide causal telemetry / trace spine. One traceId + spanId lineage for every user/system operation across CLI, TUI, WebUI, Mobile, Voice, agents, tools, workers, providers, ZAMP, avatar, sensors, plugins, A2A, API. Records origin, decisions, expected vs actual, first divergence, inaction, error genealogy, cross-surface handoffs. Wraps every other spine.
type: project
---

# PurpClaw Causal Telemetry / Trace Spine — Lane #93 (2026-08-28)

## Why this lane exists

Pretty telemetry ("model + latency under the reply") is decorative. Real telemetry is a **causal forensic spine** running through PurpClaw so every action, failed action, skipped action, retry, fallback, permission denial, agent spawn, tool call, UI click, voice command, and silent no-op can be traced back to **where it started, what decided what, and where it first diverged from expected behaviour**.

It must be shared across **CLI, TUI, WebUI, Mobile, Voice, agents, tools, workers, providers, ZAMP, avatar, sensors, plugins, A2A, and API**. One trace format. One correlation model. Otherwise telemetry becomes four dashboards politely disagreeing about reality.

This lane wraps every other spine:

```
PURPCLAW
├── Turn Spine
├── State Spine
├── Memory Spine
├── Execution Spine
├── Authority Spine
├── Event Spine
└── TELEMETRY / CAUSAL SPINE   ← this lane
      ├── Trace IDs
      ├── Span IDs
      ├── Operation lineage
      ├── Decision receipts
      ├── Expected vs Actual
      ├── First divergence
      ├── Error genealogy
      ├── Inaction reasons
      ├── Cross-surface propagation
      ├── Recovery lineage
      ├── Verification evidence
      └── Root-cause analysis
```

## Governing laws

> **LAW T1 — TRACE OR IT DIDN'T HAPPEN.**
> No meaningful state transition, action, denied action, suppressed action, retry, fallback, cross-surface handoff, or final result exists without a traceable causal record linking it to its initiating event and first known divergence from expected behaviour.

> **LAW T2 — THE FIRST FAILING COMPONENT OWNS THE ROOT FAULT.**
> Downstream components record impact, not duplicate blame.

> **LAW T3 — INACTION IS A FIRST-CLASS EVENT.**
> Every "did not happen" must carry a `reason` field. Otherwise debugging "why didn't it do anything?" gets a shrug.

> **LAW T4 — TRANSPORT HOPS DO NOT MINT NEW ROOTS.**
> Cross-surface handoffs (`android → relay → core → worker → tool`) inherit the `traceId`/`operationId`. New spans, same root.

> **LAW T5 — REQUESTED ≠ RESOLVED ≠ SERVED.**
> Provider/model identity records all three. The model identity contract.

## Identity model

```text
traceId             globally unique root; one per user/system operation
rootSpanId          first span under traceId
spanId              unique per unit of work
parentSpanId        span that caused this span (null for root)
operationId         logical operation label (e.g. "chat.turn", "tool.filesystem.write")
sessionId           binding session
turnId              chat turn (when applicable)
jobId               worker/agent job
agentId             agent identity (when applicable)
toolCallId          tool invocation
receiptId           durable receipt persistence ID
```

Voice command example tree:

```text
TRACE tr_8fc2
  span_01 VOICE_INPUT
  span_02 STT              parent=span_01
  span_03 INTENT           parent=span_02
  span_04 AUTHORITY        parent=span_03
  span_05 ROUTING          parent=span_04
  span_06 AGENT_SPAWN      parent=span_05
  span_07 TOOL_CALL        parent=span_06
  span_08 VERIFICATION     parent=span_07
  span_09 REPLY            parent=span_08
  span_10 TTS              parent=span_09
  span_11 AVATAR_REACTION  parent=span_10
```

## Origin (precise, not "user")

```json
{
  "origin": {
    "type": "USER",
    "surface": "ANDROID",
    "node": "mobile-s25",
    "input": "VOICE",
    "component": "VoiceComposer",
    "sessionId": "ses_...",
    "timestamp": "...",
    "localSequence": 1841
  }
}
```

Valid origin types:

```
USER_UI, USER_VOICE, CLI_COMMAND, TUI_COMMAND, WEB_UI, MOBILE_UI, API,
A2A, AGENT, CHILD_AGENT, TOOL_CALLBACK, SENSOR_EVENT, CRON, PLUGIN,
SYSTEM_RECOVERY, PROVIDER_CALLBACK, MEDIA_EVENT, AVATAR_EVENT
```

This answers: "This started from a swipe on Android, not from the model." or "This was spawned by child agent 17 after tool retry 2." Distinction matters.

## Causal chain fields

Every event carries:
```
causedBy        spanId
triggeredBy     spanId
derivedFrom     spanId
supersedes      spanId
retries         [spanId, ...]
fallbackFrom    spanId
blockedBy       spanId
resumedFrom     spanId
```

Example:
```
voice.final          causedBy → voice.partial
intent.resolved      causedBy → voice.final
route.selected       causedBy → intent.resolved
provider.failed      causedBy → route.selected
route.fallback       fallbackFrom → provider.failed
tool.started         causedBy → route.fallback
tool.completed       causedBy → tool.started
```

## First-known break point

Formal concept, not a string:

```text
firstFaultSpan
firstDivergenceEvent
```

> EXPECTED: provider → response → tool → verify
> ACTUAL:   provider → malformed tool schema
> FIRST DIVERGENCE: span_17
>   type=tool.schema_validation
>   reason=missing required argument "path"

Output structure:

```text
ROOT CAUSE
FIRST SYMPTOM
DOWNSTREAM EFFECTS
RECOVERY ATTEMPTS
FINAL STATE
```

## Inaction (the clever bit)

```
agent.not_spawned               reason=TASK_TOO_SMALL
tool.not_called                 reason=ANSWERABLE_WITHOUT_TOOL
fallback.not_used               reason=MANUAL_MODEL_PIN
memory.not_written              reason=LOW_IMPORTANCE
avatar.action_suppressed        reason=HIGHER_PRIORITY_ANIMATION_ACTIVE
voice.not_spoken                reason=TTS_DISABLED
permission.not_requested        reason=EXISTING_STANDING_AUTHORITY
retry.not_attempted             reason=AUTH_FAILURE_NON_RETRYABLE
```

Lane #92 hook: when Tier 2 mobile fallback is skipped because a manual pin is set, that is `fallback.not_used reason=MANUAL_MODEL_PIN` — recorded in the receipt, not silenced.

## Decision receipts

Every important decision engine emits one:

```
IntentDecision, AuthorityDecision, RoutingDecision, ToolSelectionDecision,
AgentSelectionDecision, RetryDecision, FallbackDecision, MemoryDecision,
VerificationDecision, AvatarDecision, VoiceDecision
```

Shape:

```json
{
  "decision": "SPAWN_AGENT",
  "candidates": 14,
  "selected": "frontend-specialist",
  "rejected": [
    { "agent": "researcher", "reason": "capability_mismatch" }
  ],
  "policy": "task_specialist_v3",
  "confidence": 0.94
}
```

## Expected vs Actual

```json
{
  "expected": {
    "resultType": "FILE_WRITTEN",
    "tool": "filesystem.write",
    "maxLatencyMs": 5000,
    "verification": ["FILE_EXISTS", "CONTENT_HASH_MATCH"]
  }
}
```

Recorded as:
```
EXPECTED provider=MiniMax-M2.7
ACTUAL   provider=GLM
DELTA    fallback

EXPECTED toolCalls=1
ACTUAL   toolCalls=0
DELTA    tool never invoked

EXPECTED avatar=DANCE
ACTUAL   avatar=SIT_IDLE
DELTA    animation suppressed
```

This catches silent failures.

## Cross-surface handoff trace

```text
ANDROID       user says "check desktop build"
HOME RELAY    receives operation
DESKTOP CORE  spawns coding agent
WINDOWS TOOL  runs build
ANDROID       receives result
TTS           speaks result
GOTHIC/NEON   success animation
```

Same `traceId`, `operationId`. Transport hops are spans (`android → relay`, `relay → core`).

## UI telemetry

Lightweight events:
```
ui.click, ui.longpress, ui.drag, ui.navigate, ui.toggle, ui.submit,
ui.cancel, ui.retry, ui.open, ui.close, ui.resize, ui.focus, ui.blur,
ui.keyboard
```

But sampled. NOT every pixel of avatar dragging. Instead:

```text
avatar.drag.started       from=(x,y)      to=(x,y)        duration=...
avatar.drag.completed
avatar.scale.changed      0.72 → 1.14
```

## Voice timing (granular)

```
mic_open_ms
vad_start_ms
speech_duration_ms
vad_end_ms
stt_partial_first_ms
stt_final_ms
intent_ms
route_ms
provider_first_token_ms
tts_queue_ms
tts_first_audio_ms
barge_in_ms
total_turn_ms
```

Enables:
```
TOTAL      2410ms
VAD         180ms
STT         240ms
intent       24ms
route        31ms
provider   1680ms   ← first real bottleneck
TTS         190ms
audio        65ms
```

## Provider telemetry

Rolling health:
```
request count, success rate, failure rate, timeout rate, 429 rate,
5xx rate, empty response rate, malformed response rate,
tool-call validity, first-token latency, total latency,
tokens/sec, cost, fallback frequency, quality score,
last success, last failure, circuit state
```

Per-request:
```
REQUESTED MODEL
RESOLVED  MODEL
SERVED    MODEL
```

## Tool telemetry

```
tool.requested
tool.args_generated
tool.schema_validation
tool.authority_check
tool.executor_selected
tool.executor_health
tool.start
tool.progress
tool.stdout_stderr
tool.exit
tool.result_parse
tool.verification
tool.receipt
```

`TOOL FAILED` becomes:
```
firstFault: filesystem.write schema validation failed
not:       tool execution failed
```

## Agent telemetry

Per child:
```
spawn reason, parent, speciality, provider/model, authority lease, tools,
task, memory scope, start, progress, steering events, pause/resume,
tool calls, outputs, verification, finish, kill/cancel, handoff
```

Children linked to `rootTraceId`, `parentTurnId`, `parentJobId`. Council/swarm traces are trees.

## Memory telemetry

```
memory.recall
  query, layers searched, candidates, score, selected, rejected,
  token cost, source, age

memory.write
  candidate source, classification, layer, importance, evidence,
  retention, reason

memory.write.skipped
  reason=NOT_DURABLE
```

## Authority telemetry

```
requested capability
current mode
current authority
workspace scope
surface capability
OS permission
tool availability
result
```

Example:
```
filesystem.write
  operator authority   FULL_SYSTEM
  tool registry        PRESENT
  executor              HEALTHY
  workspace scope       ALLOWED
  OS permission         N/A
  RESULT                CALLABLE
```

Catches `MODEL CLAIM != CAPABILITY TRUTH`.

## Avatar telemetry

```
trigger
semantic action
requested clip
resolved asset
rig grade
skin status
clip
blend
start pose
end pose
duration
transition
fallback
return-home
```

Example:
```
AVATAR_ACTION DANCE
  origin: user voice
  resolver: DANCE
  asset: gothic_neon_outfit_03.glb
  clip: dance_07
  transition: sit_idle → stand → dance
  result: PASS
  return: composer_right / sit_idle
```

T-pose becomes:
```
FIRST DIVERGENCE
  asset.loaded
  skeleton=true
  skin=true
  animationClip=false
```

## Frog Wizard telemetry

```
frog_alert.shown
  sourceEvent: GATE_APPROVAL_REQUIRED
  trace: tr_8192
  asset: access_request_detected
  gateState: pending
```

He remains presentation of truth, not source of truth.

## ZAMP/media telemetry

```
command origin, device, media item, playback node, play/pause/seek,
handoff, volume, failure, decoder, network source, cross-device transfer
```

```
phone pressed Play
↓
desktop ZAMP started stream
↓
mobile UI updated
```

One trace.

## Error genealogy

```
errorId, errorClass, traceId, spanId, parentErrorId, rootErrorId,
firstSeen, lastSeen, count, surface, component, stack, inputs,
recovery
```

Repeated symptoms collapse to one root:
```
ROOT ERROR       PROVIDER_STREAM_RESET
CHILD SYMPTOMS
  → TTS queue empty
  → avatar speaking never started
  → reply marked incomplete
```

## Storage layers

```
HOT     recent live events          seconds/minutes    UI streaming
WARM    current sessions/jobs       hours/days
COLD    compressed trace archive    long-term forensic
SUMMARY aggregated metrics          health/rates/trends
```

Bounded raw high-volume traces.

## Live Trace Inspector (WebUI)

```
TRACE INSPECTOR

Origin
↓
Intent
↓
Authority
↓
Router
↓
Provider
↓
Agent
↓
Tool
↓
Verification
↓
Result
```

Green/amber/red spans. Click red:
```
FIRST KNOWN BREAKPOINT
  span_019
  Provider Stream
  MiniMax-M2.7
  HTTP 200
  stream opened
  stream closed before visible token

  Classification: EMPTY_STREAM
  Recovery: retry #1 failed, fallback GLM succeeded
```

## CLI / TUI access

CLI:
```
purpclaw trace <traceId>
purpclaw trace last
purpclaw trace --failures
purpclaw trace --first-fault
purpclaw trace --session <id>
purpclaw trace --agent <id>
purpclaw trace --tool <id>
```

TUI tree:
```
✓ USER
✓ INTENT
✓ AUTHORITY
✓ ROUTE
✕ PROVIDER MiniMax
✓ FALLBACK GLM
✓ TOOL
✓ VERIFY
✓ REPLY
```

Enter expands span details. Same data, different body.

## Mobile telemetry surface

Compact "Why? / Trace" sheet from any message/action:

```
ROUTE       MiniMax → GLM
WHY         MiniMax empty response
ACTION      1 retry
EXECUTION   ANDROID
TOOLS       3
AGENTS      1
RESULT      PASS
```

Advanced Trace opens the full causal chain.

## Automatic root-cause engine (later)

TraceAnalyzer compares expected state graph vs actual trace. Output:

```
FIRST BREAK: Provider stream produced zero visible tokens.

DOWNSTREAM:
  TTS had no content.
  Avatar remained THINKING.
  UI displayed no-visible-reply placeholder.

ROOT:    MiniMax provider adapter / stream parsing.
NOT ROOT: TTS, Avatar, Composer.
```

## Lane #92 (#93) integration: existing receipt shape

The current `core/model/DispatchReceipt.kt` already carries some of this. Extend:

```kotlin
data class DispatchReceipt(
  val turnId: String,
  // EXISTING fields
  val requestedModel: String,
  val resolvedProvider: String,
  val routingReason: String,
  val fallbackPath: List<String>,
  val durationMs: Long,
  // NEW for Lane #92/#93
  val traceId: String,
  val rootSpanId: String,
  val spanId: String,
  val parentSpanId: String?,
  val tier: MobileInferenceTier?,
  val mobileFallbackFired: Boolean,
  val errorClass: RetryClassifier.ErrorClass?,
  val firstDivergenceSpanId: String?,
  val firstDivergenceReason: String?,
  val expectedProvider: String?,
  val actualProvider: String?,
  val inactionReasons: List<String> = emptyList(),
  val surfacedAs: String? = null,        // "reply" | "truthful_error_card" | "no_visible_reply"
  val causalChain: List<CausalLink> = emptyList(),
)

data class CausalLink(
  val fromSpanId: String,
  val toSpanId: String,
  val relation: String,                  // causedBy | fallbackFrom | blockedBy | ...
)
```

The receipt is durable forensic evidence. Each tier attempt emits its own span; the parent span references them.

## Lane #91 (#93) integration: avatar telemetry

`Avatar3DActor.kt` already emits `applyTransform` / `mood=` lines. Extend to the avatar schema:

```kotlin
data class AvatarTrace(
  val spanId: String,
  val parentSpanId: String?,
  val mood: PurpAngolinMood,
  val character: String,
  val requestedClip: String,
  val resolvedAsset: String,
  val rigGrade: String,                  // "biped-meshy-v3" etc.
  val skinStatus: String,                // "loaded" | "missing" | "fallback_2d"
  val animationCount: Int,
  val animationPlaying: Boolean,
  val firstDivergence: String?,
  val transition: String?,               // "sit_idle → stand → dance"
)
```

Logcat tag: `Avatar3DActor` retains; JSON line for the Live Trace Inspector.

## Lane #90 (#93) integration: media telemetry

```kotlin
data class MediaTrace(
  val spanId: String,
  val parentSpanId: String?,
  val source: MediaSource,
  val mimeType: String,
  val byteSize: Long,
  val sha256: String,
  val captureLatencyMs: Long?,
  val thumbnailLatencyMs: Long?,
  val mirrorMediaStoreId: Long?,
  val fileProviderAuthority: String,
  val fgsType: String?,                  // "cameraType" | "microphoneType" | null
  val firstDivergence: String?,          // "permission_denied" | "fgs_type_mismatch" | ...
  val inactionReasons: List<String> = emptyList(),
)
```

## Acceptance battery

1. Every user turn has exactly one `traceId`; chat.bubble and its assistant reply share it.
2. Every Tier 1/2/3 attempt emits a span under that traceId with `tier`, `errorClass`, `durationMs`.
3. Manual-pin path records `fallback.not_used reason=MANUAL_MODEL_PIN` when Tier 2 would have run.
4. Avatar mood change emits a span with `requested_clip`, `resolved_asset`, `first_divergence` when SIGSEGV quarantines a clip.
5. Media capture emits a span with `mimeType`, `sha256`, `mirrorMediaStoreId`, `first_divergence` if any.
6. Cross-surface handoffs (Mobile → Home Relay → Core) preserve `traceId`; new spans for each transport hop.
7. CLI `purpclaw trace <id>` returns the same chain shown in Mobile's Advanced Trace.
8. Live Trace Inspector renders the tree with green/amber/red colour coding.
9. Inaction reasons queryable: `SELECT spanId, reason FROM spans WHERE type='inaction'`.
10. No trace omits `origin` or `expected_vs_actual` once those become applicable.

## Out of scope (deferred)

- WARM/COLD archival and TTL rotation — separate lane.
- TraceAnalyzer auto-correlation — separate lane.
- Frog Wizard upgrades beyond provenance recording — separate lane.

## Files in scope (read-only this session; builder lane implements)

### Must read first
- `app/src/main/java/com/example/core/model/DispatchReceipt.kt` — current receipt shape.
- `app/src/main/java/com/example/core/runtime/ProviderRouter.kt` — where Tier 1/2/3 spans live.
- `app/src/main/java/com/example/ui/MainViewModel.kt` — where the receipts are emitted today.
- `app/src/main/java/com/example/ui/components/Avatar3DActor.kt` — avatar emission points.
- `app/src/main/java/com/example/media/` (to be created in Lane #90) — media capture emission points.

### To write
- `app/src/main/java/com/example/telemetry/TraceContext.kt` — global traceId + spanId holder.
- `app/src/main/java/com/example/telemetry/TraceSpan.kt` — span type with start/end, parentSpanId, errorClass.
- `app/src/main/java/com/example/telemetry/CausalLink.kt` — link types (causedBy, fallbackFrom, blockedBy, ...).
- `app/src/main/java/com/example/telemetry/InactionReason.kt` — typed reasons (TASK_TOO_SMALL, ANSWERABLE_WITHOUT_TOOL, ...).
- `app/src/main/java/com/example/telemetry/DecisionReceipt.kt` — base shape for IntentDecision, RoutingDecision, etc.
- `app/src/main/java/com/example/telemetry/ExpectedVsActual.kt` — contract declaration + delta.
- `app/src/main/java/com/example/telemetry/TraceStore.kt` — Room-backed persistence (hot/warm tiers).
- `app/src/main/java/com/example/telemetry/FirstDivergenceDetector.kt` — scans span tree, emits root cause.
- `app/src/main/java/com/example/telemetry/ErrorGenealogy.kt` — collapses repeated symptoms to root.
- `app/src/main/java/com/example/ui/components/WhyTraceSheet.kt` — Mobile "Why? / Trace" surface.
- `app/src/main/java/com/example/ui/components/AdvancedTraceSheet.kt` — full causal chain sheet.

## Definition of done

- All 10 acceptance points PASS on S25 (RZCY9172MDP).
- `traceId` present on every dispatch receipt.
- Three-tier dispatcher (Lane #92) records tier, errorClass, expected vs actual.
- Avatar lift (Lane #91) records rig, clip, skin status, transition.
- Media lane (#90) records capture latency, mirror ID, FGS type, first divergence.
- Cross-surface handoff preserves `traceId`.
- Inaction reasons queryable from `TraceStore`.
- Live Trace Inspector renders tree.
- Triple-verified by an independent verifier agent with on-glass artifacts (logcat JSON spans + DB inspection).
