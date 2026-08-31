# TASK #63 — Mobile 3D Avatar Repair

Status: HANDOFF FROM CONSULTANT LANE → BUILDER LANE
Date: 2026-08-27
Author: spec lane (this is not a code patch — it is a build contract)

This file is the canonical handoff for the next PurpClaw builder session.
Read it cover to cover before editing `Avatar3DActor.kt` or
`PurpAngolinOverlayActor.kt`. Do not start by redesigning the avatar.

---

## 1. What is wrong on device

Symptoms reported by the operator on the physical S25 (2026-08-27):

- The avatar is rendered inside a tiny clipped viewport — only ~1% of the
  character is visible.
- A hard line cuts off anything above the rig.
- The avatar moves around inside that square when dragged, but the full
  character never appears, no matter the scale.
- Pinch / drag / rotate gestures still fire (state changes), but the rig
  never reaches the screen edges because the viewport is the bottleneck.

Three independent root causes were identified in the source as it sits on
disk today. All three must be fixed together; fixing only one leaves the
other two still broken on device.

---

## 2. Files in scope

- `app/src/main/java/com/example/ui/components/Avatar3DActor.kt`
  - Owns the SceneView/Filament stage, camera, model node, animation
    selection, and the skin/animation admission gate.
- `app/src/main/java/com/example/ui/components/PurpAngolinOverlayActor.kt`
  - Owns the overlay Box, the gesture detection (drag / pinch / rotate /
    double-tap / long-press), persistence of scale + offset + rotation,
    and the model-position math.
- `app/src/main/java/com/example/ui/components/AssistantTurnCard.kt`
  - Owns the per-reply chat bubble; `TurnView` lives in this file. The
    reply-tap-disable-avatar rule lands here, not in the avatar files.
- `app/src/main/java/com/example/ui/screens/CommandScreen.kt`
  - Owns the action sheet that is shown on long-press. The bubble
    long-press is what must suppress avatar gestures.

Do not touch any other file in this lane. The DB migration protection,
turn ordering, and persistence wiring are all already correct on disk
and must be preserved.

---

## 3. Root cause A — camera sits at the rig's feet, not its chest

Current code in `Avatar3DActor.kt` `Avatar3DModel`:

```kotlin
val cameraTarget = rememberNode(engine)
val cameraNode = rememberCameraNode(engine) {
  position = Position(x = 0f, y = 0f, z = 2.0f)
  lookAt(cameraTarget)
  cameraTarget.addChildNode(this)
}
```

Meshy bipeds are bottom-origin. With `centerOrigin = Position(0f, -0.5f, 0f)`
and `scaleToUnits = 1.0f`, the visible centre of the rig is around
`y ≈ 0.5` (waist/chest). The camera is at `y = 0` aimed at `y = 0`,
which means it is pointing at the rig's ankles. From that eye-line, a
~1.7m tall biped extends mostly *above* the viewport. The "tiny cutout"
the operator sees is the shoes poking into frame.

Fix:

- Place `cameraTarget` at `Position(0f, 0.9f, 0f)` so the rig's chest is
  the lookAt centre. Do NOT leave it at the origin.
- Place the camera at `Position(0f, 0.9f, 3.4f)` — same height as the
  target, 3.4 units back. This gives a non-zero baseline so `lookAt`
  produces a real view matrix.
- Set `scaleToUnits = 1.6f` (tune ±0.2 if the head clips the top edge).
- Remove the `onFrame = { cameraNode.lookAt(cameraTarget) }` from the
  `Scene(...)` composable. Once-per-mount is enough and per-frame is the
  cause of root cause B.

These numbers are derived from the current `scaleToUnits = 1.0f` and the
fact that the rigs sit on the XZ plane at the origin. Different Meshy
characters will need slight tuning — measure the rig bounds if the head
still clips.

---

## 4. Root cause B — per-frame `lookAt` collapses to zero baseline

Current code:

```kotlin
Scene(
  modifier = modifier,
  engine = engine,
  modelLoader = modelLoader,
  cameraNode = cameraNode,
  isOpaque = false,
  cameraManipulator = null,
  childNodes = listOf(cameraTarget, modelNode),
  onFrame = { cameraNode.lookAt(cameraTarget) }
)
```

Because both the camera and `cameraTarget` are nodes parented to the
scene root at (or near) the origin, a zero-baseline `lookAt` produces an
undefined view matrix on Filament. The camera effectively stops aiming
the moment any movement happens.

