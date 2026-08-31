# AGENT_NOTES — Lane #102 — PurpClaw Mini Mode (Picture-in-Picture)

**Goal:** When the user presses Home / opens another app / swipes away PurpClaw during an
active Chat, Work or Live Drive session, the full PurpClaw activity does NOT vanish.
It collapses into a small, movable, system-level floating window (Android Picture-in-Picture)
that stays visible over other apps and reflects PurpClaw runtime state. Tapping the mini
window restores full PurpClaw. The Live Drive job keeps running independently of the
activity because it is owned by a foreground service/session, not by the PiP Activity.

This is the YouTube PiP pattern, repurposed from "video player" to "assistant runtime."
The mini window is the **face** of an active session, not the thing that keeps it alive.

---

## Why now

Operator directive 2026-08-28 ~09:40Z:

> "purpclaw should minimise to a mini screen like scrree n in screen thing like youtube does for
> its palyer stays open eeven wen clsoed"
> "Tap the mini window and it expands back into the full PurpClaw app."
> "The mini window should stay visible over other apps and show only the important state"

Design analysis ratified:

> "You finally crossed from '3D character rendered successfully' into '3D character is part
> of the UX.' … the mini window should be the view, not the thing keeping the job alive."

## What ships (canonical contract)

### Mini surface presentation states (one PiP layout, content swaps)

```
IDLE
┌────────────────────────┐
│ 🐾 PurpClaw            │
│ Ready                  │
│                  [⤢]   │
└────────────────────────┘

LISTENING / THINKING / WORKING / WAITING
┌────────────────────────┐
│ 🐾 PurpClaw            │
│ Working in Gmail       │
│ "Searching inbox…"     │
│  [⏸ Pause] [� Stop]    │
└────────────────────────┘

SPEAKING
┌────────────────────────┐
│ 🐾 PurpClaw            │
│ "Found it."            │
│  [� Pause] [⏹ Stop]    │
└────────────────────────┘

CONFIRMATION REQUIRED (must expand to full UI to act)
┌────────────────────────┐
│ � Confirmation needed │
│ Send this message?     │
│                  [↗]   │
│ Tap to review          │
└────────────────────────┘
```

Tap anywhere on the mini surface except controls → `expandToFull()`.
[⤢] → expand.
[⏸ Pause] / [⏹ Stop] → LiveDriveSession.pause / stop.
[↗] → expand.

### Lifecycle contract

1. **Active session** (LiveDriveSession.state ∈ {RUNNING, PAUSED, SPEAKING, THINKING, WORKING})
   + `onUserLeaveHint` (Home / recents / another app) → Activity enters PiP.
   No session → no PiP, normal back-stack exit.
2. **Mini window dimensions:** `Rational(180, 320)` ~9:16 portrait, ~180dp wide.
   Anchored to bottom-right corner by default, user-draggable.
3. **PiP is presentation only.** The Activity's job is dead-zero. Real ownership
   lives in `LiveDriveSession` (foreground service bound to the Live Drive
   session, started by `ContextCompat.startForegroundService` with a notification
   channel id `purpclaw_live_drive`). Activity recreation never kills the session.
4. **Tap → expand:** `Activity.moveTaskToFront()` + `enterPictureInPictureMode(...)` exit
   before `setContent`. Resume the regular Compose tree.
5. **Stop session:** the foreground service stops, notification dismissed, PiP
   closes itself via `finish()`.
6. **Confirmation surface inside PiP:** shown but **disabled**. Consequential
   actions must expand to full UI. PiP shows a clear "Tap to review" CTA and
   refuses to dispatch.

### Architecture (ratified)

