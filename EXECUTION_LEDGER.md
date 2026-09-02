# PURPCLAW MOBILE EXECUTION LEDGER

**Canonical. This file outranks conversational memory.** Every new session reloads this
before doing anything else. Compaction may murder conversation history; this file is the
work state. Update it whenever a task changes status — a task not reflected here does
not exist.

Last updated: 2026-09-02 Europe/London
Session: P0 failure-driven fixes — pin truth, status-box law, browser ownership, finalizer gate, capability-count truth, honest LOCAL lane; Gradle wrapper repaired; APK installed + boot-smoked on S25 RZCY9172MDP

## 2026-09-02 ACTIVE CHECKPOINT — TRUTH FIXES, WRAPPER REPAIR, BOOT SMOKE

### Build system
- **Gradle wrapper was hand-broken**: `gradlew.bat` bypassed the wrapper and ran
  `-jar ..\lib\gradle-gradle-cli-main-9.3.1.jar` (parent-dir lib), whose classpath
  could not resolve `org.gradle.launcher.bootstrap.ProcessBootstrap`. The cached
  `gradle-9.3.1-bin` distribution was also half-swapped (launcher jar modified
  2026-09-02 02:52, missing ProcessBootstrap). Repair: purged the dist cache,
  restored stock `gradlew.bat`/`gradlew` from gradle/gradle v9.3.1 (broken copies
  kept as `gradlew.bat.handrolled.bak` / `gradlew.handrolled.bak`). First real
  build: `BUILD SUCCESSFUL in 4m 47s` (fresh distribution download).

### Fixes landed (all in this build)
- `ProviderRouter.kt` — triple-prefix pin fix (stripped embedded provider prefix
  from unresolved manual pins; restore log now reads `auto/AUTO`, previously
  `groq/groq/qwen/qwen3.8-27b`). **NO SEED AUTHORITY LAW**: live `/models`
  failure, exception, or EMPTY listing now leaves the catalogue EMPTY — the old
  hardcoded seed lists (qwen-plus/kimi-moonshot/gpt-4o/glm-4.5/...) can no longer
  pose as a live catalogue. MiniMax allowlist re-labelled truthfully
  (`attested_allowlist_2026-08-29`; MiniMax exposes no chat /models). Failure
  classes extended: `DEAD_ENDPOINT`, `TOOLS_UNSUPPORTED`, `PROMPT_TOO_LARGE`,
  `CONTEXT_TOO_SMALL` (NIM dead function-UUIDs, Groq tool-schema + context walls
  now typed, rotate the candidate, never kill the AUTO turn). Receipt carries
  requested → resolved → served with per-attempt records.
- `AssistantTurnCard.kt` — **STATUS BOX LAW (operator, 2026-09-02)**: thinking /
  reasoning renders INSIDE the status box (animated kaomoji thinking face in the
  header + token count), never as a fake reply line in the chat body; reasoning
  visible while streaming (box collapsed by default). Routing-receipt chip proves
  `MODE · profile · PIN provider/model · resolved[/model] · SERVED provider/model`
  when served ≠ resolved.
- `ToolRuntimeEngine.kt` — **browser ownership law**: `android.browser.embed` is
  PurpClaw's own browser and the default for built artifacts and "open in your
  browser"; `android.browser.open` is EXTERNAL-only (Chrome etc.), workspace
  file:// and an explicit `embed:true` arg redirect to the embedded pane.
- `MainViewModel.kt` — **finalizer gate**: `VerificationStarted/Passed/Failed`
  now emitted from real receipts (ARTIFACT_WRITTEN / ARTIFACT_PREVIEW_VERIFIED /
  RECEIPT_SIGNED / RESPONSE_DELIVERED + failed tool list) before
  WorkCompleted/WorkBlocked — the live card can no longer strand mid-lifecycle
  ("6/7 verified in OBSERVING"). Council seat failure categories extended to the
  new typed classes.
- `PurpCoreLifecycleService.kt` — **capability-count truth law**: broker counts
  are taken AFTER `syncAllAndroidPermissions()` completes (was counted before
  sync → "0 active" while camera/apps/network demonstrably worked), and the
  label names what it counts ("N broker caps granted (comms/doc set)") — camera/
  mic/network are tracked by their own engines and are not in this number.
- `LocalModelHost.kt` — **anti-mock law**: the canned "Local on-device inference
  response…" fake text is gone; the LOCAL lane now fails with a typed
  `LOCAL_OFFLINE_NOT_IMPLEMENTED` error (no on-device inference engine is
  bundled), capabilities list honest-empty, benchmark refuses to fabricate
  numbers.
- `SharedQuotaLedger.kt` — the four new typed failure classes + classification.

### Physical evidence (S25, serial RZCY9172MDP)
- arm64 APK 160,806,174 B, SHA-256 `eff9ac25c1b0b246096226b5721b80b9fafe3f2a03d29ee5af86cf1a651ea0a1`,
  installed 2026-09-02 ~12:50, cold launch clean.
- Boot smoke: 0 FATAL / 0 ANR; all catalogues live-refreshed at boot (NIM 46,
  OpenRouter 21 free of 421, Groq 10, Cerebras 2, Google AI 36, Cloudflare 65;
  missing-key providers skip honestly); `StartupSelfCheck passed=true caps=23
  perms=5/6`; PurpCore notification shows the new truthful wording.

### OPEN P0 (honest — none of these are certified)
- Manual pin on-glass receipt pass: operator must drive a pinned chat turn and
  read PIN = resolved = SERVED on the receipt chip (build ships the proof
  surface; the pass needs the phone in hand).
- ANR root-cause profiling; battery/screen-off survival certification (battery
  optimisation deliberately left ON for hostile testing — do not grant the
  exemption before certification).
- Browser-ownership E2E TVG and the full website-build TVG on device.
- Artifact URI resolution for local workspace files; provider output parser
  (reasoning/tool markup → hidden reasoning + real ToolInvocation).
- Structural: PermissionCentreScreen built but unmounted; agents_registry.json
  (91) + skills_registry.json (30) parsed by nothing; AgentTowerManager still
  carries fabricated mission data (anti-mock violation); artifact center DB
  dead code; context compression absent; boot overlay timer-driven; no
  on-device inference engine.

## 2026-09-01 ACTIVE CHECKPOINT — KEYBOARD, CHAT, ROUTER RECOVERY, AVATAR

- Exact live root cause for the broken Samsung keyboard was not target-SDK folklore:
  Android InputDispatcher rejected Honeyboard touches because the full-screen on-top
  avatar `SurfaceView` obscured the IME (`BLOCK_UNTRUSTED`, `NO_INPUT_CHANNEL`).
- `PurpAngolinOverlayActor` now hides the renderer window for the IME lifetime and
  shows the Compose-native identity projection instead. Filament is not resized during
  the keyboard animation. When IME closes, the 3D renderer returns with the persisted
  transform.
- Exact final arm64 APK SHA-256:
  `0D84E6A4A808FB6B60EB8C17EDAC68F9EE085441CCC35E4F54BBAB9767EE0FF1`.
  Build/install/cold launch PASS on Samsung tablet `SM-X400`, serial `R52YA0ECHSH`.
- Physical keyboard proof PASS: direct Honeyboard key taps produced `qwe`; logcat after
  the taps contained zero `Untrusted touch` / `Dropping untrusted touch` events. Send
  accepted the message and chat returned a real model reply.
- Avatar visibility root cause fixed: a newer framing multiplier trusted the compact
  GLB's false ~0.017m bind-pose bounds even though its animated skinned mesh is already
  authored at normal metre scale. Restored the last physically proven source-scale
  framing and removed the false 97x correction.
- Seated/turned animation silhouette exceeded the former 150dp clamp envelope. The
  canonical envelope is now 260dp at scale 0.40, so full head, hands, knees and boots
  remain inside the right edge on SM-X400.
- Physical semantic animation proof PASS for four real fully-skinned assets/states:
  `thinking -> Confused_Scratch`, `speaking -> Agree_Gesture`,
  `success -> Happy_jump_f`, `idle -> Chair_Sit_Idle_F`. Screenshots:
  `C:/Temp/purpclaw-tablet-3d-thinking-proof.png`,
  `C:/Temp/purpclaw-tablet-3d-speaking-proof.png`, and
  `C:/Temp/purpclaw-tablet-3d-idle-after-reply.png`.
