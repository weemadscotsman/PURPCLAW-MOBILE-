# AGENT_NOTES — Lane #102 — PurpClaw Mini Mode (Android Picture-in-Picture)

**Goal:** When the operator leaves PurpClaw during an active Chat, Work or Live Drive session, the activity collapses into a small, draggable, system-level Picture-in-Picture window rather than disappearing. Live Drive / agent work keeps going under a foreground service. Tap-to-expand restores the full app. Consequential actions promote back to the full UI before execution.

**Operator framing (2026-08-28 ~09:38):**
> "purpclaw should minimise to a mini screen like scrree n in screen thing like youtube does for its palyer stays open eeven wen clsoed"

Mini Mode is the answer: a PurpAngolin-fronted PiP surface that keeps the operator's running work in view while they use other apps.

---

## Truth baseline (already in source)

`AndroidManifest.xml` already declares `android:supportsPictureInPicture="true"` on `MainActivity` (line 44) — PiP is opted-in at the manifest level. `MainActivity` itself is a plain `ComponentActivity` with no PiP callbacks wired (lines 18-55). The work below is the missing wiring + the mini composable, not a manifest change.

---

## Architecture (YouTube-PiP-shaped)

```
PurpClawActivity (MainActivity)
   │
   ├─ Full UI                  (Compose, NavigationSurface)
   │
   └─ PiP / Mini Mode          (Android system PiP)
          │
          └─ LiveSession       (foreground service, survives PiP/activity death)
                 │
                 ├─ ChatSession        (Chat)
                 ├─ WorkSession        (WORK mode, lease-bearing)
                 ├─ LiveDriveSession   (mobile agent worker — Lane #92)
                 ├─ AvatarStateStream  (IDLE/THINKING/SPEAKING/WORKING)
                 └─ ConfirmationQueue  (consequential ops wait for expand)
```

**The mini window is the view, not the keeper.** Killing the PiP activity does not stop Live Drive — only the foreground service does. This is the same pattern YouTube uses (`MediaSession` survives PiP teardown).

---

## Mini surface contract

### Three presentation states (matches existing `PurpRuntimeState` enums)

| State | UI | Behaviour |
|---|---|---|
| **IDLE** | `🐾 PurpClaw` · "Ready" | No animation. Stays mounted. Tap → expand. |
| **THINKING / WORKING** | `🐾 Working…` · task title (e.g. "Opening Settings") | Animated cyan activity ring (reuse `AssistantTurnCard.kt` thinking pill). Auto-reveals brief status every 2s. |
| **SPEAKING** | `🐾 PurpClaw` · short reply snippet (≤64 chars) | Mouth/viseme amplitude drives avatar lip flap if 3D actor is mounted in the mini surface. Otherwise a thin green waveform bar. |
| **CONFIRMATION_NEEDED** | `🐾 Confirmation needed` · "Send this message?" + [Review] button | Forces expand on tap — see §Consequential actions. |

### Aspect ratio

Android PiP requires the activity to advertise an aspect ratio via `enterPictureInPictureMode(params)`. PurpClaw's mini is square-ish, slightly wider than tall: **9:10** (width:height). Fits Galaxy S25 in both portrait and landscape corners.

### Controls (when controls layer revealed)

- **Tap body** → toggle controls layer (auto-hide after 3s)
- **Tap expand arrow** → full app
- **Tap ⏸ Pause** → suspends Live Drive foreground service; avatar returns to IDLE
- **Tap ⏹ Stop** → ends Live Drive session, removes notification, foreground service stops
- **Drag body** → system handles repositioning (no custom logic needed)

---

## Lifecycle wiring (single Activity)

`MainActivity` already exists. The PiP contract below extends it without restructuring the existing Compose tree.

### On-user-background

```
onUserLeaveHint()
   └─ if (LiveSession.isActive && !isInPictureInPictureMode)
         └─ enterPictureInPictureMode(
              PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 10))
                .build())
```

`onUserLeaveHint()` fires on home press, recents, app switcher — exactly the YouTube behaviour. Don't fire on in-app navigation (`Surface` swaps within the same activity don't trigger it).

### On PiP entry

```
onPictureInPictureModeChanged(inPiP, config)
   └─ viewModel.onPipChanged(inPiP)
        ├─ if (inPiP) PurpClawApp renders MiniSurface composable
        └─ else       PurpClawApp renders full NavigationSurface tree
```

