---
name: AGENT_NOTES_TASK103_SHRINKABLE_REACTIVE_AVATAR
description: Operator directive 2026-08-28 ~10:30Z. Replace merged-pack GLBs (one file per character, 18-20 clips each) with per-mood single-move GLB files stored in per-character folders; wire reactive mood → clip file mapping; lift 3D quarantine so the shrinkable avatar is back online on S25.
type: project
---

# Lane #103 — Shrinkable Reactive Avatar + Per-Mood Single-Move GLBs

**Operator framing (2026-08-28 ~10:30Z):**
> "I WANT THE SRINKABLE AVATAR BACK ONLINE WITH THOSE REACTIVE STATES NAD ABILTYS REALY MAPPED TO THEOSE SINGLE MOVE ANIMATION GLB FILES AND ORGED TO MAKE SURE EACH SET IS STORED SEEPRATEYL AND USED RIGHT"

Three coupled deliverables:
1. **Shrinkable avatar back online.** The S25 hardware quarantine (`Avatar3DActor.kt:38-57`, blocking libgltfio-jni SIGSEGV on Adreno 830) must be lifted so the 3D rig is actually visible.
2. **Reactive states + abilities mapped to single-move GLB files.** One GLB per state per character, no merged packs.
3. **Each character set stored separately.** Per-character folders under `assets/avatars/`; the loader must never cross-load across characters.

This is **DEGRADED-OPERATIONAL** today: the avatar renders as an invisible Box on S25 because the quarantine kills the 3D path. The 2D fallback (`PurpAngolinAvatar.kt`) does react to moods but is not the shrinkable 3D companion the operator is asking for.

---

## Truth baseline (already in source — what is right)

- `PurpAngolinOverlayActor.kt` — pinch clamp `0.40..1.0` (overlay shell, persists in `purpclaw_avatar_prefs`), drag clamped to `screenW / 1.6f`, double-tap hide, long-press reduced-motion, single-tap reset square at BottomEnd, default `scale=0.40f`. **Shrinkable shell = PRESENT in source.**
- `PurpAngolinAvatar.kt` — mood enum `PurpAngolinMood` (10 states: IDLE, ACTIVE, THINKING, LISTENING, SPEAKING, DEGRADED, FAILED, BOUNCING, FLOURISH) drives breathing/wobble/halo/bounce. **Reactive states contract = PRESENT in source.**
- `Avatar3DClips` (in `Avatar3DActor.kt:72-167`) — three mood→clip maps (`PURPANGOLIN`, `BABSHAGGOTH`, `LYRA`) over the merged-pack files. **Mapping contract = PRESENT in source, but maps clip NAME within a merged file rather than file PATH per mood.**
- `Avatar3DActor.kt` — loadAdmission gate (`skinCount>0 && animator.animationCount>0`), camera at `(0, 0.9, 3.4)` lookAt `(0, 0.9, 0)`, FOV axis `aspect>1 ? HORIZONTAL : VERTICAL`, hard quarantine on S25.
- `AGENT_NOTES_TASK91` — lane #91 spec lifts the static-pose guard and adds sit-on-composer-right anchor + soft-snap-back. Implementation pending; lane currently NOT VERIFIED.
- `AGENT_NOTES_TASK63` — canonical contract for animation playback (`applyAnimation(idx, t)` not `playAnimation()` autoplay), per-clip certification order, default SIT pose on `Chair_Sit_Idle_F`.

## Truth gap (what is wrong)

### Asset layer — WRONG SHAPE
```
$ ls -la app/src/main/assets/avatars/
babshaggoth.glb          16,703,296   18 clips merged
lyra.glb                 18,471,380   19 clips merged
purpangolin.glb          21,274,940   20 clips merged
purpangolin_idle_withSkin.glb 12,639,060  1 clip (idle only — this is the existing single-move reference)
```

Operator asks for: **one GLB per state per character**, each character set in its own folder.
Currently: each character is one merged file (or one idle test rig).

### Loader layer — maps NAME not FILE
`Avatar3DClips.clipFor()` returns a clip STRING inside the merged pack:
```kotlin
val primary = map[mood] ?: return null   // e.g. "Chair_Sit_Idle_F"
if (primary in available) return primary  // found in animator catalogue
```
It does NOT return a file path. There is no per-mood file lookup. The current `Avatar3DModel` loads ONE file per character (`assetPath = "avatars/$character.glb"` or `"avatars/purpangolin_idle_withSkin.glb"`).