Fix: remove `onFrame = { cameraNode.lookAt(cameraTarget) }`. The
one-shot `lookAt(cameraTarget)` inside `rememberCameraNode` is sufficient
once root cause A is fixed (real eye position, real target position,
non-zero baseline).

---

## 5. Root cause C — overlay translation math fights the camera frustum

Current code in `PurpAngolinOverlayActor.kt`:

```kotlin
val visibleHeight = 1.65f
val visibleWidth = visibleHeight * (widthPx / heightPx)
val modelX = 0.20f + (offset.x / widthPx) * visibleWidth
val modelY = -0.43f - (offset.y / heightPx) * visibleHeight
```

This positions the model in an abstract space that has nothing to do
with the camera frustum. Pan "down" slides the rig *further off the
bottom* of the screen. There is no clamp, no centre reset, no
relationship between drag pixels and the actual eye-line. The result is
that the rig is dragged off-screen and never returns.

Fix:

- Drop the `visibleHeight / visibleWidth` math entirely.
- Translate the model directly in its own world space:
  `modelNode.position = centredPosition + Position(translationX, translationY, 0f)`
  where `translationX` and `translationY` are user-driven offsets in
  world units.
- Clamp `translationX` to ±1.2 units and `translationY` to ±1.6 units
  so the rig cannot be dragged off-screen at any reasonable scale.
- Reset defaults: `scale = 0.65f`, `offset = IntOffset.Zero`,
  `rotationY = 0f`.
- Bump `layout_version` from 3 to 4 inside the existing `prefs` block so
  existing installs stop reading the broken legacy geometry. Existing
  geometry reset path already exists — just feed it the new defaults.

---

## 6. Reply-tap disables avatar gesture interception

The avatar must remain visible while a per-message action sheet is open
(copy / read / retry / more) so the user can see her reading along.
But the avatar must NOT swallow the tap that opens the action sheet, and
must NOT keep intercepting drags while the sheet is open — otherwise the
user can't copy a reply or hit a sheet button without fighting her.

The cleanest way to do this in Compose: the action sheet lives inside
`CommandScreen.kt` (`actionSheetTurn` state). When that state is
non-null, pass a `gesturesEnabled: Boolean` flag into the overlay (and
through into the gesture detectors). The overlay composable already has
this hook shape — just wire the flag through.

Specifically:

- Add `interactionEnabled: Boolean` parameter to `PurpAngolinOverlayActor`
  (the parameter already exists in the file but is currently unused for
  blocking the gesture detectors while a bubble sheet is open — extend
  it).
- In `CommandScreen.kt`, pass
  `gesturesEnabled = actionSheetTurn == null` into the overlay.
- Keep the avatar visible (do NOT hide her); just stop her
  `pointerInput { detectTransformGestures ... }` from firing while the
  sheet is open. The simplest implementation: in the
  `Modifier.then(if (interactionEnabled) ... else Modifier)` block, also
  gate the `detectTransformGestures` modifier.

The bubble's own `combinedClickable` (in `AssistantTurnCard.kt`) does not
need to change. The gesture arbitration is one-way: avatar yields to
chat, chat does not yield to avatar.

---

## 7. Smaller chat reply text

`AssistantTurnCard.kt` line ~242 renders the assistant reply at
`fontSize = 12.5.sp` with `lineHeight = 17.sp`. The user reply
(`UserTurnCard.kt` line ~155) is the same. Reduce both to
`fontSize = 12.sp`, `lineHeight = 16.sp`. That is the size the previous
build landed at and what the operator confirmed as readable in chat.

Do not touch font sizes for status boxes, route receipts, telemetry, or
the per-reply PurpAngolin badge. Only the reply body text.

---

## 8. Chronological message ordering — already correct, do not touch

`MainViewModel.kt` already sorts `turns` with
`compareBy<TurnRecord> { it.timestamp }.thenBy { it.sequence }` and the
Room query in `PurpClawDatabase.kt` `TurnDao.getTurnsForSession` is
`ORDER BY timestamp ASC, sequence ASC`. Both are correct. The previous
build landed the ordering fix and the operator confirmed it. Preserve
both exactly.