Both branches live inside `PurpClawApp` so ViewModel state is the single source of truth. The mini surface reads the same `PurpRuntimeState`, `liveTurn`, `activeLease`, `tokenBurnCents`, `selectedCompanion` flows as the full app — **no duplicated state**.

### On resize (PiP is resizable on Android 12+)

```
onConfigurationChanged(newConfig)
   └─ viewModel.onPipConfigChanged(newConfig)
```

---

## LiveSession foreground service (the keeper)

New class: `app/src/main/java/com/example/runtime/LiveSessionService.kt`

Responsibilities:
1. Start as `startForeground(id, notification)` when `LiveSession.isActive`.
2. Notification channel `purpclaw_live_session` — required on API 26+.
3. Foreground service type: `foregroundServiceType="specialUse"` (Live Drive doesn't fit mic/camera/location/dataSync cleanly — specialUse + description in manifest).
4. Owns the `LiveDriveSession`, `WorkSession`, `ChatSession` continuations.
5. Survives activity death — PiP surface reads from service-bound `LiveStateBus` (a `StateFlow` exposed via local binder for the in-process case, via `Messenger` if cross-process).
6. Stop semantics:
   - `STOP_SELF` after Live Drive completes
   - `STOP_FOREGROUND_DETACH` if user just minimises the UI but work continues
   - `STOP_FOREGROUND_REMOVE` only when user explicitly taps ⏹

**Manifest additions (new, not edits to existing source contract):**

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".runtime.LiveSessionService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="agent_runtime_session" />
</service>
```

---

## Mini surface composable (new file, single responsibility)

New file: `app/src/main/java/com/example/ui/mini/MiniSurface.kt`

```kotlin
@Composable
fun MiniSurface(
  state: PurpRuntimeState,            // IDLE/THINKING/SPEAKING/CONFIRMATION_NEEDED
  taskTitle: String?,                  // e.g. "Opening Settings"
  replySnippet: String?,               // ≤64 chars during SPEAKING
  selectedCompanion: String,           // avatar identity
  onExpand: () -> Unit,
  onPause: () -> Unit,
  onStop: () -> Unit,
  onReviewConfirmation: () -> Unit,    // CONFIRMATION_NEEDED only
  modifier: Modifier = Modifier
) {
  // 9:10 layout. PurpVoid background, 1dp PurpBorder, 14dp rounded corners.
  // Avatar head + state icon + title row + status row + control row (auto-hide 3s).
  // No scrollable content — single screen snapshot.
}
```

**Aspect** is enforced by `PictureInPictureParams.setAspectRatio(Rational(9, 10))` — Compose fills whatever the system gives.

---

## Consequential actions (the "must expand" path)

Any operation tagged **CONSEQUENTIAL** in the existing capability registry (`CapabilityRegistry.kt`) MUST promote to full UI before execution when in PiP. The expansion is mechanical:

```
ConfirmationQueue.requestReview(reason)
   └─ viewModel.requestFullUiFromPip()
        └─ MainActivity.moveTaskToFront + onResume → triggers auto-expand
```

CONSEQUENTIAL operations currently:
- Send email / message on user's behalf
- File delete / overwrite
- Payment / purchase
- Camera activation beyond viewfinder
- Accessibility-driven UI actions (Lane #92 Live Drive)

Non-consequential (stay-in-PiP-safe):
- Chat reply composition
- Memory recall
- Calendar read
- Search

The registry is the source of truth; no per-action hardcoded list in the mini surface.

---

## Confirmation-need detection (driver of CONFIRMATION_NEEDED state)

The mini surface shows the CONFIRMATION_NEEDED chip whenever `viewModel.pendingConfirmation: StateFlow<ConfirmationRequest?>` is non-null. The flow is fed by:

1. `LiveDriveSession.enqueueAction()` — every consequential action queues a confirmation
2. `WorkSession.commit()` — lease-bearing work needs approval
3. `ChatSession.sendMessage()` — only when user has not pre-approved

The confirmation card shows the reason and a Review button. Tap → expand → full Compose tree renders → user reviews → approve/deny → back to PiP if they background again.

---

## Drag / snap / close (system responsibilities)

Android's PiP framework already provides:
- **Drag** — system handles window repositioning via long-press drag
- **Snap-to-edge** — system snaps when near edge
- **Close** — X button removes the PiP window (the activity is paused, not destroyed; the foreground service continues)
- **Tap-to-expand** — handled by us, calls `moveTaskToFront` + flag to render full UI

We do **not** implement custom touch handlers for these. Anything else is fighting the framework.

---

## Notification + tap behaviour

When Live Drive runs:
1. Persistent notification: title "PurpClaw Live Drive", text shows current task (e.g. "Opening Gmail — Drafting reply")
2. Tap notification → full app (not mini)
3. Tap mini surface → toggle controls / expand
5. Swipe notification away → foreground service continues (PiP still visible)

---

## Files to touch (operator handoff)

| # | File | Action |
|---|---|---|
| 1 | `AndroidManifest.xml` | ADD `<uses-permission>` + `<service>` block. Do NOT modify existing `MainActivity` declaration. |
| 2 | `MainActivity.kt` | ADD `onUserLeaveHint`, `onPictureInPictureModeChanged`, `onConfigurationChanged` overrides + PiP params builder. Do NOT modify existing onCreate. |
| 3 | NEW `runtime/LiveSessionService.kt` | Foreground service, owns sessions, exposes `LiveStateBus`. |
| 4 | NEW `ui/mini/MiniSurface.kt` | Compose composable. Single screen, no scroll. |
| 5 | `ui/PurpClawApp.kt` | ADD branch: `if (isInPip) MiniSurface(...) else when(activeSurface) { ... }`. Existing when() tree untouched. |
| 6 | `ui/MainViewModel.kt` | ADD `isInPip`, `pendingConfirmation`, `onPipChanged`, `requestFullUiFromPip` state. Existing fields untouched. |
| 7 | `core/runtime/PurpRuntimeState.kt` | CONFIRM enum covers all states; ADD `CONFIRMATION_NEEDED` if missing. |
| 8 | `core/capability/CapabilityRegistry.kt` | ADD `requiresFullUiReview: boolean` flag to capability tags. |

8 files, 6 edits, 2 new files. No new dangerous permissions (`FOREGROUND_SERVICE_SPECIAL_USE` is normal-protection).

---

## On-glass verification contract (5 probes, S25)

1. **Cold start → background → PiP** — launch app, send a chat message that takes >5s to reply, press Home → PiP appears in screen corner with 🐾 + "Working…" + reply snippet.
2. **Drag PiP** — drag to opposite corner; system snaps. Verify no crash, no `libfilament` logcat warnings.
3. **Tap to expand** — single tap → mini surface reveals controls layer → tap expand arrow → full app returns with same chat state preserved.
4. **Live Drive PiP survival** — start a Live Drive task (Lane #92), press Home, verify PiP shows live progress, kill activity via `adb shell am kill com.example` → foreground service continues → reopen app → state restored.
5. **Consequential confirmation** — trigger a WORK-mode consequential action (e.g. "send this email"), verify mini surface shows CONFIRMATION_NEEDED, tap Review → expand → approve → back to PiP.

Each probe produces a screencap + logcat slice. **Mark Lane #102 DONE only after all 5 probes PASS** with artefacts in `C:\Users\Admin\Desktop\.tmp\mini_mode_*.png`.

---

## Triple-verify (after implementation)

1. `gradle :app:assembleDebug --no-daemon --no-build-cache` → `BUILD SUCCESSFUL`.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk` → `Success`.
3. `adb shell am start ...` + the 5 probes above → all PASS with captured artefacts.
4. Spawn `verification` subagent with this spec + 5 screencaps + changed files. Verdict PASS/FAIL/PARTIAL.
5. Spot-check 2-3 of the 5 probes by re-running the adb commands yourself.

---

## Operator-law echoes

- **"PiP is the view, not the keeper"** — service owns the session. Killing the activity does not stop work.
- **"Consequential actions must expand before execution"** — registry-driven, not per-action hardcoded.
- **"No duplicated state"** — `PurpRuntimeState` and capability flags are the single source; mini reads them via the same flows as full app.
- **"YouTube-shaped"** — drag, snap, controls-layer, tap-to-expand are system responsibilities; we do not fight them.
- **"Edge-to-edge PurpClaw identity"** — mini uses PurpVoid bg, PurpBorder, PurpNeon — same theme tokens as full app.

---

## Open debts

- `LiveStateBus` cross-process boundary (binder vs Messenger) — start with in-process binder, migrate if cross-process needed.
- 3D avatar in mini surface (head + viseme only) — defer until TTS viseme lane ships; mini surface renders 2D avatar fallback until then.
- Live Drive state persistence across process death — Lane #92 territory.
- Confirmation-need visual review — separate chip vs full expansion semantics needs `Verification` sign-off.

Lane #102 is **OPEN**. Implementable immediately on top of Lane #100's single-nav tree.