### Hardware quarantine — visible-avatar blocker
`Avatar3DActor.kt:38-57` defaults `PURPCLAW_3D_AVATAR_DISABLED = true` on Samsung SM-S/e2q/dm3q devices. Verified 4/4 stable cycles with quarantine ON (PID 16258 / 16729 / 23161 / 25379, 0 SIGSEGV). Lifting requires either:
- (a) Path A from AGENT_NOTES_TASK42: sceneview 2.3.0 → 4.33.0 upgrade + camera re-tuning (RECOMMENDED)
- (b) Path C: drop sceneview wrapper, use `com.google.android.filament:filament-android` directly
- (c) keep quarantine + ship 2D fallback only (does NOT meet operator directive)

---

## Target architecture

### Asset layout (per-character folders, per-mood single-move files)

```
app/src/main/assets/avatars/
├── purpangolin/
│   ├── idle.glb               # Chair_Sit_Idle_F (or equivalent)
│   ├── listening.glb          # same as idle (loop)
│   ├── thinking.glb           # Confused_Scratch
│   ├── speaking.glb           # Agree_Gesture
│   ├── active.glb             # Walking
│   ├── bouncing.glb           # Big_Wave_Hello
│   ├── flourish.glb           # Happy_jump_f
│   ├── degraded.glb           # Finger_Wag_No
│   └── failed.glb             # Angry_Stomp
├── babshaggoth/
│   ├── idle.glb               # Idle_15
│   ├── listening.glb
│   ├── thinking.glb
│   ├── speaking.glb
│   ├── active.glb
│   ├── bouncing.glb
│   ├── flourish.glb
│   ├── degraded.glb
│   └── failed.glb
└── lyra/
    ├── idle.glb               # Idle_15
    ├── listening.glb          # Idle_03
    ├── thinking.glb           # Idle_12
    ├── speaking.glb           # Wave_for_Help_3
    ├── active.glb             # Stylish_Walk_inplace
    ├── bouncing.glb           # victory
    ├── flourish.glb           # victory
    ├── degraded.glb           # Stumble_Walk
    └── failed.glb             # Strangled_and_Fall_Forward
```

Each file: ONE rig (one skin, one skinned mesh hierarchy), ONE clip, ~600 KB to 1.5 MB depending on clip complexity. Total APK cost: ~12 MB per character × 3 = 36 MB (vs current ~56 MB merged packs). Storage contract: a character set NEVER contains clips for another character; the loader MUST refuse to load `babshaggoth/thinking.glb` while in a `purpangolin` mount.

### Loader change — map MOOD → FILE PATH

```kotlin
// New: per-mood, per-character single-move GLB path resolver
fun assetPath(character: String, mood: PurpAngolinMood): String {
  val dir = when (Avatar3DClips.assetId(character)) {
    "babshaggoth" -> "avatars/babshaggoth"
    "lyra" -> "avatars/lyra"
    else -> "avatars/purpangolin"
  }
  val stem = when (mood) {
    PurpAngolinMood.IDLE -> "idle"
    PurpAngolinMood.LISTENING -> "listening"
    PurpAngolinMood.THINKING -> "thinking"
    PurpAngolinMood.SPEAKING -> "speaking"
    PurpAngolinMood.ACTIVE -> "active"
    PurpAngolinMood.BOUNCING -> "bouncing"
    PurpAngolinMood.FLOURISH -> "flourish"
    PurpAngolinMood.DEGRADED -> "degraded"
    PurpAngolinMood.FAILED -> "failed"
  }
  return "$dir/$stem.glb"
}
```

The mood→clip-string map in `Avatar3DClips.PURPANGOLIN` etc. is no longer needed for the runtime path — it stays as documentation only (or is removed). The path resolver is the SOLE mapping at render time.

### Reactive mood-driven model swap