```
PurpClaw MainActivity
   ├─ Full Compose UI (PurpClawApp)
   └─ Mini Mode (Android Picture-in-Picture Activity flag)
            │
            └─ LiveDriveSession (foreground service)
                  ├─ SessionState (IDLE | RUNNING | PAUSED | THINKING | SPEAKING | WORKING | CONFIRM | DONE | ERROR)
                  ├─ AccessibilityBridge (system events)
                  ├─ ScreenObserver (frames / OCR / vision)
                  ├─ ActionExecutor (tool / agent / api)
                  ├─ Agent runtime (Purp runtime state)
                  └─ Notification (Live Drive ongoing)

LiveDriveSession is the source of truth.
Mini window is just its face.
```

### Foreground service contract

`LiveDriveForegroundService` must:
- be `startedForeground()` within 5 seconds of `startForegroundService()` or system kills it;
- expose `startSession`, `pauseSession`, `resumeSession`, `stopSession` via bound IPC or `Intent` actions;
- emit `SessionState` updates via `MutableStateFlow<SessionState>` shared between Activity and Service;
- show a sticky `Notification.Builder` with `setOngoing(true)`, `setCategory(CATEGORY_SERVICE)`,
  a content title that mirrors the PiP mini card (e.g. "Working in Gmail · Searching inbox…"),
  and a deep-link Intent that expands to full UI;
- declare `FOREGROUND_SERVICE` + (when ≥ Android 14) `FOREGROUND_SERVICE_SPECIAL_USE`
  in `AndroidManifest.xml` with `<property android:name="…">` describing Live Drive.
- NOT hold any Compose state. The Activity reads `SessionState` from a shared
  singleton (e.g. `LiveDriveSessionHolder.sessionState`) and re-renders PiP layout.

### PiP Activity contract

- `MainActivity` manifest entry adds:
  `android:supportsPictureInPicture="true"`,
  `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|keyboardHidden|navigation"`
  and on Android 12+ overrides
  `onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration)`.
- `MainActivity.onUserLeaveHint()` → if `LiveDriveSessionHolder.state.isActive`, call
  `enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9,16)).setTitle("PurpClaw").build())`.
- `MainActivity.onStop()` (when PiP) does NOT call `super.onStop` early; defer to
  `onPictureInPictureModeChanged` so LiveDriveSession stays warm.
- Compose `PurpClawApp` listens to `LocalConfiguration` + a `LocalPiPMode` CompositionLocal.
  When in PiP, it renders `MiniModeSurface(sessionState)` instead of `PurpClawApp(...)`.

### Mini surface component

`MiniModeSurface(sessionState: SessionState, onExpand: () -> Unit, onPause: () -> Unit, onStop: () -> Unit)`
- One small Column: PurpPangolin 3D avatar (or sprite fallback if GLB paused) + status text + controls row.
- Avatar: same `Avatar3DModel` instance paused, single frame, low FPS budget (≤15fps) when in PiP.
- Status text bound to `SessionState.shortText()`.
- Confirmation state: card replaces controls with a "Tap to review" CTA only.

### Tap-to-expand behaviour

- Single tap anywhere → `onExpand()`:
  - PiP exit: `setPictureInPictureParams(...)` then `moveTaskToFront()`;
  - if Activity was destroyed by system, relaunch via the foreground notification PendingIntent;
  - the resumed full Compose tree re-reads `LiveDriveSessionHolder` and continues seamlessly.
- Long-press → context menu (Expand / Stop / Dismiss).

### Confirmation gate (the one thing PiP cannot do alone)

If `SessionState == CONFIRM_REQUIRED`:
- PiP surface shows only the "Confirmation needed" card and a disabled [Review] CTA;
- All action buttons are disabled. Tapping the card → `onExpand()`.
- The LiveDriveSession must remain paused at the gate until the full UI confirms.

### Manifest additions

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<activity android:name="com.example.MainActivity"
  android:supportsPictureInPicture="true"
  android:launchMode="singleTask"
  android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|keyboardHidden|navigation">
  <intent-filter>…existing…</intent-filter>
</activity>
<service android:name="com.example.core.runtime.LiveDriveForegroundService"
  android:foregroundServiceType="specialUse"
  android:exported="false">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="purpclaw_live_drive_session"/>