- AUTO recovery proof PASS for ordinary chat: OpenRouter returned a typed 404 because
  the candidate did not support the requested Android tool schema; the same turn
  continued and completed through NVIDIA NIM. Final telemetry identified the concrete
  NIM model instead of `AUTO -> none`.
- **OPEN P0:** historical Podcast/Council rows visibly fail in 18–43ms with typed
  `PROVIDER_EXCEPTION`. Shared provider capability filtering/fan-out recovery remains
  unverified for Podcast and must not inherit the ordinary-chat PASS.
- **OPEN P0:** model prose still stated stale/invented runtime counts (13 tools / 9
  agents) while the route telemetry was truthful. Runtime inventory claims must be
  injected from verified observations or omitted, never improvised by the model.
- **OPEN:** physical avatar spin, hold-drag, pinch limits and all remaining semantic
  states (listening, working/tool, error, greeting/dance) require exact-build retest;
  prior receipts are historical and do not certify this APK.

## 2026-08-31 ACTIVE CHECKPOINT — VOICE, STEERING, LIVE WORK CARD

- Constitution preflight repaired for the reorganized canonical docs tree;
  `purpclaw constitution validate` now returns VALID.
- Kokoro no longer depends on Android native TTS initialization. Exact arm64 APK built,
  installed on physical S25 `RZCY9172MDP`, and Read Aloud played a real 24 kHz WAV from
  the app cache through MediaPlayer. Log route: `voice_provider=kokoro`, 2.4 s playback,
  clean completion/release, no fatal crash. Screenshot:
  `C:/Temp/purpclaw-s25-kokoro-proof.png`.
- Added durable steering sessions anchored to the active WorkSession/turn/checkpoint.
  Raw messages persist verbatim before compression; 1200 ms burst quiet-window,
  SteeringDelta, plan revision, cancellation, before-tool, after-tool/resume and
  before-final hooks are integrated. A late steering delta that requests tools now
  re-enters a bounded same-turn tool loop; it is never acknowledged as complete merely
  because the first draft existed. Crash persistence now includes the compiled delta.
- Root cause of the visually stale WorkSession card found: `activeWorkServiceTurnId`
  was nulled before the later `WorkCompleted` emission guard, making completion events
  unreachable. Completion/block events now emit before ownership clears.
- One PreviewCardEvent spine now projects `PLANNING -> EXECUTING -> OBSERVING ->
  VERIFYING -> RESPONDING -> COMPLETED`. Foreground status, card and companion consume
  that same phase projection. Continuation-loop tools now emit the same typed card
  events as direct tools.
- Live card now retains expandable per-call tool receipts: real call ID, tool name,
  RUNNING/VERIFIED/FAILED, evidence reference or exact failure. Direct tool start/end
  now reuse one call ID instead of generating mismatched timestamps.
- Targeted WorkSession projection test covers planning, execution, observation,
  response and durable completion plus tool receipt transitions.
- Flagship acceptance queue is now explicit:
  1. Home-offline phone-sovereign `PURPCLAW S25 FIELD CONSOLE` build.
  2. Home-connected real multi-agent `PURPCLAW FIELD STATION` build with actual Souls,
     registry-backed skills, non-overlapping ownership, merge gates, QA/rework and
     final runnable project.
  3. Twenty identical voice turns with eight latency timestamps; turn 20 must not
     trend slower than turn 2 and STT/TTS ownership/queues must return to clean idle.

---

## COUNTS (LEGACY NUMBERED ROWS)

```
REGISTERED STATUS ROWS: 67 (legacy task numbers preserved; highest assigned ID is 69)
DONE:    43
ACTIVE:  9
PENDING: 15
BLOCKED: 0 (physical S25 is attached)
```

The former `TOTAL: 63` header was stale: the table already contained later tasks
#64–#69. Counts above reflect the actual status rows, not the highest task number.

The 2026-08-30 NOSKIM intake adds a cross-cutting P0 checklist rather than inventing
new legacy numbers for already-overlapping work. Reconcile these counts only after
mapping every checklist item to its existing numbered owner.

## 2026-08-30 CURRENT TRUTH CHECKPOINT — SUPERSEDES STALE LOWER STATUS TEXT

Source read completely, line-by-line: 2,724 / 2,724 lines from
`2a1c0f05-fae5-4948-b038-fa4eb7242647/pasted-text.txt` (173,070 bytes). The source
condenses 100 archived files, 93 SHA-256-unique files and seven exact duplicates.

### Exact combined APK proved on physical S25

- APK SHA-256: `EB7482EDF121FAAEB4461289E2546A880D3B8FD0B8567750A2A189F76ED596C1`.
- Build: targeted Android unit tests + `assembleDebug` PASS.
- Install: `adb install -r` PASS on `RZCY9172MDP`.
- First launch exposed a real constructor-order crash: persisted WORK called
  `armExecutionLease()` before `_activeLease` existed. Initialization order was fixed,
  rebuilt and reinstalled; do not regress this exact cold-launch case.
- Repaired launch: PID `23021`, no fatal AndroidRuntime entry after launch/touch tests.
- WORK restored automatically with no `Grant (5m)` dialog or duplicate in-app gate.
  Android OS permissions remain authoritative.
- Boot catalogue receipt: NIM 64 models; OpenRouter 396 total / 21 free; boot stamp lists
  OR, NIM, MiniMax, LongCat, Kimi, Qwen, DeepSeek, OpenAI and Z.ai. Missing-key direct
  providers were truthfully skipped. LongCat boot route was included; its authenticated
  catalogue and completion had already passed live core probes.
- Composer touch PASS: keyboard opened, `TVG_touch_proof` entered, Send became active;
  proof text was cleared afterward so the operator's phone was left usable.
- Keyboard/avatar coexistence PASS: full skinned avatar stayed visible and clamped up.
- Avatar spin PASS: persisted yaw `0.0 → -71.47793`.
- Avatar hold-drag PASS: persisted position `(-31,0) → (-679,-747)` while yaw remained
  `-71.47793`; this proves movement is viewport-space rather than chat-row space.
- App remained PID `23021`; no touch crash. Avatar is deliberately left at the tested
  middle-left position and can be moved back by the operator/Reset control.

### Shared provider/quota work proved this session

- Core dispatch now crosses one shared governor for CHAT, WORK, VOICE, PODCAST/council,
  children, retries and real LLM dispatch; NIM has a 40 RPM hard ceiling with an
  operating ceiling of 36.
- Shared sliding window/token bucket, concurrency cap, priority queue, dedupe, storm
  guard, Retry-After, jittered backoff, circuit/quota states and secret-safe telemetry
  have targeted passing tests (8/8 across governor + provider failover).
- `PROVIDER_QUOTA_EXHAUSTED` is distinct from transient 429 and carries reset-until.
- OpenRouter OAuth PKCE is implemented with Android Keystore storage and callback
  wiring. It is compile/device-launch proven but remains **LIVE AUTH UNVERIFIED** until
  an operator completes the real provider login.
- OpenAI and MiniMax remain honest API-key integrations; no fake OAuth is advertised.
- Remaining quota gap: Android catalogue/health probes still need explicit acquisition
  through the same shared mobile ledger; boot refresh currently performs provider HTTP
  discovery directly.

### NOSKIM master coverage — all requirements retained

The recovered source covers: UI/sidebar regression; organism/runtime law; CHAT/WORK
authority; async children; tools/skills/drivers; seven-layer memory; USER/MEMORY/
checkpoint/event separation; provider registry/AUTO/catalogue quality; shared quota;
routing telemetry; receipts/graveyard; podcast/council lifecycle; Soul/provider split;
responsive UI parity; truthful Settings; file/project intake; voice/TTS/ZAMP; camera/
vision/sensors; avatar/Mochi/PetPack; onboarding/adoption; widgets/browser/app-use;
steering/context; scoped agent self-inspection; capability evolution; installer/first
run; live update; security/vault; TVG; Android background/process reality; historical
pass/fail truth; council epistemics; and the complete 93-file source coverage ledger.

### Canonical practical next-build queue (source lines 2698–2718)

1. **P0 ACTIVE:** restore New Chat, Chats/history, Settings and every missing navigation
   destination; first inventory `OLD ITEM → REAL ROUTE/ACTION → NEW HOME → PRESERVED → TESTED`.
2. Build one canonical navigation definition consumed by Desktop and Mobile.
3. Bind Settings rows to real state or explicitly show unavailable/degraded.
4. Provider catalogue/AUTO exact APK physical proof — **PASS for launch/catalog refresh;
   deeper chat fallback execution remains active verification, not blanket green**.