```kotlin
// In Avatar3DActor / Avatar3DModel
val assetPath = Avatar3DPaths.assetPath(character, mood)
val modelInstance = remember(modelLoader, assetPath) {
  runCatching { modelLoader.createModelInstance(assetFileLocation = assetPath) }
    .onFailure { Log.e(TAG, "Could not load $assetPath for mood=$mood", it) }
    .getOrNull()
}

// loadAdmission gate: must be a fully-skinned, animated rig
val admitted = modelInstance?.takeIf { it.skinCount > 0 && it.animator.animationCount > 0 }
  ?: run {
    Log.e(TAG, "Rejected $assetPath: skins=${modelInstance?.skinCount} anims=${modelInstance?.animator?.animationCount}")
    return  // fall back to 2D PurpAngolinAvatar
  }

// Drive playback: applyAnimation(0, t) — single clip, idx=0 always
LaunchedEffect(admitted) {
  val start = withFrameNanos { it }
  while (true) {
    withFrameNanos { now -> admitted.animator.applyAnimation(0, (now - start) / 1e9f) }
  }
}
```

When the mood changes, `remember(modelLoader, assetPath)` re-keys, the previous `modelInstance` is GC'd, the new single-move GLB is loaded. Per `AGENT_NOTES_TASK91` the explicit `applyAnimation(0, t)` driver replaces `playAnimation()` autoplay.

### Shrinkable shell — already in source, no change

The overlay (`PurpAngolinOverlayActor.kt`) already handles:
- pinch clamp `0.40..1.0`
- drag clamp `±screenW/1.6`
- reset square at BottomEnd (always on screen)
- double-tap hide / tap visibility chip
- long-press reduced-motion
- persist `scale`, `dX`, `dY`, `hidden`, `reduced`, `rotation_y` in `purpclaw_avatar_prefs`

The reactive part (mood swap → new GLB load → new clip play) lives inside `Avatar3DActor`/`Avatar3DModel`. The shell stays untouched.

### Hardware quarantine lift

Until AGENT_NOTES_TASK42 Path A (sceneview 4.33.0 upgrade) or Path C (filament-android direct) lands, `PURPCLAW_3D_AVATAR_DISABLED` blocks the 3D path on S25 and the operator sees an invisible Box. **This lane cannot be verified visible on S25 until quarantine is lifted.** Verification gates:

1. Lift quarantine (Path A or C).
2. Build → install → cold launch → screencap → confirm rig renders in `purpangolin/idle.glb` pose.
3. Drive mood changes from chat → screencap after each → confirm file swap in logcat (`assetPath=...`).
4. Confirm pinch shrinks and enlarges (screencap at scale 0.40 vs 1.0).
5. Confirm drag moves rig within clamped bounds.
6. logcat clean of `libgltfio-jni` SIGSEGV across all mood transitions.

---

## Files in scope

| # | File | Action |
|---|---|---|
| 1 | `app/src/main/assets/avatars/purpangolin/*.glb` | NEW — 9 single-move files extracted from `purpangolin.glb` |
| 2 | `app/src/main/assets/avatars/babshaggoth/*.glb` | NEW — 9 single-move files extracted from `babshaggoth.glb` |
| 3 | `app/src/main/assets/avatars/lyra/*.glb` | NEW — 9 single-move files extracted from `lyra.glb` |
| 4 | `app/src/main/java/com/example/ui/components/Avatar3DActor.kt` | EDIT — mood→path resolver, per-mood `remember(assetPath)` reload, quarantine lift, `applyAnimation(0, t)` driver. Keep loadAdmission gate. |
| 5 | `app/src/main/java/com/example/ui/components/PurpAngolinOverlayActor.kt` | No code change required (already shrinkable + persist). |
| 6 | `app/src/main/java/com/example/ui/components/AGENT_NOTES_TASK42_LIBGLTFIO_SIGSEGV_ROOT_CAUSE.md` | UPDATE — quarantine status flips to LIFTED when Path A or C lands. |
| 7 | `app/src/main/java/com/example/ui/components/AGENT_NOTES_TASK103_SHRINKABLE_REACTIVE_AVATAR.md` | NEW — this file. |

Total: 27 new GLB files, ~12 MB per character × 3 = ~36 MB APK addition. Old merged-pack GLBs can be REMOVED after per-mood extraction (saves ~20 MB). Net APK delta: +16 MB if kept side-by-side, -4 MB if old packs deleted post-cert.

### Why this is the right shape

