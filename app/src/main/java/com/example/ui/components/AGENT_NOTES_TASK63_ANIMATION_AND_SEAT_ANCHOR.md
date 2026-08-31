# TASK #63 — Animation Playback + Sit-on-Composer Right-Edge Anchor

Status: BUILD CONTRACT (operator-ratified 2026-08-28 01:25–01:30Z)
Lane: builder
Predecessor: `AGENT_NOTES_TASK63_AVATAR_REPAIR.md` (3D render PASS; static-pose guard on)

This file is the canonical handoff for the next animation + anchor pass.
Read it cover-to-cover before editing `Avatar3DActor.kt`,
`PurpAngolinOverlayActor.kt`, or `CommandScreen.kt`. Do **not** redesign the
avatar. The 3D character render is PASS. The work now is **make her move and
park her on the composer**.

---

## 1. What is right on device (preserve it)

- Canonical Gothic/Neon Meshy character renders successfully at full body
  visible, centred, head-to-boots — captured in `.tmp/purp_avatar_fullbody.png`
  2026-08-27 13:57Z (accidental capture while ChatGPT was focused).
- `RIG_HALF_HEIGHT = 0.825` (purpangolin) framing math PASS.
- Camera at `(0, 0.9, 3.4)` looking at `(0, 0.9, 0)` PASS.
- FOV axis `aspect > 1f ? HORIZONTAL : VERTICAL` PASS.
- No libgltfio-jni SIGSEGV with the static-pose guard ON.
- 3 GLBs verified in APK: `purpangolin.glb` 21,274,940; `babshaggoth.glb`
  16,703,296; `lyra.glb` 18,471,380. All carry real skeletal animation
  (skin=1, 26 nodes, 18–20 clips per file, 72 channels per clip = 24-bone
  humanoid × 3 transforms).

---

## 2. What is broken

- `playAnimation()` is NOT being called. The rig sits in bind/T-pose forever
  because the static-pose guard at `Avatar3DActor.kt:324-330` only logs
  `mood=${mood.label} ... (animation playback disabled: static pose mode)`.
- Default placement is "standing in the middle of the chat like she owns the
  viewport" (operator quote). She obstructs the conversation.
- Reset/persist contract is **not anchored to the composer** — it uses magic
  pixels. Different screen sizes, fold states, or chat configurations will
  mis-anchor her.

---

## 3. Files in scope

- `app/src/main/java/com/example/ui/components/Avatar3DActor.kt`
  - Enable `playAnimation()` from the mood → clip map.
  - Print runtime animation catalogue: log every clip name + duration once
    on load (already have on-disk binary; log them from
    `instance.animator.animationCount` and accessor data).
- `app/src/main/java/com/example/ui/components/PurpAngolinOverlayActor.kt`
  - Compute `sitAnchorX` from the composer right edge,
    `sitAnchorY` from the composer top edge. **No magic pixels**.
  - Wire `gesturesEnabled = (actionSheetTurn == null)` through into the
    gesture handler (already partially there at line 256).
  - Soft-snap to seated position when drag releases within
    `avatarSeatInset` of the composer.
  - Persist `pose` (current clip key) in `purpclaw_avatar_prefs`.
  - Bump `layout_version` 6 → 7 so this contract retires the post-Step-2
    geometry cleanly. Reset defaults to **seated on right edge**.
- `app/src/main/java/com/example/ui/screens/CommandScreen.kt`
  - Expose the composer's right edge + top edge via a `BoundsObserver` (or
    a `BoxWithConstraints` wrapper that writes the bounds into a
    remembered state). The overlay needs this to seat the avatar.
  - Pass `gesturesEnabled = actionSheetTurn == null` into the overlay.
- `app/src/main/assets/avatars/{purpangolin,babshaggoth,lyra}.glb`
  - **No changes.** The bundles already carry the animation clips. Use what
    is on disk.

---

## 4. Animation enablement contract

Per operator spec:

```
4.1  Load animated GLB with NO autoplay first.
4.2  Print runtime animation catalogue.
4.3  Play ONE idle animation only.
4.4  If stable on physical S25 → individual clip certification.
4.5  If one clip crashes, quarantine that clip and continue testing others.
4.6  If every skinned clip crashes, investigate SceneView/Filament
     animation compatibility while preserving the canonical GLB.
4.7  PASS only when the real Gothic/Neon character visibly moves on S25.
```