5. Validate NIM chat classification so code/completion models never enter chat pools.
6. Validate `openrouter/free` primary/free policy and bounded fallback rotation.
7. Persist provider/model health quarantine so dead routes are not immediately reused.
8. Unify podcast/global busy lifecycle.
9. Add Home-offline circuit TTL.
10. Finish seat lifecycle/quorum/degraded operation.
11. Correlate router → tool → agent → receipt → memory → verification → UI trace IDs.
12. Give council roles scoped evidence-query tools/evidence packs.
13. Enforce quantitative factual claims require evidence IDs or become UNVERIFIED.
14. Separate event/task history from learned memory derivation.
15. Finish missing seven-layer writers/cross-layer recall in the existing memory spine.
16. Run the remaining physical-device TVG matrix on each exact final artifact.
17. Only after P0 truth work: avatar exclusion/layout refinements and cosmetics.

### Navigation P0 device receipt — 2026-08-30 00:11

- Exact APK SHA-256: `25E783E4EBE0A63BCD016B04E585CE51D103E66AEE655F0112C059FE6A13080F`.
- Build/unit/assemble PASS; install-replace PASS; cold launch PID `27435`; no fatal crash.
- Chat now exposes a professional Menu icon instead of hiding navigation behind the
  companion paw glyph.
- On-glass sheet proves New Chat, Recent Chats and all already-real mobile surfaces are
  reachable. Settings is visible below the scroll fold.
- New Chat changed persisted session from `ses_canonical_01` to
  `ses_mtezur3e_0bbeb4` without deleting the old event-spine turns.
- Selecting the 153-turn Recent Chat restored persisted session
  `ses_canonical_01` and its exact visible transcript; PID remained `27435`.
- Source map: `purp mobile/NAVIGATION_INVENTORY_2026-08-30.md`.
- Status: **New Chat + Recent Chat restoration PASS on S25**. Shared Desktop/Mobile
  navigation manifest, Scheduled owner, dedicated union Library, plugin projection,
  media/player and profile parity remain PARTIAL/OPEN.

### Moldyspot case law #001 — declaration vs observation

An eviction/usage notice is authoritative for the moment it is observed, not permanent
runtime truth. Later successful execution is fresher evidence, but does not prove why
the state changed. Preserve both timestamped records; do not retroactively label the
earlier declaration stale without evidence. Operational truth follows the newest
reproduced, verified observation. The same decay rule applies to old PASS states.

### Council identity/audit correction retained

- Protect the separation `model != Soul/identity != memory != authority != capability != execution`.
- Stable identity material should become a compact immutable header plus scoped dynamic
  memory/runtime retrieval, not a blind multi-thousand-token injection every turn.
- Runtime state remains authority for device/capability/permission/route truth.
- Consequential actions require request → decision → executor → permission → execution
  → verification → result correlation.
- Podcast seats created only by prompting are labelled persona simulation. They become
  runtime council agents only when resolved/spawned through the real Soul/agent registry.

## 2026-08-28 FULL HANDOFF RECONCILIATION (NO-SKIM)

Source recovery completed line-by-line from both supplied handoffs: 8,943 lines in
`07cb7b84-4285-48a5-8534-c19077ba3047/pasted-text.txt` and 1,410 lines in
`828c575b-6e92-47dc-94bb-589f80d15750/pasted-text.txt`. Repeated transcript text was
still read; no sections were sampled or skipped.

Immediate build order:

1. Restore useful AI replies: AUTO must resolve a configured phone fallback when Home
   is unavailable; an emergency-routing refusal is not a successful assistant reply.
2. Speak the final rendered assistant text and retain user/assistant turns in order.
3. Admit only GLBs with both a skin and animation catalogue, enumerate runtime clips,
   then loop exactly one safe seated idle (`Chair_Sit_Idle_F`) before any mood clips.
4. Build, install in-place, launch, inspect crash logs, send a real chat turn, and
   capture physical S25 proof. A source edit or successful APK build is not device PASS.

Recovered operator-pinned product queue (existing numbered rows remain canonical):

- mobile screenshot audit; back-to-chat from every surface; professional iconography;
  model selectors/AUTO routing/voice reply; seven-layer agent memory; podcast ten-seat
  mutable roster, 10–20 minute target, transcript/audio export, break mode, continuity;
  non-chat UI unification; layouts/text audit; explicit council intent; rude-score and
  tribunal-receipt protocol; agent-as-seat; Purp identity files; BIOS/POST; guest queue.
- cross-surface parity: NoSignups catalogue adapter/widget, cockpit reply-status bar,
  reply-status regression battery, BIOS visual certification, per-bubble action rails,
  TUI seat picker, agents-runtime bridge repair, access-resolver defer-as-allow repair,
  dispatcher `SEND_AGENT` context routing, web cockpit hover rail, mobile bottom-sheet
  renderer, Step 11 verifier, Step 12.14 five-dispatch reconstruction, and Step 12.15
  independent triple verification. The NSIS EXE wrapper remains explicitly deferred.
- identity/provider account architecture: PurpClaw identity is separate from provider
  connections; OAuth/device flow first, secure key import fallback, encrypted/revocable
  secrets, short-lived scoped device sessions, dynamic model/capability discovery.
- privacy/product telemetry: anonymous install/account/session/active-user/device and
  feature-outcome/reliability signals only; never prompts, replies, memory, files,
  credentials, or personal content; Privacy & Diagnostics controls/event viewer/delete;
  separate opt-in Help & Feedback; Product Pulse DAU/WAU/MAU, retention, funnel,
  adoption, performance, and crash views.
- mobile routing/settings correction (operator clarification 2026-08-28): Home is an
  optional offload node only, never the default chat or WORK dependency. Mobile WORK
  must actively use supported tool/function calls and coding capabilities from free
  models. NIM and OpenRouter catalogues are live/self-updating and feed one capability-
  aware AUTO router. The model registry is the single source of truth for chat, tools,
  function calling, coding, vision, reasoning, context, health, and availability.
  Replace mocked/stub/duplicated settings with one deep, truthful, professional and
  actionable settings surface; every control must read/write the real backend state.
- single-navigation Lane #100 source mutation is present (compact bottom dock is the
  sole primary navigation; OVERFLOW keeps MODE + COMPANION only), but build/device
  certification was interrupted and remains open.
- boot sting remains parked: play the approved PurpClaw line only on fresh install or
  major update, never on every launch.
- native Android Picture-in-Picture Mini Mode: minimizing an active Chat, WORK, or Live
  Drive session collapses the activity into a movable system PiP surface with identity/
  avatar, short current state, pause, stop, and expand controls. PiP is a view only;
  `LiveDriveSession` + foreground service own continued execution independently of the
  activity. Distinguish hiding the mini UI from stopping the job. IDLE, WORKING,
  SPEAKING, and CONFIRMATION presentation states are required; consequential review
  expands the full app before execution. Native PiP lifecycle/positioning is preferred
  over a custom overlay.
- avatar asset certification clarification: the source pack contains 189 ready-made,
  skinned/rigged animated GLBs that work on desktop. Android must select only a compact
  state set (sit/idle, listen, think, speak, work, success, error, greet) from those
  files and certify each independently on the S25. Do not ship all 189 and do not rely
  on the current 20-clip merged GLB: it visibly animates but continuous playback causes
  a confirmed `libgltfio-jni.so` SIGSEGV on Adreno 830. Only hardware-stable assets may
  enter the mood controller; a failed asset is quarantined, never replaced by T-pose.

The older screenshots referred to “another five pending” without exposing their names.
They are recorded as five unresolved historical slots; names will be recovered from
repo evidence rather than invented.

Additional source read in full: 355 lines / 14,504 bytes from
`caeb5a43-f146-462b-9a22-bcc25aedd027/pasted-text.txt`. It is an older execution
snapshot, not current truth. Its podcast memory/roster/transcript/break/continuity
items map to completed rows #65–#69. Still-open items map to existing rows #3, #12,
#13, #20, and #58 plus the separately recorded cross-surface reply/status work. No
duplicate task rows were created. Its historical phone-call/background-process notes
are evidence context only and do not override current S25 crash/touch receipts.

## CURRENT

#63 Mobile 3D avatar overlay — 2026-08-28 00:48Z physical cert + 01:27Z follow-up:

