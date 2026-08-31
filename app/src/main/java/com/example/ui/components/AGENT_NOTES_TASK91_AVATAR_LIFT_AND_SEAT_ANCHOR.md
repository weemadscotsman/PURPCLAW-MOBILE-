---
name: AGENT_NOTES_TASK91_AVATAR_LIFT_AND_SEAT_ANCHOR
description: Lift static-pose guard + add sit-on-composer-right anchor + soft-snap-back + clip-by-clip certification. Builds on AGENT_NOTES_TASK63 spec; addresses the two source gaps (Avatar3DActor.kt:324-330, PurpAngolinOverlayActor.kt:IntOffset.Zero default) that block #11 and #12 verification.
type: project
---

# Mobile 3D Avatar — Animation Lift + Seat Anchor — Lane #91 (2026-08-28 02:48Z)

## Why this lane exists

Operator ratified (2026-08-28 01:25Z) that the **canonical mobile character is the Gothic/Neon Meshy character**, full-body T-pose already renders on S25 (`purpangolin.glb`, 21.27 MB, 26 nodes, 20 clips, 72 channels each, default SIT = `Chair_Sit_Idle_F`).

Two remaining gaps block verification:

1. **Task #11 — animation playback RED in source.** `Avatar3DActor.kt:324-330` carries a static-pose guard that logs `mood=... (animation playback disabled: static pose mode)` and **never calls `playAnimation()`**. Logcat confirms: no `mood=` line, only `applyTransform` lines.
2. **Task #12 — sit-on-composer-right anchor GAP in source.** `PurpAngolinOverlayActor.kt` defaults `offset = IntOffset.Zero` (center of stage). The contract spec (`AGENT_NOTES_TASK63_ANIMATION_AND_SEAT_ANCHOR.md §5`) requires `sitAnchorX = composerBounds.right - avatarSeatInset (16.dp.toPx())` and `sitAnchorY = composerBounds.top`. Pinch clamp `0.40..2.5` and `maxOffX/Y = screenW/H / 1.6f` sanitization are already in source.

This spec closes both gaps and adds the soft-snap-back animation.

## Scope (in)

1. **Lift the static-pose guard** in `Avatar3DActor.kt:324-330`. Animation drives from explicit `applyAnimation(idx, t)` calls driven by Compose recomposition with advancing `t` — NOT from `playAnimation()` autoplay (race-free cross-clip swaps).
2. **Add `sitAnchorX/Y`** computation from composer bounds in `PurpAngolinOverlayActor.kt`. Default `offset` lands at the anchor on first launch (when `layout_version < 7`) and on tap of the reset square.
3. **Soft-snap-back animation** — when the user releases a drag within 80 dp of the anchor, the rig animates back to the seat over ~250 ms with `FastOutSlowInEasing`. Outside 80 dp it stays where released.
4. **Clip-by-clip certification** order from `AGENT_NOTES_TASK63 §4.4` — start with `Chair_Sit_Idle_F` (lowest risk), then progressively enable others. Quarantine any clip that SIGSEGVs; never ship a build that includes a known-crashing clip.
5. **Driver contract** — `applyAnimation(idx, t)` invoked from `LaunchedEffect(mood)` keyed on mood, with `t` advancing per frame via `withFrameNanos`. NO autoplay loop.

## Out of scope (deferred)