### 4.1 Initial load (no autoplay)

In `Avatar3DModel`, after the skin/animation admission gate passes, set
`autoAnimate = false` (already done at line 207). Do **not** call
`playAnimation()` until the user has triggered a mood transition.

### 4.2 Print runtime animation catalogue

In the same `LaunchedEffect` that currently logs the static-pose line, also
enumerate the actual clips from `instance.animator.animationCount` and
matching accessor names (the rig's `gltf` accessors carry the clip input
buffers; read accessor names from the parse cache). Format:

```
I Avatar3DActor: cat character=purpangolin count=20
I Avatar3DActor:   [00] Agree_Gesture 8.17s
I Avatar3DActor:   [01] All_Night_Dance 5.33s
...
I Avatar3DActor:   [11] Chair_Sit_Idle_F 11.50s  <-- default idle
...
```

### 4.3 Play ONE idle clip (Chair_Sit_Idle_F)

When the canonical mood is `IDLE` (or `LISTENING`), call
`admittedModelInstance.animator.applyAnimation(N, time)` where `N` is the
index of `Chair_Sit_Idle_F` in the resolved clip list. Do NOT use
`autoAnimate = true` — drive the time explicitly so we keep authority over
the playback head (SpeechPulse / ThinkingPulse / FlourishPulse all need
this to swap clips without race).

Initial playback time: 0f. Loop on `time >= duration`.

### 4.4 Per-clip certification order (S25 physical, 1 build per batch)

```
clip 0: Chair_Sit_Idle_F      (default SIT)
clip 1: Walking               (WALK)
clip 2: Big_Wave_Hello        (WAVE / FLOURISH)
clip 3: Agree_Gesture         (SPEAK / gentle gesture)
clip 4: Confused_Scratch      (THINK)
clip 5: Happy_jump_f          (FLOURISH / big reaction)
clip 6: Running               (WORKING)
clip 7: Flirty_Strut          (COY/banter)
clip 8: Finger_Wag_No         (DEGRADED soft-nope)
clip 9: Angry_Stomp           (FAILED - WARNING: heavy movement)
clip 10: All_Night_Dance      (DANCE)
clip 11: Burpee_Exercise      (EXERCISE - WARNING: drop to floor)
clip 12: Cardio_Dance         (WORKOUT)
clip 13: Backflip_and_Hooks   (FLIP - WARNING: extreme pose)
clip 14: Boxing_Guard_Right_Straight_Kick (FIGHT - WARNING)
clip 15: Boxing_Warmup        (WARMUP)
clip 16: Grab_Bar_and_Swing_Forward (ACRO - WARNING)
clip 17: Happy_Sway_Standing   (FLOURISH small)
clip 18: Angry_Ground_Stomp_1  (DEGRADED strong)
clip 19: Angry_Ground_Stomp_2  (DEGRADED strong)
```

For each clip: assemble → install → launch → drive the mood that maps to
it → screencap → logcat → confirm no SIGSEGV in
`libgltfio-jni`/`AndroidRuntime`/`libc`. If a clip crashes, **add its name
to a `QUARANTINED_CLIPS` set in the source**, leave it in the catalogue log
for visibility, and remove it from the mood → clip map. Continue.

### 4.5 Why explicit `applyAnimation` over `playAnimation`

`playAnimation()` is convenient but starts its own loop on the animator
thread; cross-clip swaps then race. Driving `applyAnimation(idx, t)` on
Compose recomposition keeps clip swaps under our control. If the rig
*must* use `playAnimation()` to advance the time, wrap it in a
`LaunchedEffect` keyed on `mood + clipName` so cancelling one animation
cancels the next cleanly.

---

## 5. Default pose + anchor contract

Per operator spec:

```
DEFAULT AVATAR STATE
- pose = SIT  (Chair_Sit_Idle_F loop)
- anchor = composer.rightEdge
- feet/hips aligned to composer top edge
- body offset slightly upward so she looks seated on the composer
- right side placement by default
- preserve full chat readability
- do not cover send/mic controls
```

### 5.1 Anchor arithmetic

`CommandScreen.kt` exposes the composer bounds. The overlay reads them via
`remember { mutableStateOf(ComposerBounds(...)) }` updated by a
`BoundsObserver` sitting inside the composer `Row`. The overlay then
computes:

```
sitAnchorX = composerBounds.right - avatarSeatInset   // px from screen left
sitAnchorY = composerBounds.top                       // px from screen top
avatarSeatInset = 16.dp.toPx()  // tuned so she doesn't cover the send/mic icons
```

`purpclaw_avatar_prefs` stores these as `sitX`, `sitY`, `sitScale`. Reset
on `layout_version` bump writes them to defaults (sit on right composer
edge at scale 1.0).

### 5.2 Default `translationX/Y` math

In `PurpAngolinOverlayActor.kt`:

```
panWorldX = (offset.x / widthPx) * 1.6f
panWorldY = -(offset.y / heightPx) * 1.6f
```

When the rig is anchored to the right edge, the world translation needed
to land her `sitAnchor` (hip bone) on `composerBounds.top` is:

```
feetusOffsetY = rigHalfHeight * combined * 1.0f  // she sits with feet at hip-line
defaultOffsetY = -(feetusOffsetY - sitAnchorScreenY_fromTop) * (heightPx / 1.6f) * sign
defaultOffsetX = (sitAnchorScreenX - widthPx/2) * (widthPx / 1.6f)  // px from centre
```

Tune ±0.05 until the operator confirms the seat looks right.

### 5.3 Pinch zoom contract

```
two-finger pinch on avatar → scale continuously
clamp scale to [0.4, 2.5]
preserve anchor position while scaling (recompute sitAnchorX/Y from current
  composer bounds during pinch so she grows in place)
persist scale across app restart
double-tap = hide (already wired) — DOES NOT reset scale
Reset (corner square) = seated on right composer edge at default scale
```

If pinch lands inside the gesture handle (`BottomEnd` 220×280dp / 320×380dp)
the current `detectTransformGestures` handler at
`PurpAngolinOverlayActor.kt:257-268` already updates `scale` via `zoom`.
The clamp `0.20..2.5` is correct but should be tightened to `0.40..2.5` so
a phone-width sit never produces a 0.2× lady.

### 5.4 Drag + soft-snap-back

```
one-finger drag → moves her anywhere
on gesture end:
  if abs(anchor.x - composerBounds.right + avatarSeatInset) < 80.dp && 
     abs(anchor.y - composerBounds.top) < 80.dp:
    animate back to seated default via animateOffsetAsState
  else:
    persist current position as user-defined anchor
```

### 5.5 Touch pass-through

The empty stage must still pass touches to chat. Only the projected avatar
body captures gestures. Today the gesture handle is a `BottomEnd` Box that
captures touches anywhere inside it. Replace with a hit-region computed
from the rig's bounding-box projection OR (cheaper) keep the BottomEnd
handle but shrink it to the projected rig bounds. Until then, do not
expand the handle.

### 5.6 Persisted state contract

```
purpclaw_avatar_prefs keys:
  scale          (Float, 0.4..2.5, default 1.0)
  offsetX        (Int px, sanitised against world-bounds)
  offsetY        (Int px, sanitised against world-bounds)
  rotationY      (Float deg, -720..720, default 0f)
  hidden         (Boolean, default false)
  reduced        (Boolean, default false)
  pose           (String, default "Chair_Sit_Idle_F")
  layout_version (Int, current 7 — bump to force defaults reset)
```

`LaunchedEffect(Unit)` on overlay mount must:
1. Compare stored `layout_version` to current — if `< 7`, force defaults
   and persist.
2. Sanitise `offsetX/Y` against the world-bounds clamp
   `abs((offset.x / widthPx) * 1.6f) <= 1.0`.
3. If sanitisation changed anything, persist immediately.

---

## 6. Mood → clip map (canonical)

```
purpangolin:
  IDLE       -> Chair_Sit_Idle_F     (default; 11.5s loop)
  LISTENING  -> Chair_Sit_Idle_F     (calm sit while user types)
  THINKING   -> Confused_Scratch     (3.2s; thinking scratch)
  SPEAKING   -> Agree_Gesture        (8.2s; gentle gesture while speaking)
  ACTIVE     -> Walking              (5.0s loop; "she's moving")
  WORKING    -> Running              (11.3s loop; heavy work)
  FLOURISH   -> Happy_jump_f         (6.3s; big reaction)
  BOUNCING   -> Big_Wave_Hello       (2.5s; bounce in seat)
  DEGRADED   -> Finger_Wag_No        (9.8s; soft no)
  FAILED     -> Angry_Stomp          (13.0s; WARNING: heavy)
```