- **Per-file isolation.** A SIGSEGV in one mood can never taint another mood — each file is loaded standalone, GC'd on swap, no shared animator state. The libgltfio-jni SIGSEGV on Adreno 830 becomes per-mood recoverable instead of process-killing.
- **Loader simplicity.** `assetPath(character, mood)` returns a string. `modelLoader.createModelInstance(assetFileLocation = path)` is a single call. The catalogue walk + name-matching logic disappears.
- **Certifiability.** One file = one clip = one test. The clip-by-clip certification from AGENT_NOTES_TASK63 §4.4 becomes file-by-file certification, which is what the operator has been asking for ("each set is stored separately and used right").
- **Reactive.** Mood swap → remember key changes → new GLB loads → `applyAnimation(0, t)` plays the only clip. The "abilities" mapping (IDLE→idle, SPEAKING→speaking, etc.) is a direct one-to-one path lookup, no name-resolution gymnastics.
- **Shrinkable.** Already in source via the overlay shell; this lane doesn't touch that surface.

---

## Acceptance battery (on-glass, S25, post-quarantine-lift)

1. **Cold launch renders idle** — `purpangolin/idle.glb` mounts, head-to-toes visible, no SIGSEGV, `applyAnimation(0, 0)` at t=0 = first frame of `Chair_Sit_Idle_F`.
2. **Mood swap reloads file** — drive IDLE→THINKING via chat message, screencap within 500 ms shows `Confused_Scratch` clip body pose (not the sit pose). logcat shows `assetPath=avatars/purpangolin/thinking.glb`.
3. **All 9 moods render** — IDLE / LISTENING / THINKING / SPEAKING / ACTIVE / BOUNCING / FLOURISH / DEGRADED / FAILED each loaded standalone, each non-crashing.
4. **Shrinkable** — pinch from 0.40 to 1.0 → rig grows; pinch past 1.0 clamps (shell clamp is `0.40..1.0`).
5. **Drag clamped** — drag past `screenW/1.6` clamps; reset chip remains inside screen.
6. **Persist** — `adb shell run-as com.example cat shared_prefs/purpclaw_avatar_prefs.xml` shows `scale`, `dX`, `dY`, `hidden`, `reduced`, `rotation_y` written.
7. **Hide / show** — double-tap → invisible; tap chip → returns with last state.
8. **Reduced motion** — long-press → static (still mounted, no animation advance); long-press again → resumes.
9. **Reset** — tap reset chip → returns to seat anchor at scale 1.0, rotation 0.
10. **No cross-character contamination** — switch character to babshaggoth, drive mood IDLE → logcat `assetPath=avatars/babshaggoth/idle.glb`. The lyra folder must never load.
11. **Sibling regression** — chat list, composer, voice mode, settings — none regressed.
12. **SIGSEGV clean** — full battery ×3 cycles (cold launch each), logcat shows zero `libgltfio-jni` SIGSEGV entries.

Until 12/12 PASS on glass, this lane is NOT VERIFIED.

---

## Open debts

- **Single-move GLB extraction pipeline.** The current Meshy packs are merged; we need a script (Node + `@gltf-transform/core` or Python `pygltflib`) that takes `purpangolin.glb`, parses each clip, and emits one file per clip. Without this pipeline the 27 GLBs don't exist. **This is the #1 blocker for this lane.**
- **Sceneview upgrade (Path A) or filament-android direct (Path C).** Without one of these, quarantine stays ON and the rig remains invisible on S25.
- **Clip name → file stem mapping.** The current names (`Chair_Sit_Idle_F`, `Confused_Scratch`) need to be agreed on the new stem names (`idle`, `thinking`). Suggested table is in §Target architecture; builder lane can rename.
- **APK size budget.** ~36 MB of new GLBs is non-trivial; consider `assetsPack` or runtime download if user pushback on APK size.
- **S25 battery impact.** 3D rendering at full chat-frame rate costs ~5-8% battery per hour on Snapdragon 8 Elite. Frame-rate cap (30 fps) and visible-only animation are mitigations.

Lane #103 is **OPEN**. Implementable in order: (1) write glb-splitter script, (2) extract 27 files, (3) edit `Avatar3DActor.kt` for mood→path + per-mood swap + applyAnimation driver, (4) lift quarantine via Path A or C, (5) run 12-point battery.

Lane #103 supersedes the clip-name-mapping half of Lane #91 (single-move files > name lookup inside merged pack). The shrinkable shell from #91 stays. The animation-lift + sit-anchor + soft-snap-back parts of #91 stay. Only the loader-shape changes from "merged pack with clip lookup" to "per-mood file with direct path".