## Step 1 cert (00:48Z) → DEGRADED → Step 2 patch sent → operator applied → 01:18Z retest:

* Static-pose guard PASS (no libgltfio-jni SIGSEGV on Adreno 830).
* Framing math PASS (`RIG_HALF_HEIGHT=0.825`, `framingScale=1.2121212`).
* Persisted offset sanitisation FAILS to recover from `layout_version=5` install.
* Operator applied the 5-line patch (layout_version 5→6 + tighter world-units clamp
  + LaunchedEffect world-bounds check + reset chip anchored outside rig offset).
* Build/install/launch succeeded; PID 13523 alive post-01:18Z relaunch.

## Step 2 visual cert (01:25–01:27Z) — THE RIG IS VISIBLE:

* `purp_focused.png` 01:27 — PurpClaw surface: NO 3D body on screen yet, but
  `applyTransform` lines flood logcat with `tx=-0.58, ty=0.42 → tx=-0.45, ty=-0.02`
  drift, confirming the **gesture handler is firing** and recomputing `translationX/Y`
  per frame. This means the rig IS being driven — just not visible in the captured
  shot because of focus timing or because the gesture-driven lerp is moving the rig
  through the visible region during the capture window.
* `.tmp/purp_avatar_fullbody.png` 13:57 (yesterday's accidental capture, ChatGPT
  focused) — the canonical Gothic/Neon Meshy character **head-to-toes in T-pose**,
  purple hair + boots + outfit clearly visible. That is the rig at the right scale,
  centred, fully framed. So the renderer + camera + framing math is correct.
* **Operator verdict (2026-08-28 01:25Z):** "There she fucking is. That is the right
  girl now. The asset identity problem is basically settled." ✅

## CURRENT — 01:28Z — animation playback, NOT 3D render:

The rig is rendering correctly. What is **missing** is `playAnimation()` actually
running on the bones. The static-pose guard at `Avatar3DActor.kt:324-330` deliberately
disables animation playback because libgltfio-jni.so was SIGSEGV-ing on Adreno 830
when `playAnimation()` drove the rig's skeleton (see the comment block at lines
315-323 referencing the S25 crash 2026-08-27). The `mood=` log line is missing from
the captured logcat — only `applyTransform` lines run. The rig is therefore sitting
in its bind/T-pose, which is exactly what the operator sees.

## GLB animation inventory (enumerated 2026-08-28 01:25Z from on-disk binary):

```
purpangolin.glb  (21,274,940 bytes, glTF 2.0, Khronos Blender I/O v4.2.57)
  skin=1, meshes=1, primitives=1, nodes=26, animations=20
  [00] Agree_Gesture          8.17s  72ch
  [01] All_Night_Dance        5.33s  72ch
  [02] Angry_Ground_Stomp_1   3.17s  72ch
  [03] Angry_Ground_Stomp_2   1.03s  72ch
  [04] Angry_Stomp           13.00s  72ch
  [05] Backflip_and_Hooks     4.30s  72ch
  [06] Big_Wave_Hello         2.47s  72ch
  [07] Boxing_Guard_Right_Straight_Kick 10.00s 72ch
  [08] Boxing_Warmup          1.40s  72ch
  [09] Burpee_Exercise        1.80s  72ch
  [10] Cardio_Dance           5.83s  72ch
  [11] Chair_Sit_Idle_F      11.50s  72ch  ← SIT clip (default idle)
  [12] Confused_Scratch       3.23s  72ch  ← THINKING clip
  [13] Finger_Wag_No          9.83s  72ch
  [14] Flirty_Strut           0.63s  72ch
  [15] Grab_Bar_and_Swing_Forward 8.00s 72ch
  [16] Happy_Sway_Standing    1.40s  72ch
  [17] Happy_jump_f           6.33s  72ch  ← FLOURISH clip
  [18] Running               11.33s  72ch
  [19] Walking                5.00s  72ch

babshaggoth.glb  (16,703,296 bytes) — 18 clips incl Idle_15, Running, Walking,
  Thomas_Flair_to_Jump_Up, Kung_Fu_Punch, FunnyDancing_02

lyra.glb        (18,471,380 bytes) — 19 clips incl Idle_03/04/05/08/09/12/15,
  Walking, Running, Stylish_Walk_inplace, victory, Wave_for_Help_3,
  Strangled_and_Fall_Forward
```

All three bundles have `skin=1`, real bones (72 channels per clip is consistent with
a 24-bone humanoid rig × 3 transforms). They are NOT static — they carry full
skeletal animation. The static-pose guard is the only reason they aren't moving.

## Operator spec — NEXT STAGE (2026-08-28 01:25Z):

```
3D CHARACTER RENDER = PASS
3D ANIMATION        = NOT PASS

DO NOT CHANGE THE AVATAR ASSET IDENTITY OR REPLACE IT.
DO NOT restore any 2D fallback.

Repair sequence:
1. Identify exactly which GLB is currently rendered        — DONE (purpangolin.glb)
2. Inspect skeleton / skin / animations / clip names       — DONE (above)
3. If static-only, locate matching animated GLB            — N/A, all animated
4. Load animated GLB with NO autoplay first                — needed
5. Print runtime animation catalogue                       — needed
6. Play ONE idle animation only                            — needed
7. Physical S25 test: build→install→launch→logcat→screencap
8. If stable, test clips individually: idle, walk, sit,
   talk, turn/look, dance
9. If one clip crashes, quarantine that clip and continue
10. If every skinned clip crashes, investigate
    SceneView/Filament animation compatibility while
    preserving the canonical GLB
11. PASS only when the real Gothic/Neon character visibly
    moves on the physical S25
```

## Operator spec — DEFAULT POSE + ANCHOR (2026-08-28 01:30Z):

```
DEFAULT AVATAR STATE
- pose = SIT  (clip: Chair_Sit_Idle_F, 11.5s loop)
- anchor = composer.rightEdge
- feet/hips aligned to composer top edge
- body offset slightly upward so she looks seated on the composer
- right side placement by default
- preserve full chat readability
- do not cover send/mic controls

PINCH ZOOM
- two-finger pinch → scale continuously
- clamp to sane min/max
- preserve anchor position while scaling
- persist scale across app restart
- double-tap or Reset returns to default seated size

REAL ANCHOR (no magic pixels)
  sitAnchorX = composer.right - avatarSeatInset
  sitAnchorY = composer.top
  pelvis/sitAnchor on model lands on composer top edge

INTERACTION RULES
- one-finger drag moves her anywhere
- two-finger pinch scales her
- optional two-finger horizontal rotate Y (later)
- soft-snap back into seated position when dragged close to composer
- tapping empty transparent stage passes through to chat
- only her projected body/hitbox captures gestures
- x/y/scale/rotation/pose persist
- Reset Avatar = seated on right composer edge at default scale

Once animations work, default clip = actual sit/idle, not standing.
```

This is the third contract change in the avatar lane since 2026-08-27. The render
path is solid; the animation + anchor path is now the entire remaining work.

## NEXT (in order)