If a clip is quarantined in §4.4, fall back through the chain
`primary → FALLBACKS[mood] → first clip containing "Sit_Idle" → first clip
containing "Idle" → first clip`. Never crash, never fabricate a cycle,
never restore the 2D fallback path.

---

## 7. Acceptance tests (run on physical S25 RZCY9172MDP)

1. `gradlew :app:compileDebugKotlin` — must succeed.
2. `gradlew :app:testDebugUnitTest` — must pass; `Avatar3DClipsTest` still PASS.
3. `gradlew :app:assembleDebug` — APK at
   `app/build/outputs/apk/debug/app-debug.apk`.
4. Install (`adb install -r ...`). Force-stop first.
5. Launch. Confirm PurpClaw focuses. Confirm avatar appears seated on the
   right edge of the composer, fastened to the composer top, NOT covering
   send/mic icons.
6. Logcat: every clip name logged at INFO. No SIGSEGV in `libc`/`libgltfio-jni`.
7. Capture `screencap -p /sdcard/avatar_seated.png` → `.tmp/avatar_seated.png`.
   Visual PASS requires:
   - Full rig visible head-to-boots.
   - Feet/hips near composer top.
   - Head clear of status bar.
   - Body on right edge, not blocking chat centre.
   - Reset chip visible inside screen.
8. Drive `IDLE` mood → rig plays Chair_Sit_Idle_F loop. Confirm via
   repeated screencap over 3s — different frames (movement proves playback).
9. Drive `SPEAKING` mood → rig swaps to Agree_Gesture.
10. Drive `THINKING` mood → rig swaps to Confused_Scratch.
11. Pinch out → rig grows. Pinch in → rig shrinks. Clamp respected.
12. Drag to centre of screen → rig follows. Drop near composer → rig
    animates back to seated.
13. Long-press → reduced-motion. Chair_Sit_Idle_F freezes (or static
    pose replaced). Long-press again → motion resumes.
14. Double-tap → rig hides; visibility icon reappears; tap icon → rig
    back, seated.
15. Reset chip tap → rig animates back to seated at default scale.
16. Force-stop, relaunch → rig appears seated at persisted scale.
17. If any clip crashes during step 8/9/10, quarantine its name and
    re-run for the next clip on the list (§4.4). Continue until either
    every clip is certified OR all skinned clips crash (then escalate —
    don't patch over with 2D).

---

## 8. Hard rules

- Do **not** touch the bundled GLB files. All three are confirmed animated.
- Do **not** restore any 2D fallback. `PurpAngolinAvatar` stays for the
  load-admission-rejection path (skin=0 or animationCount=0), not for
  runtime fallback.
- Do **not** add `playAnimation()` autoplay. Drive time explicitly via
  `applyAnimation(idx, t)` from Compose recomposition.
- Do **not** use magic pixels. Composer bounds come from
  `CommandScreen.kt`'s `BoundsObserver`. Reset values come from
  `sitAnchorX = composer.right - avatarSeatInset`.
- Do **not** change DB schema. `PurpClawDatabase.kt` stays at v4.
- Do **not** commit. Local tree + live probes only.

---

## 9. Out of scope (other lanes own these)

- Empty-visible-response recovery (`[no visible reply from model]`) —
  P0 lane, separate spec.
- Chat reply card sizing (12sp is correct) — already done.
- Memory weave / podcast / settings / TTS — separate lanes.

---

## 10. Definition of done

The task is DONE when, in one continuous run:
- All four files above edited per the contract.
- `layout_version` bumped 6 → 7 in `PurpAngolinOverlayActor.kt`.
- compile + unit tests + assembleDebug all green.
- APK installed on S25, launched, rig visible seated on right edge of
  composer.
- Logcat shows full animation catalogue for the active character.
- Rig plays at least `Chair_Sit_Idle_F` (idle) and one non-idle clip
  (e.g. `Walking` or `Big_Wave_Hello`) without SIGSEGV.
- Pinch zoom, drag, soft-snap-back, hide/show, reset all work on glass.
- Force-stop / relaunch restores seated pose at persisted scale.
- Clip-by-clip certification log written to `.tmp/clip_cert_<char>.log`
  for at least 5 clips, including at least one quarantine entry if any
  clip crashed during this session.
- Screenshot `.tmp/avatar_seated.png` saved and visually confirmed.

Until all of that exists on disk + emulator + glass, the task is not done.