Persistence across force-stop / relaunch is already correct via Room.
The destructive `fallbackToDestructiveMigration()` is already removed
from `PurpClawDatabase.kt` — the builder simply calls `.build()` with
no destructive fallback. Preserve that. Do not re-add
`fallbackToDestructiveMigrationOnDowngrade()` or any other silent wipe
path. If Room ever asks for a missing migration, the right answer is to
add the migration, not to wipe user data.

---

## 9. Acceptance tests the builder must run

Run all of these before declaring done. Receipts required for each.

1. `./gradlew :app:compileDebugKotlin` — must succeed cleanly.
2. `./gradlew :app:testDebugUnitTest` — must pass; existing test
   `Avatar3DClipsTest.kt` must still PASS.
3. `./gradlew :app:assembleDebug` — APK produced at
   `app/build/outputs/apk/debug/app-debug.apk`.
4. Install on emulator (`adb install -r ...`). Launch via
   `adb shell am start -n com.example/.MainActivity`.
5. Drive `CommandScreen` from a Compose UI test or via `adb shell input`
   taps:
   - `adb shell screencap -p /sdcard/avatar_default.png` — full body
     visible, no clipping line.
   - Drag the avatar to each of the four corners. Capture after each.
     Rig must remain visible at every position.
   - Pinch out. Rig must grow but not clip the top of the viewport.
   - Pinch in. Rig must shrink but stay legible.
   - Two-finger rotate. Rig must turn around its own vertical axis.
   - Long-press. Reduced-motion static sprite must replace the 3D rig.
     Long-press again. 3D rig must come back.
   - Double-tap. Avatar must hide; the visibility-control box must
     reappear; tapping it must restore the avatar.
   - Send a new user message; confirm it appears immediately above the
     composer in chronological order.
   - Wait for the assistant reply; confirm it lands below the user
     message, not above.
   - Long-press the assistant reply. Action sheet must appear. While the
     sheet is open, attempt to drag the avatar — it must NOT move.
     Dismiss the sheet. Avatar must become draggable again.
6. `adb shell am force-stop com.example` then relaunch. The previous
   conversation must still be visible. Avatar geometry must restore to
   the bumped `layout_version = 4` defaults.

If any of those fails, fix and re-run. Do not declare done on partial
receipts.

---

## 10. Hard rules

- Do not touch DB schema. `PurpClawDatabase.kt` version stays at 4. Do
  not add a destructive migration fallback under any circumstance.
- Do not change the GLB set. The three bundled assets
  (`purpangolin.glb`, `babshaggoth.glb`, `lyra.glb`) are the merged Meshy
  packs with skins. No new assets, no T-posed props, no skinned-only
  without animation.
- Do not relax the skin/animation admission gate in `Avatar3DActor.kt`.
  Models with `skinCount == 0` or `animator.animationCount == 0` must
  still be rejected and fall back to the 2D `PurpAngolinAvatar`.
- Do not bypass the gesture arbitration. The bubble long-press path
  must keep working; the avatar's drag/rotate must yield to it.
- Do not commit. The "no git commits — local only" rule from this
  workspace is still in force. Local tree + emulator + receipts only.

---

## 11. What this lane does NOT touch

- Provider routing (`ProviderRouter.kt`, `unified_api.js` on the desktop
  side) — that is the desktop builder's lane.
- Memory (`MemoryGateway.kt`, `seven_layer_memory` table) — already
  correct, do not touch.
- TTS gateway — already correct, do not touch.
- The `cockpit.html` web shell — separate surface, separate lane.
- The PurpClaw desktop installer — separate lane.

---

## 12. Definition of done

This task is done when the builder has, in one continuous run:

- Edited `Avatar3DActor.kt` and `PurpAngolinOverlayActor.kt` per the
  fixes above.
- Edited `AssistantTurnCard.kt` for the smaller reply text.
- Edited `CommandScreen.kt` to pass `gesturesEnabled =
  actionSheetTurn == null` into the overlay.
- Bumped `layout_version` to 4 in the overlay prefs.
- Compiled, unit-tested, assembled APK.
- Installed on emulator, launched, driven the gesture battery above.
- Captured screenshots: avatar_default, avatar_drag_corners,
  avatar_pinch_max, avatar_pinch_min, avatar_rotate, avatar_reduced_motion,
  chat_persistence_after_relaunch.
- Confirmed the destructive migration fallback is still absent from
  `PurpClawDatabase.kt`.

Until all of that exists on disk + emulator screenshots, the task is not
done.