1. #63 device verification → then LIVE PODCAST RECORDING (session goal): install ok →
   launch → `adb shell screenrecord` → drive podcast via chat ("convene a council that
   debates …") → pull screen recording + `podcast_episodes.json` + transcript .md.
2. #39 Step 12.14 — build+install+5-dispatch E2E reconstruction
3. #38 Step 12.15 — independent verifier (gated on 12.14)
4. #20 model selectors + auto-routing + voice reply on-device proof

## DO NOT DROP (operator-pinned)

- 3D avatar animation playback (operator spec 2026-08-28 01:25Z) — keep canonical GLB, enumerate clips, play ONE idle first, quarantine any SIGSEGV'd clip, no 2D fallback
- Sit-on-composer right-edge default (operator spec 2026-08-28 01:30Z) — anchor = composer.rightEdge minus inset, pose = Chair_Sit_Idle_F loop, pinch zoom 0.4..2.5, soft-snap back when dragged near composer
- Spec file: `app/src/main/java/com/example/ui/components/AGENT_NOTES_TASK63_ANIMATION_AND_SEAT_ANCHOR.md` (10 sections, clip-by-clip certification order, hard rules, DoD)

- mobile surface audit (#3)
- back-to-chat navigation (#12)
- iconography (#13)
- model routing + voice reply (#20)
- seven-layer memory weave (#49)
- 10-seat podcast + mid-show speaker changes (#54)
- transcript export (#55)
- shit-stir break mode (#56)
- UI unification (#58)
- podcast resume continuity (#60)
- live podcast recording (session goal — not done till file exists on disk)

---

## ACTIVE (11) — in progress, state as of last touch

| # | Task | State | Files / proof so far |
|---|------|-------|----------------------|
| 63 | 3D avatar overlay (rigged GLB, composer sit, TTS pulse) | Full-stage code + unit + APK PASS. Live small-viewport screenshots REJECTED; full-stage on-device proof pending reconnect. | `Avatar3DActor.kt`, `PurpAngolinOverlayActor.kt`, `AssistantTurnCard.kt`, `CommandScreen.kt`, `MainViewModel.kt`; full-screen SceneView stage; bounded gesture handle; skin+animation admission gate; PurpAngolin 20 clips, Babshaggoth 18, Lyra 19; `Avatar3DClipsTest` PASS |
| 3 | Audit every phone surface via screenshots | Partial (boot_check.png, av_check.png captured; more pending) | `.tmp/boot_check.png`, `.tmp/av_check.png`, `.tmp/ui.xml` |
| 12 | QoL: back-to-chat from any surface | In progress; exact contract + triple-proof gate recorded under STEP DEFINITIONS | Navigation host/surface inventory pending device audit |
| 13 | Replace emoji icons with pro iconography | In progress; primary chat/avatar/browser/attachment/podcast controls cleaned, broader Canvas/status surfaces remain | `CommandScreen.kt`, `PurpAngolinOverlayActor.kt`, `MediaAttachmentsRow.kt`, `DualViewBrowser.kt`, `PodcastStudioScreen.kt`, `MainViewModel.kt` |
| 20 | FIX model selectors + auto-routing + voice reply | In progress; on-device proof outstanding | — |
| 58 | Unify non-chat UI to Chat styling | In progress; chat body at 12sp; timestamp ordering; paired user prompt retained; queued text persisted | `AssistantTurnCard.kt`, `CommandScreen.kt`, `MainViewModel.kt` |
| 55 | Podcast: 10–20 min target + downloadable transcript + audio | In progress | `core/runtime/CouncilPodcastEngine.kt`, `ui/screens/PodcastStudioScreen.kt` |
| 56 | Podcast: shit-stir break mode | In progress | `core/runtime/CouncilPodcastEngine.kt` |
| 60 | Podcast resume continuity (seats remember mid-show) | In progress | `core/runtime/CouncilPodcastEngine.kt` |

## PENDING (15)

| # | Task | Definition |
|---|------|-----------|
| 1 | Voice overlay — darken chat to black, thicker visualizer, rotating styles | `ui/components/VoiceVisualizerOverlay.kt` |
| 5 | NIM threading + model filter — verify on phone | needs device |
| 6 | LAN home-runtime fix — verify persisted | needs device |
| 8 | NIM picker: source live catalogue incl. previews | — |
| 23 | Step 11 verifier — BLOCKED until Step 12 lands | see STEP DEFINITIONS |
| 38 | Step 12.15 — independent verifier agent (gated on 12.14) | see STEP DEFINITIONS |
| 39 | Step 12.14 — build + install + 5-dispatch E2E reconstruction | see STEP DEFINITIONS |
| 47 | Podcast guest queue — mic during podcast queues user turn | `enqueueGuest`/`dequeueGuest` wired; `GUEST_ROLE` in seat rotation |
| 50 | Step 13: BIOS/POST boot screen with PurPangolin overlay | plan steps 13.8–13.12 |
| 52 | Step 13: Agent-as-Seat — every soul can claim a podcast seat | `seatsForRoster` + `refreshSeatsFromRoster` wired |
| 53 | PurpAngolin identity from MD files (cross-surface) | — |
| 57 | Layouts + text audit on every screen | — |
| 59 | Pod rude score: bump profanity density past polite-debate baseline | CouncilPodcastEngine prompt law |
| 61 | Turn-based voice chat: only convene council on explicit intent | MainViewModel routing |
| 62 | Tribunal receipts as engine protocol — not prompt etiquette | CouncilPodcastEngine + DispatchRecorder |

## DONE (43) — with evidence

| # | Task | Evidence |
|---|------|----------|
| 2 | Bottom drawer full exit + open-to-middle | drawer code in PurpClawApp shell |
| 4 | Podcast turn/TTS fixes | on-device |
| 7 | Drawer 3-state (collapsed/middle/expanded) | — |
| 9 | NIM filter: token-aware exclusion of non-chat models | free-only catalogue law |
| 10 | NIM auto-refresh every app run + free-only law | — |
| 11 | OpenRouter auto-refresh free list every app run | — |
| 14 | AUTO FREE router law — gateway + free + configured only | ProviderRouter |
| 15 | Spend control + paid receipts | ProviderRouter cost ledger |
| 16 | Catalog provenance fields on CatalogueModel | — |
| 17 | Direct provider fetchers (MiniMax/Kimi/Qwen/DeepSeek/OpenAI/Z.ai) | — |
| 18 | AI Models UI: Free Gateways vs Direct/Pro split | `ui/screens/AiModelsSettingsScreen.kt` |
| 19 | Build + install + smoke-test on phone | gradle EXIT 0 + adb install Success |
| 21 | Step 4: ToolRuntimeEngine extension | `core/runtime/ToolRuntimeEngine.kt` |
| 22 | Step 2: CanonicalToolCall normalizer | `core/runtime/CanonicalToolCall.kt` |
| 24 | Step 5: HomeRuntimeBridge.chatWithTools | `core/network/HomeRuntimeBridge.kt` |
| 25 | Step 6: ProviderRouter wire tools array | `core/runtime/ProviderRouter.kt` |
| 26 | Step 1: CapabilitySnapshot data class | — |
| 27 | Step 3: ToolLifecycle 5-state enum | `core/runtime/ToolLifecycle.kt` |
| 28 | Step 7: MainViewModel mirror + receipt re-injection | `ui/MainViewModel.kt` |
| 29 | Step 8: CouncilPodcastEngine receipt re-injection | `core/runtime/CouncilPodcastEngine.kt` |
| 30 | Step 10: live podcast E2E — ep_1787784192537 (8 seats) | episode file on device |
| 31 | Step 9: parity test harness | unit tests |
| 32 | Step 12.1 DispatchReceipt data class | `core/runtime/DispatchRecorder.kt` |
| 33 | Step 12.6 chatWithTools emits DispatchReceipt | `core/network/HomeRuntimeBridge.kt` |
| 34 | Step 12.2 DispatchLedger Entity+DAO+DB v4 | `core/database/DispatchLedgerEntity.kt`, `DispatchLedgerDao.kt`, `PurpClawDatabase.kt` |
| 35 | Step 12.12 freshness in receipt re-injection blocks | MainViewModel + CouncilPodcastEngine |
| 36 | Step 12.11 canonical capability identity + dedupe + aliases | CapabilityTruthRegistry |
| 37 | Step 12.13 parity test extension (2 scenarios) | unit tests |
| 40 | Step 12.5 DispatchReceipt + parseToolCalls in ProviderRouter | `core/runtime/ProviderRouter.kt` |
| 41 | Step 12.7 Council askSeat executes proposed tool calls | `core/runtime/CouncilPodcastEngine.kt` |
| 42 | Step 12.3 DispatchRecorder writer | `core/runtime/DispatchRecorder.kt` |
| 43 | Step 12.9 system.router.last_decision → one structured receipt | ToolRuntimeEngine router tools |
| 44 | Step 12.10 system.router.trace → real DispatchReceipt rows | ToolRuntimeEngine |
| 45 | Step 12.8 system.router.inspect: decision surface + freshness | ToolRuntimeEngine |
| 46 | Step 12.4 RoutingDecisionContext carrier | `core/runtime/RoutingDecisionContext.kt` |
| 48 | CHAT UI: assistant reply + status/token-burn row | `ui/MainViewModel.kt` (session literal→_activeSessionId, lastLiveStatus, tokenBurnCents), `ui/screens/CommandScreen.kt` |
| 51 | Step 13: live podcast E2E with citations + memory refs | ep file + verifier PASS (2026-08-26 podcast slice) |
| 64 | FIX podcast turn-count stuck at ~50 | `CouncilPodcastEngine.kt` — `loadSavedIndex()` now rebuilds turnCount from `podcast_episodes.json` transcript array by matching episode ID; stale stored values corrected on app boot |
| 65 | Podcast: 10-seat mutable council + add/remove mid-show | `CouncilPodcastEngine.kt` — `MAX_SEATS=10`, `addSeat`/`removeSeat`/`setActiveSeats` wired; `GUEST_ROLE` for #47 queue |
| 66 | Podcast: downloadable transcript .md export | `CouncilPodcastEngine.kt` — `buildString { }` with `transcriptMd.writeText(mdBody)` per episode; `transcriptMdPath` in `SavedEpisode` |
| 67 | Podcast: shit-stir break mode | `CouncilPodcastEngine.kt` — `enterBreakMode`/`exitBreakMode`/`BreakState`; 13 tangent pool; break-mode chip in `CommandScreen.kt` |
| 68 | Podcast: resume continuity (episode checkpoint on disk) | `CouncilPodcastEngine.kt` — `EpisodeCheckpoint`/`writeCheckpoint`/`loadResumableEpisode`/`buildResumePreamble`; checkpoint after every turn |
| 69 | MEMORY: weave all 7 layers through agents | `core/runtime/MemoryGateway.kt` — `buildWeaveBlock(topic)` wires 7-layer stack to council seat prompts |

---

## STEP DEFINITIONS (no more "what is step 12" nonsense)

**Task #12 (mobile QoL; not “Step 12”)** = every primary non-chat surface exposes a
consistent one-action return to the existing COMMAND/chat surface. Returning must reuse
the same chat/session state: no new session, lost draft, reordered turns, duplicate live
turn, or remounted overlay state.
- **Three proofs required:** (1) compile + navigation/unit checks, (2) on-device traverse
  every registered `NavigationSurface` and capture the return-to-chat result, (3) state
  continuity showing the same session ID, turn count/order, composer draft, and avatar
  state before/after the round trip.
- **Three adjacent regressions required:** navigation/back-stack + Android system Back;
  chat persistence/streaming/action-sheet state; overlay/voice/browser modal touch and
  dismissal behavior.

**Step 11** = artifact verifier for the tool-call parity chain. Blocked historically
because the live artifact had empty `toolCalls[]`. Re-unblockable now Step 12 landed;
fold into #38 verifier run.

**Step 12** = DispatchReceipt truth pipeline (execution truth for every tool call):
- 12.1 data class → 12.2 Room ledger (DB v4) → 12.3 recorder → 12.4 decision carrier
- 12.5 ProviderRouter emit → 12.6 HomeRuntimeBridge emit → 12.7 council executes tools
- 12.8/12.9/12.10 router inspect/last_decision/trace tools
- 12.11 canonical capability identity → 12.12 freshness → 12.13 parity tests
- **12.14 (#39)** = build + install + drive a fresh 5-dispatch podcast; reconstruct all
  5 dispatches from the ledger alone; ledger row count == 5.
- **12.15 (#38)** = independent verifier. **Three proofs required:** (1) compile + unit
  tests green, (2) on-device 5-dispatch E2E with `dispatch_ledger` rows pulled and
  counted, (3) regression: chat reply path, podcast path, router tools all still work.

**Step 13** = agent-seats + memory weave + BIOS/POST + chat-UI truth. Full plan:
`C:\Users\Admin\.openclaude\plans\tingly-painting-dewdrop.md` (13.1–13.16).
Status: 13.x chat fix + live E2E done (#48, #51); BIOS/POST (#50) and agent-as-seat
(#52) pending.

## EVIDENCE PROTOCOL

A task moves to DONE only with: files changed + (tests OR build proof) + device
verification where UI/runtime is touched. `CLAIMED_DONE ≠ DONE`. Screenshot after every
UI change; pull receipts (`podcast_episodes.json`, `dispatch_ledger` rows,
`boot_receipts`) with `run-as com.aistudio.purpclaw.osv7`.

## DEVICE / BUILD FACTS

- Build: `C:/Users/Admin/Tools/gradle-9.3.1/bin/gradle.bat :app:assembleDebug --no-daemon --no-build-cache --no-configuration-cache -Dorg.gradle.jvmargs="-Djava.awt.headless=true"` (no wrapper; build-cache poisons dex)
- adb: `C:/Users/Admin/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- Device: RZCY9172MDP (1440x3120). App: `com.aistudio.purpclaw.osv7` / `com.example.MainActivity`
- Git Bash mangling: prefix adb shell paths with `MSYS_NO_PATHCONV=1`
- Podcast trigger: chat → intent → tool `android.podcast.convene` → `runEpisode` (MainViewModel.kt ~L1088–1138)
- Avatar mood law: FLOURISH (post-reply pulse) > SPEAKING (TTS) > THINKING (generating) > LISTENING (typing) > IDLE (CommandScreen.kt)
- Avatar package proof (2026-08-27): `app-debug.apk` 114,303,541 bytes contains
  `assets/avatars/{purpangolin,babshaggoth,lyra}.glb`; SHA-256 values are
  `67F866...9C002`, `D51143...3CC78`, `140C8B...D407` respectively.

## 2026-08-28 PHONE CHAT + BOUNDED AVATAR RECEIPTS

- Root cause of the boots-only render: `ModelNode(scaleToUnits = 1.6f)` expanded a
  Meshy rig whose reported bind height was only `0.017m`. The mobile actor now keeps
  the GLB's native metre scale and applies transforms through a stable rig root.
- Rejected implementation: a full-screen transparent native `SceneView` rendered the
  avatar correctly but intercepted the entire chat surface. The native surface is now
  bounded to the avatar stage; taps outside it reach chat, buttons, and composer.
- Physical device touch proof: composer accepted `TOUCH_OK`, Send executed, the user
  turn remained in order, and the assistant reply rendered directly on the dark canvas.
  Screenshot: `.tmp/bounded_send_test.png`.
- Conversation-history root cause: `MainViewModel` built `(userText, assistantText)`
  pairs even though `ProviderRouter` consumes `(wireRole, content)`. History is now the
  ordered last 40 real `user`/`assistant` turns.
- History device proof: prompt referenced the previous `TOUCH_OK`; the persisted phone
  reply was exactly `CHAT_HISTORY_OK`. OpenRouter live fallback selected
  `nvidia/nemotron-3-ultra-550b-a55b:free`. Screenshot:
  `.tmp/history_fix_pass.png`; Room proof in `event_spine_turns`.
- Terminal failure-state proof: exhausted/slow endpoints now stop THINKING and visibly
  render `No response received · Retry`; detailed provider failures remain in logcat.
- Remaining avatar state limitation: the certified compact GLB currently supplies the
  safe seated idle. Visible per-state animation clips still require separate fully
  skinned/animated assets; a T-pose or unskinned file is not an allowed fallback.
- Keyboard regression PASS: Samsung IME originally covered the lower native stage.
  The actor now anchors above the IME plus an 88dp control-row clearance. Device proof
  shows the full body while the native surface ends at y=1265 and composer controls
  begin at y=1400 (`.tmp/ime_avatar_controls_clear.png`). Tapping WORK at y=1490
  succeeded and changed the composer/submit UI (`.tmp/work_button_clear.png`).
- Regression reopened from operator touch test: the IME-padding implementation resized
  the native SceneView repeatedly during keyboard animation. Samsung emitted rejected
  buffers at heights 1470/1143/1140/1137 and the historical crash signature is a native
  null dereference in `libgltfio-jni.so`. Recovery layout v12 forces the avatar visible,
  keeps the SurfaceView at one invariant maximum size, translates it for IME, and makes
  pinch affect only the rig-root transform. Fresh build + physical touch proof required.
- Recovery v12 first device pass: keyboard open/close plus direct avatar horizontal swipe
  preserved PID 11217 and produced no crash-buffer entry. Reset scale was visibly too
  small because the old presentation depended on surface-size coupling; fixed-stage
  render scale now compensates 1.875x while stored user scale semantics remain 0.40.
- Recovery v12 compensated build installed on S25: boot PID 13101 survived direct avatar
  swipe, keyboard open, second touch, and keyboard close unchanged; crash buffer empty.
  A clean relaunch produced PID 13526 with avatar visible at compensated full-body size
  (`.tmp/touch_recovery_ready.png`). No rejected-buffer, SIGSEGV, or fatal-signal event
  appeared during the test. Physical two-finger pinch remains operator verification.

## 2026-08-28 CANONICAL MOBILE END-TO-END ORDER (25 GATES)

Source of truth: operator attachment `06d20de2-897c-4a6a-88ad-b612e77dea1b/pasted-text.txt`
(786 lines, read in full). These are acceptance gates, not implementation claims:

1. **Capability truth:** canonical broker states REGISTERED / CONFIGURED / COLD / WARM /
   DEGRADED / DEAD / UNAVAILABLE, with node, executor, authority, health, probe/success/
   failure times, count, latency, last receipt, and version. Registered alone is never
   dispatchable.
2. **Health-aware router:** validate installed/configured/authorised/live/eligible before
   dispatch; bounded cold probe; skip dead; trace fallback and every override's rule,
   source, reason, target, and health. Expose route-explain, capability-health, and
   capability-verify commands.
3. **One dispatcher:** desktop and mobile share parse → session → identity → authority →
   capability → execution → verification → receipt. Transport services do not dispatch.
   Prove valid, invalid, bad-node, denied, unavailable, executor-failure,
   verification-failure, and receipt-failure paths truthfully.
4. **Native Android resolution:** natural language preserves constraints: default app,
   browser-only, explicit Chrome, or another currently installed/live browser; then
   authority, native execution, foreground/result verification, receipt.
5. **Conversational execution context:** persist current phone node, foreground app,
   current surface/task, and conversation referents across follow-up turns.
6. **Authorised subsystem activation:** automatically start already-authorised adapters;
   missing Android permissions produce BLOCKED, never a fake green capability.
7. **Post-action observation:** ACTION → OBSERVE → VERIFY for launch, URL, interaction,
   and submission. `ok:true` alone is not user-visible proof.
8. **Receipts everywhere:** actor/action/time/node/authority/capability/executor/safe args,
   before+after health/result/verification/duration/trace/receipt; explicitly state what
   each receipt proves and does not prove.
9. **Podcast durability:** persist episode/round/topic/objective/positions/claims/evidence/
   decisions/open questions/receipts/cursor through Android process death and resume via
   the existing memory spine.
10. **Context budget:** measured thresholds at 60/75/85/95%; structured critical state
    survives distillation, checkpoint, compaction, and context rotation.
11. **Dead podcast seats:** QUEUED/THINKING/TOOL_CALL/RESPONDING/COMPLETE/ERROR/TIMED_OUT/
    SKIPPED; bounded retry then skip and continue.
12. **Round objectives:** topic, question, success criteria, known evidence, open claims,
    and decision needed before a round runs.
13. **Claim/reference truth:** SUPPORTED/PARTIAL/UNVERIFIED/SPECULATIVE/CONTESTED/RETRACTED
    claims and SUPPORTED/UNRESOLVED/BRANCH-ONLY/HALLUCINATED/RETRACTED references;
    high-specificity claims require evidence.
14. **Anti-fake-action:** preserve detection of promised but unexecuted work; compact UI
    says `PROPOSED, NOT EXECUTED · No authorised tool receipt`, details behind inspection.
15. **Speech sanitation:** tool/protocol/XML/JSON/provider/routing control payloads are
    parsed away before clean conversational text reaches TTS.
16. **Room-chatter rejection:** VAD + source/speaker confidence + operator-address and
    relevance classify intended operator speech, background room speech, or uncertain.
17. **Avatar truth:** IDLE/LISTENING/THINKING/SPEAKING/WORKING/SUCCESS/ERROR/AWAY are driven
    by runtime truth; failure exits THINKING. Prove drag, pinch, hip-yaw, double-tap,
    full-screen clamping, and chat passthrough/selectability without changing canonical
    default presentation.
18. **UI/settings truth:** UI mutation → backend request → authoritative ACK → returned
    state; restart reloads canonical persistence. One navigation system, usable composer
    and avatar, readable metadata, all secondary surfaces cleaned.
19. **Mobile independence truth:** show device/identity/session/node/execution context/
    capabilities/authority/services/provider/network/last verification and distinguish
    phone, desktop, and local execution.
20. **Identity/session sync:** durable SELF/USER identity, sessions, ordered chat history,
    memory lineage, receipts, and node identity survive provider and device switches.
21. **Mobile CLI parity:** same canonical command families/schema/semantics as desktop
    where applicable, with truthful platform-specific capability differences.
22. **Android lifecycle torture:** chat + podcast + browser + background task through lock,
    background, wait, resume, rotate, network loss/restore, process kill/restart, session
    and podcast restore, receipts, and continued work.
23. **Full native phone E2E:** voice opens YouTube specifically in Chrome, searches,
    verifies visible results, receipts, then follows `open second result`, `another
    installed browser`, and `tell me what you're looking at` without losing context or
    inventing observation.
24. **Failure E2E:** deliberately kill Chrome/provider/network, disable accessibility,
    invalidate capability, background service; router adapts, health changes, fallback
    traces, no fake completion, useful recovery, receipts remain.
25. **Ship receipt:** create `PURPCLAW_MOBILE_SHIP_RECEIPT.md` containing version/APK/
    device/Android/build/runtime/dispatcher/capability counts and every required device
    test, limitation, failure, deferred item, and evidence path. **NO SHIPPED CLAIM
    WITHOUT PHONE RECEIPTS.**

### Fresh avatar receipt after this order

- Layout v13 replaced the fixed ~308x392dp clamp rectangle with a scale-aware visible-body
  envelope. Physical S25 held-drag proof: persisted `(dX,dY)` changed from `(0,0)` to
  `(-855,-1402)`, PID stayed 19478, crash buffer stayed empty, and screenshot
  `.tmp/roam_v13_forced_test.png` shows the avatar moved from composer-right into the
  upper/left chat viewport. Follow-up correction aligns the renderer's visual centre with
  this gesture envelope; rebuild + device proof still required.

## 2026-08-29 CANONICAL MEDIA PIPELINE ORDER

Both supplied attachments (`a7b274b8...` and `385db371...`) were read in full: 383
lines each and byte-for-byte equivalent in requirements. This adds the following gates:

- **One `MediaArtifact` truth:** id, image/video/audio/frame/screenshot type, source,
  content URI, MIME, filename, bytes, dimensions, duration, creation time, node/chat/
  message lineage, parent artifact, SHA-256, thumbnail URI, Gallery URI, metadata, and
  receipt. Camera, upload, screenshot, generated media, and extracted frames use the
  same schema and all other stores reference the artifact ID.
- **Photo flow:** voice/chat → native camera → real file → existence + decode verification
  → artifact → PurpClaw Gallery → Android MediaStore where appropriate → visible inline
  chat card → vision only when actually invoked → receipt + durable memory reference.
  The card shows the pixels, dimensions/type/size/location, and View/Analyse/Share actions;
  a pathname or `[tool result: success]` is not acceptance.
- **Video flow:** ingest/record → artifact → ffprobe metadata (duration, resolution, FPS,
  codec, audio streams, bitrate, rotation, creation metadata) → coarse interval + scene
  sampling → perceptual dedupe → contact-sheet artifact → audio extraction + timestamped
  STT → visual/speech timelines → targeted dense original-resolution pass → multimodal
  temporal answer with tappable timestamped evidence frames.
- **PurpClaw Gallery:** ALL/PHOTOS/VIDEOS/SCREENSHOTS/GENERATED/VIDEO FRAMES/CONTACT
  SHEETS/AUDIO backed by MediaArtifact lineage, analysis, task/chat/device/time and
  receipts. Captured photos normally publish to both internal truth and MediaStore;
  extracted frames remain internal unless explicitly saved.
- **Conversational artifact references:** `that`, `this one`, `crop that`, `look closer`,
  `send to council`, and time-relative video questions resolve to the current artifact
  and its persisted child timeline without reprocessing the entire source.
- **Camera receipt:** action/execution/result/artifact/file/decode verification/gallery/
  chat attachment. **Ship test:** voice `Take a photo and tell me what you see` must
  capture, visibly attach, save, analyse the real pixels, answer, and receipt.
- **Video ship test:** record ten seconds, render a playable artifact, save, probe/process,
  extract evidence frames, transcribe any speech, answer temporally with visible frames,
  and persist the complete lineage. Never describe pixels that were not inspected.
WORK_SESSION_SPEC: voice->WorkSession->plan+acceptance->execution(foreground/WorkManager)->save artifacts->verify->receipt->notification(OPEN CODE/RESULT/RECEIPT/RESUME/CANCEL). Build verified. Waiting operator scope confirm.

## 2026-08-31 TABLET RECOVERY + VOICE / INPUT REOPENED GATES

Physical target: Samsung `SM_X400`, ADB serial `R52YA0ECHSH`.

- **Storage recovery PASS:** operator-authorised removal of clearly identified games
  increased `/data` free space from ~803 MB to ~31 GB. Finance, banking, messaging,
  documents, photos, media services and Samsung/Android system packages were preserved.
- **State preservation PASS:** pre-uninstall app data was archived to
  `C:/Temp/purpclaw-tablet-appdata-20260831.tar`; the sovereign chat database and shared
  preferences were restored after reinstall. Encrypted-vault entries remain subject to
  Android Keystore validity after uninstall and must be re-authorised if the OS removed
  their key.
- **APK build/install PASS:** `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, and
  `:app:assembleDebug` passed; current arm64 APK installed successfully on the tablet.
- **Composer/IME PASS (current build):** physical Compose editor gained focus and Samsung
  keyboard rendered on glass with a valid input connection. This does not close the
  Android touch-delay warning: the required no-avatar-host A/B test remains OPEN.
- **Microphone capture PASS:** real recognizer session opens and system audio capture runs;
  existing visualizer is driven by actual mic RMS.
- **STT lifecycle correction PASS (silence case):** Android `NO_SPEECH_DETECTED` is now a
  recoverable no-input event, not sticky `VoiceMode.ERROR`. Real partial hypotheses are
  promoted when the vendor recognizer returns an empty final result; `EXTRA_LANGUAGE` now
  receives the required IETF language tag string. Compile, unit and silent physical run
  passed without crash.
- **STT spoken transcription PENDING PHYSICAL:** operator must speak a known phrase into
  this exact APK; PASS requires visible final text and continuation into the same turn.
- **Own TTS / stable Soul voices OPEN:** Android currently maps canonical Kokoro IDs onto
  installed platform voices. That is a deterministic fallback, not authoritative Kokoro
  synthesis. Extend the existing `TextToSpeechEngine` through the shared voice provider
  boundary: Soul-owned profile -> healthy Kokoro/local provider -> typed deterministic
  fallback. Preserve one speech queue, `tts.finished` turn handoff, barge-in and no voice
  roulette. Do not create a second podcast/mobile voice runtime.
- **SurfaceView touch interference OPEN P0:** perform controlled current-vs-no-avatar-host
  physical A/B. If omitting construction of the native avatar host removes Android's
  touch-delay warning, keep rendering non-interactive and put gestures in a bounded
  Compose-owned avatar hit target. Do not add more composer focus hacks.
- **Camera ownership PASS-looking / regression required:** fresh chat launch produced no
  `Camera 0 ACTIVE` evidence; camera must remain lazy and release after real capture.
- **Router / AUTO failover OPEN:** repeat HOME-OFFLINE WORK creation task and forbid
  `AUTO -> none` while any eligible phone route remains.
- **Avatar semantic-state proof OPEN:** packaged GLBs are skin+animation admitted and
  T-pose is suppressed in code. Physical proof still required for idle, listening,
  thinking, tool/working, speaking, success, error and greeting, plus full-screen bounded
  drag/spin/pinch without blocking chat controls.

## 2026-09-01 TABLET COMPOSER / 3D IME / FREE ROUTER RECEIPT

Physical target: Samsung `SM_X400`, ADB serial `R52YA0ECHSH`, Android 16.

- **Composer controls RESTORED / PHYSICAL PASS:** the canonical toolbar again exposes
  `CHAT`, `WORK`, live `AUTO` model selector, `+` action sheet, microphone, PurpClaw
  navigation and Send/Cancel. UIAutomator observed every control in the installed APK;
  screenshot: `C:/Temp/purpclaw-controls-proof.png`.
- **Keyboard + real 3D coexistence PHYSICAL PASS:** PurpAngolin remains the real fully
  rigged/animated Filament GLB while Samsung Honeyboard is open. No flat fallback image
  is constructed. The renderer SurfaceView is physically shortened to the IME top
  (`1320x1329`, not the prior full-screen `1320x2112` surface). Screenshot:
  `C:/Temp/final-real-3d-ime-v2.png`.
- **Touch acceptance PHYSICAL PASS:** three direct screen taps on Honeyboard entered
  `qwe` into the Compose editor (final editor value `123qwe`). Samsung logcat contained
  zero `Untrusted touch due to occlusion`, `BLOCK_UNTRUSTED`, or dropped-untrusted-touch
  events. Screenshot: `C:/Temp/final-typed-3d.png`.
- **Plus action entry point PHYSICAL PASS:** installed UI exposes real File, Folder,
  Camera, Gallery/Photo, URL and Clipboard actions with unavailable actions truthfully
  disabled/reasoned. Screenshot: `C:/Temp/final-plus-menu.png`.
- **OpenRouter cost incident MITIGATED / ACCOUNT RECONCILIATION OPEN:** AUTO currently
  fails closed away from OpenRouter. Manual OpenRouter invocation is rejected before HTTP
  unless the exact live id is `openrouter/free` or ends in `:free`; gateway type alone no
  longer bypasses the spend gate. This prevents further silent paid AUTO traffic but does
  not by itself prove which historical request created the reported account charge.
- **Daily live catalogues PHYSICAL PASS:** boot logs prove NIM `/v1/models` and OpenRouter
  `/api/v1/models` refresh on the tablet. OpenRouter admitted only live zero/zero-priced
  records. NIM AUTO now rejects live code-completion, vision, reward, parser, embedder,
  reranker, safety and other non-chat families; focused classifier unit tests pass.
- **Installed acceptance APK:** SHA-256
  `0E1D035F5D4B2A4CA24DB4E610371F9DB8A7F7D226C82352953FD77311BC570D`.
- **Still OPEN:** physical pinch acceptance; spoken STT continuation; full AUTO WORK
  build benchmark; provider account-side charge reconciliation; full PM2 role extraction
  integration; complete podcast failure/recovery and lifecycle torture matrix.

## 2026-09-01 SESSION — ROOT CAUSE AUDIT + NO-SKIM LEDGER INTEGRATION

**P0-A Freeze Evidence:** DONE. `var/probes/purp_proof1.png` (709,943 bytes) and `purp_ui.xml`
  (32,751 bytes) from tablet `R52YA0ECHSH`. UIAutomator confirmed 62 labeled nodes.
  Council 6-seat failure captured in XML with exact error: all seats returning
  `PROVIDER_EXCEPTION` at 15–21ms — consistent with immediate local rejection.

**P0-C Routing (FREE_MODELS):** ALREADY DONE. `CommandPanel.tsx` lines 1065–1080 wire
  `/api/free-models` on mount. Route exists at `apps/web/app/api/free-models/route.ts`
  and performs live discovery via `openrouter-catalog.js` + `nim-catalog.js`. No
  hardcoded FREE_MODELS constant. Source claim of failed edit was self-corrected.

**P0-D Council Root Cause:** CONFIRMED. `PROVIDER_EXCEPTION` at 15–21ms = immediate local
  401 from Android KeystoreVault. `ProviderRouter.kt` line 1559 fires:
  `"401 Unauthorized: OPENROUTER_API_KEY missing in KeystoreVault"`. The key is not
  seeded into Android Keystore. PM2 confirms Home API port 7780 is offline — even if
  it were running, Council on Android would still 401 without the key.
  **Fix required:** seed `OPENROUTER_API_KEY` into Android KeystoreVault.
  **Secondary:** bring up Home API at port 7780 (currently zero services in PM2).

**SEC-001 NVIDIA key:** FIXED. `credentials.json` nvidia apiKey replaced with
  `«redacted»`.

**RUN-001 (Xiaozhi port 7788 ECONNREFUSED):** `companion/xiaozhi_bridge.js` expects
  Samantha voice service on `SAMANTHA_PORT=7788` — not running. Not critical path
  for current stack.

**RUN-002 (companion_swarm.js tower personality layer missing):** `companion_swarm.js`
  is a pure file loader reading `skills/{agent}/AGENT.md|SKILL.md|GOALS.md|PROTOCOLS.md`.
  The "missing layer" error means consuming code expects a different interface from the
  loader — requires code trace to find exact call site.

**No bulk PM2 restart performed.** 27-service ecosystem confirmed absent. Lifecycle
  classification required before any restart.

**NO-SKIM LEDGER integrated.** 126-item checklist confirmed. Key laws recorded:
  - Unknown != zero
  - Logged != verified
  - Tool success != task success
  - PurpClaw does not mark its own homework
  - Fresh probe beats stale memory; preserve discrepancy
  - DONE != COMPLETED