- Cross-character swap UI (Lyra / Babshaggoth) — default is purpangolin only.
- Voice → viseme mouth animation — separate lane (#83).
- Avatar emotional expressions beyond mood → clip map — already covered by `AGENT_NOTES_TASK63 §4`.
- External animation authoring — only the bundled Meshy GLB clips are in scope.

## Hard rules

- **NEVER enable `playAnimation()` autoplay** — race conditions across clip swaps on Adreno 830. Use explicit `applyAnimation(idx, t)` only.
- **NEVER disable `loadAdmission`** — the `skinCount>0 && animator.animationCount>0` gate is the SIGSEGV firewall. If a clip fails to load, fall back to the next in the mood map; if all fail, fall back to a different GLB; if all fail, fall back to `PurpAngolinAvatar` 2D placeholder.
- **NEVER bypass sanitization** — `maxOffX/Y = screenW/H / 1.6f` clamp must run on every launch AND every drag release.
- **NEVER store raw pixel offsets** — always store `IntOffset` in screen pixels, compute world units at render time.
- **NEVER let the reset chip leave the screen** — anchored to `BottomEnd` of the stage, not offset by the rig's stored position.
- **layout_version bump**: 7 → 8 (this lane). Existing 7-pinned installs reset to defaults on upgrade.
- **No layout_version downgrade** — once a build ships at version N, all prior N-1 installs must reset to defaults when they see N.

## Files in scope (read-only this session; builder lane implements)

### Must read first

- `app/src/main/java/com/example/ui/components/Avatar3DActor.kt` — static-pose guard at lines 324-330; mood→clip map elsewhere.
- `app/src/main/java/com/example/ui/components/PurpAngolinOverlayActor.kt` — gesture handle, persistence, sanitization, default `offset = IntOffset.Zero`.
- `app/src/main/java/com/example/ui/components/AGENT_NOTES_TASK63_ANIMATION_AND_SEAT_ANCHOR.md` — canonical spec this lane implements.
- `app/src/main/java/com/example/ui/screens/CommandScreen.kt` — composer bounds source for the anchor computation.
- `app/src/main/assets/avatars/purpangolin.glb` — bundled rig (verify `Chair_Sit_Idle_F` exists with `applyAnimation(0, t)` returning non-zero bone transforms).

### To write / modify

- `app/src/main/java/com/example/ui/components/Avatar3DActor.kt` — remove static-pose guard, wire `applyAnimation(idx, t)` per-frame.
- `app/src/main/java/com/example/ui/components/PurpAngolinOverlayActor.kt` — add `sitAnchorX/Y`, soft-snap-back, layout_version=8, default-offset change to anchor.
- `app/src/main/java/com/example/ui/components/PurpAvatarSeatAnchor.kt` — new composable helper that computes seat anchor from `composerBounds: Rect` (passed in from `CommandScreen`).

## Implementation contract

### `Avatar3DActor` lift (replace lines 324-330)

```kotlin
// REMOVE: static-pose guard
// if (animationPlaybackDisabled) { Log.w(TAG, "static pose mode"); return }

// ADD: explicit per-frame driver
@Composable
fun Avatar3DActor(
  mood: PurpAngolinMood,
  ...
) {
  val clipIndex = remember(mood) { moodToClipIndex(mood) }
  var frameTimeNanos by remember { mutableLongStateOf(0L) }
  LaunchedEffect(clipIndex) {
    // Reset to clip start on mood change; advance t per frame
    val startNanos = withFrameNanos { it }
    while (true) {
      withFrameNanos { nowNanos ->
        frameTimeNanos = nowNanos - startNanos
      }
    }
  }
  Scene(
    ...
  ) {
    // Drive the rig with advancing t
    applyAnimation(clipIndex, frameTimeNanos / 1_000_000_000f)  // ns → s
  }
}
```

The `applyAnimation` call replaces the existing `applyTransform` line. The driver's only job is to advance `t` — no autoplay loop.

### `PurpAngolinOverlayActor` anchor (replace IntOffset.Zero default)

```kotlin
@Composable
fun PurpAngolinOverlayActor(
  ...
  composerBounds: Rect? = null,  // passed in from CommandScreen
  ...
) {
  val layoutVersion = 8  // BUMP from 7
  ...
  val avatarSeatInset = 16.dp
  val seatAnchorX = composerBounds?.let {
    (it.right - avatarSeatInset.toPx()).roundToInt()
  } ?: 0
  val seatAnchorY = composerBounds?.top?.roundToInt() ?: 0
  ...
  var offset by remember {
    mutableStateOf(
      if (resetLegacyGeometry) IntOffset(seatAnchorX, seatAnchorY)
      else sanitizeOffset(prefs.getInt("dX", seatAnchorX), prefs.getInt("dY", seatAnchorY))
    )
  }
  ...
}
```

### Soft-snap-back (in `detectTransformGestures`)

```kotlin
detectTransformGestures { _, pan, zoom, rotation ->
  // ... existing pinch + rotate ...
  val newOffset = IntOffset(
    offset.x.coerceIn(-maxPxX, maxPxX) + pan.x.roundToInt(),
    offset.y.coerceIn(-maxPxY, maxPxY) + pan.y.roundToInt()
  )
  offset = newOffset
}

// After pointer release (separate LaunchedEffect):
LaunchedEffect(offset) {
  val distToAnchor = sqrt(
    ((offset.x - seatAnchorX).toFloat()).pow(2) +
    ((offset.y - seatAnchorY).toFloat()).pow(2)
  )
  if (distToAnchor < 80.dp.toPx()) {
    // Animate to anchor
    animate(
      initialValue = offset,
      targetValue = IntOffset(seatAnchorX, seatAnchorY),
      animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
    ) { value, _ -> offset = value }
  }
}
```

## Clip-by-clip certification order

Per `AGENT_NOTES_TASK63 §4.4`:

1. **`Chair_Sit_Idle_F`** — first clip. Default SIT pose, 11.5 s loop, low bone complexity. Run 60 s on glass, check `libgltfio-jni` logcat for SIGSEGV. If clean, mark CERTIFIED.
2. **`Idle_*` family** — second. Static-or-slow-motion clips.
3. **`Walk_*` / `Run_*` family** — third. Higher bone velocity.
4. **`Dance_*` / `Wave_*` / `Turn_*` family** — fourth. Multi-channel choreography.
5. **`Fight_*` / `Kick_*` / `Punch_*` family** — fifth. Aggressive motion, highest bone complexity.
6. **`Flip_*` / `Jump_*` / `Climb_*` family** — last. Extreme motion.

A clip that SIGSEGVs is **quarantined** — added to a `BLOCKED_CLIPS` set in `Avatar3DActor`, never mapped from a mood, and surfaced in the build's `BLOCKED.md` ledger for the next lane. **Never ship a build that includes a known-crashing clip in the active map.**

## Mood → clip map (initial)

| Mood | Clip | Cert tier |
|---|---|---|
| IDLE | `Chair_Sit_Idle_F` | 1 |
| LISTENING | `Chair_Sit_Idle_F` (loop) | 1 |
| THINKING | `Chair_Sit_Idle_F` (loop, head tilt via rotation) | 1 |
| ROUTING | `Idle_03` | 2 |
| WORKING | `Idle_05` | 2 |
| WAITING_TOOL | `Idle_07` | 2 |
| WAITING_AGENT | `Idle_10` | 2 |
| STREAMING | `Idle_15` | 2 |
| SPEAKING | `Idle_15` (with speech pulse) | 2 |
| SUCCESS | `victory` | 3 |
| ERROR | `Confused_Scratch` | 3 |
| INTERRUPTED | `Idle_07` (with shake) | 3 |

Clips in tiers 4–6 remain unmapped until certified.

## Acceptance battery (on-glass, S25)

1. **Render** — launch app → 3D rig visible head-to-toes in gothic/neon character, NOT 2D fallback placeholder. Static pose at `Chair_Sit_Idle_F`.
2. **Anchor default** — on fresh install, avatar sits at composer-right edge, hips at composer-top. NOT centered.
3. **Pinch** — inside gesture handle, pinch out → rig scales up to 2.5×, pinch in → down to 0.40×. Persists on relaunch.
4. **Drag** — drag inside gesture handle → rig follows finger, clamped to ±screen/1.6. Persists on relaunch.
5. **Snap-back** — drag rig to within 80 dp of anchor → release → rig animates back to anchor over 250 ms.
6. **Rotate** — two-finger rotate inside gesture handle → rig spins on Y axis. Persists on relaunch.
7. **Reset** — tap reset square → rig returns to anchor at scale 1.0, rotation 0.
8. **Hide / show** — double-tap gesture handle → rig hides + small visibility chip appears. Tap chip → rig returns.
9. **Long-press reduced motion** — long-press inside gesture handle → rig enters reduced-motion (static, smaller scale). Long-press again → restored.
10. **Animation playback** — mood cycles through IDLE → THINKING → SPEAKING → SUCCESS → IDLE via a forced state bus test. Rig visibly animates (not just pose swaps). `dumpsys gfxinfo com.aistudio.purpclaw.osv7` reports non-zero bone-transform frame deltas.
11. **No SIGSEGV** — full animation battery does not crash `libgltfio-jni` or `AndroidRuntime`. logcat clean of `SIGSEGV` / `libc: Fatal signal 11`.
12. **Sibling regression** — chat list scrolls smoothly, composer types text, action rail opens/closes, voice mode toggle works. No regression in any sibling surface.

Until 12/12 PASS on glass, this lane is **NOT VERIFIED**.

## Risks / known unknowns

- **Adreno 830 + libgltfio-jni skinning path** — the static-pose guard was added precisely because `playAnimation` SIGSEGV'd. Explicit `applyAnimation(idx, t)` may behave differently. If it still SIGSEGVs, the lane must either drop back to `playAnimation` (with race-conditions accepted as documented debt) or fall back to a different rig format.
- **Composer bounds availability** — `PurpAngolinOverlayActor` is currently called from somewhere in the screen tree without composer bounds. The builder lane must thread `composerBounds: Rect` from `CommandScreen` down through whatever hosts the overlay actor.
- **Speech pulse interaction** — the existing `speechPulse = 1f + (outputLevel * 0.18f)` modifier multiplies scale. If animation playback also drives scale, the two will fight. Spec assumes builder lane gates `speechPulse` when animation is active.
- **Garbage collection during clip swap** — `applyAnimation(idx, t)` with `idx` changing rapidly can leak `Animator` instances. Builder lane must `destroy()` the previous animator before binding the new one.

## Definition of done

- All 12 acceptance points PASS on S25 (RZCY9172MDP).
- Static-pose guard removed; `applyAnimation(idx, t)` runs per frame.
- Sit-anchor at composer-right edge, hips at composer-top, on fresh install and after reset.
- Soft-snap-back animation works within 80 dp of anchor.
- Pinch/drag/rotate/hide/show/reduced-motion persist across relaunch.
- Clip-by-clip certification complete for tiers 1–2; tier 3 may remain unmapped.
- No SIGSEGV in any certified clip.
- Triple-verified by an independent verifier agent with on-glass artifacts (logcat + screencap or, since screencap is Secure-flagged, `dumpsys SurfaceFlinger` + frame counters).