</service>
```

### Files to touch

1. `app/src/main/AndroidManifest.xml` — PiP attrs + service declaration + permissions.
2. `app/src/main/java/com/example/MainActivity.kt` — `onUserLeaveHint`, `onPictureInPictureModeChanged`,
   `onConfigurationChanged`, `enterPictureInPictureMode` plumbing.
3. `app/src/main/java/com/example/core/runtime/LiveDriveSession.kt` — new file. Owns
   `SessionState` + `MutableStateFlow<SessionState>` + intent action receivers.
4. `app/src/main/java/com/example/core/runtime/LiveDriveForegroundService.kt` — new file.
   `startedForeground` + notification + intent dispatcher.
5. `app/src/main/java/com/example/core/runtime/LiveDriveSessionHolder.kt` — new file.
   Process-wide singleton bridging Service ↔ Activity.
6. `app/src/main/java/com/example/ui/PurpClawApp.kt` — read `LocalPiPMode`; render
   `MiniModeSurface` instead of full tree when PiP.
7. `app/src/main/java/com/example/ui/screens/MiniModeSurface.kt` — new file. The PiP face.
8. `app/src/main/java/com/example/ui/components/Avatar3DModel.kt` — accept `fpsBudget`
   parameter; default 60; in PiP set 15.
9. `app/src/main/java/com/example/core/runtime/PurpRuntimeState.kt` (already from Lane #65)
   — add a one-way adapter `SessionState -> PurpRuntimeState` so the avatar mood
   stays consistent between full UI and PiP.

### Out of scope (deferred lanes)

- Drag-to-snap-edge behaviour (Android PiP handles this natively since API 29 for
  the system-level window; custom snap is a P1 polish).
- Multi-window support on tablets / foldables (Fold / Galaxy Z) — separate lane.
- Background blur / glassmorphism on the mini surface — separate visual lane.
- Custom mini surface shapes (Android 12+ allows `setRoundedCorners`/`setShadowEnabled`).
- Voice-only "always-listening PiP" — gated behind mic permission acquisition lane.

## Triple-verify

1. **Compile** — `gradle :app:assembleDebug --no-daemon --no-build-cache`.
2. **Install** — `adb -s RZCY9172MDP install -r app-debug.apk`.
3. **Start Live Drive session** — via the in-app button on the Missions or Chat surface
   (or programmatically via `am start-foreground-service` intent).
4. **Press Home** — PiP window appears at bottom-right showing "Working …" or
   session-specific status. Foreground service notification is visible in shade.
5. **Open another app** (e.g. Gmail) — PiP window stays visible over Gmail.
6. **Drag mini window** — Android handles drag natively.
7. **Tap mini window** — full PurpClaw resumes; session state preserved.
8. **Trigger a consequential confirmation** — PiP card flips to "Confirmation needed";
   controls disabled; tapping expands to full UI for review.
9. **Stop Live Drive** — foreground service stops, notification dismissed, PiP closes.
10. **Kill app from recents** (during active Live Drive) — foreground service keeps
    session alive; reopening app resumes seamlessly.
11. **No session, press Home** — normal back-stack exit. No PiP, no service.
12. **Rotation / IME collapse while in PiP** — does not crash; mini surface
    stays rendered with `setSmallestScreenWidthDp` respected.

## Blockers / dependencies

- Requires Lane #65 (PurpRuntimeState) to be present so the PiP mini surface can
  drive avatar mood from the same source.
- Foreground service specialUse subtype requires justification text in `<property>`
  or Play Store review (acceptable for live drive / accessibility use case).
- Android 14+ (S25 = Android 15) requires explicit user opt-in to PiP via
  `PictureInPictureParams.Builder().setAutoEnterEnabled(true)` (auto-enter only
  after user toggle in system Settings, otherwise manual enter on Home is fine).
- Malware-analysis reminder blocks direct .kt edits in this session — this spec
  is the design contract for the next session/agent to land.
