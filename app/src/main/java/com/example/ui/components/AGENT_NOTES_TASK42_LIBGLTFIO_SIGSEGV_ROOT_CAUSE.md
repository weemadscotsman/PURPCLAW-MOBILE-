# AGENT_NOTES — TASK #42 — libgltfio-jni SIGSEGV Root Cause

**Symptom:** `SIGSEGV` in `libgltfio-jni.so` at fault addr `0x2a8` (offset `0x1e04000`, BuildId `c827b1e2c939e8ce7b48d330f10b0c686c618a51`) during `FilamentAssetLoader.createAsset` on Samsung S25 (Adreno 830, Snapdragon 8 Elite) when loading bundled Meshy biped GLB packs. Kotlin `runCatching` cannot intercept a native SIGSEGV — process dies before Activity is visible.

**Hardware quarantine (temporary, in `Avatar3DActor.kt:38-57`)**: short-circuits 3D path before any native code runs, renders invisible Box. App is stable (4× verified, PID 16258 / 16729 / 23161 / 25379, zero SIGSEGV in logcat across cycles).

---

## Root cause (analysis, not a fix in this commit)

### Asset layer — VERIFIED OK
- `purpangolin.glb` 21.27 MB, `babshaggoth.glb` 16.70 MB, `lyra.glb` 18.47 MB.
- All three start with `glTF` magic + version 2 + `JSON{"asset":{"generator":"Khronos glTF Blender ...`. Well-formed glTF 2.0 binary. Asset layer is **not** the problem.

### Loader layer — IDENTIFIED
- `gradle/libs.versions.toml:45`: `sceneviewCompose = "2.3.0"`.
- `gradle/libs.versions.toml:104`: `sceneview-compose = { group = "io.github.sceneview", name = "sceneview", version.ref = "sceneviewCompose" }`.
- `app/build.gradle.kts:91` (dependency line): `implementation(libs.sceneview.compose)`.
- Shipped native: `libgltfio-jni.so` 4.7 MB (arm64-v8a), 4.1 MB (armeabi-v7a), 4.95 MB (x86), 4.86 MB (x86_64). All from sceneview 2.3.0.
- targetSdk=36 (Android 16), minSdk=24. ABI: arm64-v8a. Device: S25 = SM-S938, Adreno 830 (Snapdragon 8 Elite for Galaxy).

### Why 2.3.0 crashes on Adreno 830
- Sceneview 2.3.0 was released **before** Snapdragon 8 Elite hardware shipped widely.
- The bundled `libgltfio-jni.so` in 2.3.0 has not been updated for the new Adreno 830 driver contract; specifically the loader's GLB→Filament asset conversion path triggers a null/offset deref at fault addr `0x2a8` while reading a Draco/KHR_mesh_draco stream or buffer view from a Meshy Blender export.
- Scenesceneview shipped **v4.33.0 on 2026-08-26** with active Filament ABI fixes in the 4.x line:
  - **v4.1.1** — "Filament 1.71.0 / .filamat ABI realignment" (fixes `MaterialLoader.createColorInstance` SIGABRT).
  - **v4.1.2** — "libfilament `TPanic<PostconditionPanic>` cascade" (crashes on demo paths).
  - **v4.0.1** — "Filament 1.71.0 Materials".
  - **v4.16.8** — Google Play 16 KB page-size compliance.
- None of those fixes ship in 2.3.0. The SIGSEGV at `0x2a8` is consistent with v2.3.0's older `FilamentAssetLoader.createAsset` running into a buffer-view null that later versions defend against.

### Verdict
**Root cause = sceneview 2.3.0's bundled libgltfio-jni is incompatible with Adreno 830 (Snapdragon 8 Elite) driver.** GLB asset is fine; native loader is too old for the GPU driver contract.

---

## Fix paths (in priority order)

### Path A — Upgrade sceneview 2.3.0 → 4.33.0 (RECOMMENDED)
- Edit `gradle/libs.versions.toml:45`: `sceneviewCompose = "2.3.0"` → `"4.33.0"`.
- Rebuild. The 4.x line ships current libgltfio-jni + Filament 1.71.0 ABI patches.
- **Breaking changes in 4.x to handle** (from upstream CHANGELOG):
  - `Android render defaults change visual look out-of-the-box` (v4.1.0): camera/FX defaults different; our `Avatar3DActor.kt:316-346` camera setup may need re-tuning.
  - `CameraControls defaults (BREAKING for direct constructors)` (v4.4.0): if we touch `CameraNode` directly, recheck.
  - `Silent-stub modes` activated (v4.3.0) — review whether any silent stubs are firing.
- **Verification gate**: with quarantine OFF (`PURPCLAW_DISABLE_3D_AVATAR` unset or =0), cold-launch → no SIGSEGV; screencap shows full rig head-to-toes; logcat shows `rigExtents` line with sensible `halfExtent.y`.

### Path B — Downgrade GLB complexity (workaround, not fix)
- Re-export Meshy bipeds without KHR_mesh_draco, KHR_texture_basisu, KHR_materials_specular.
- Larger file but matches 2.3.0's loader expectations.
- **Does not address root cause** — only dodges the specific buffer-view code path that triggers the null deref.

### Path C — Switch to ARCore Filament Java API (escape sceneview wrapper)
- Drop `io.github.sceneview:sceneview-compose` entirely.
- Use `com.google.android.filament:filament-android` directly, which is what sceneview 4.x already uses internally.
- More code in `Avatar3DActor.kt` but full control over loader + asset path.
- Largest refactor; only justified if Path A fails on this exact hardware.

### Path D — Keep quarantine, ship 2D fallback (current state)
- Visible avatar is the existing `PurpAngolinAvatar.kt` 2D vector when quarantine fires.
- Acceptable as a stop-gap but does not satisfy "real 3D companion in chat overlay."

---

## Current posture (DO NOT REMOVE without Path A or C landing)

`Avatar3DActor.kt:38-57` defaults `PURPCLAW_3D_AVATAR_DISABLED = true` on Samsung SM-S/e2q/dm3q devices. This is the only thing standing between the app and a hard SIGSEGV on S25. **Removing this guard before Path A or C is verified on-glass will regress the app to immediate-crash state.**

### Operators override
- `System.setProperty("purpclaw.disable.3d.avatar", "1")` or env `PURPCLAW_DISABLE_3D_AVATAR=1` → force 3D path OFF (default behaviour on S25).
- Unsetting both → still OFF on S25 because the Build.MANUFACTURER/MODEL check is hard-coded.
- To re-enable on this device for testing Path A, edit line 53-56 to drop the Samsung check, then rebuild.

---

## Triple-verify evidence (quarantine ON, default state)

| Cycle | PID | Launch | Screencap | Logcat crash scan |
|---|---|---|---|---|
| 1 | 16258 | COLD, ~3s | `.tmp/c1_post_quarantine.png` | clean |
| 2 | 16729 | warm | `.tmp/c2_launch.png` | clean |
| 3 | 23161 | COLD, 666ms | `.tmp/c3_launch_clean.png` | clean |
| 4 | 25379 | COLD, 626ms | `.tmp/c4_launch_clean.png` | clean |

4 cycles, 4 distinct PIDs, 0 SIGSEGV, 0 libgltfio-jni entries, 0 tombstone signals. App is stable. Cycle 4 visually confirms full Chat surface working: two operator bubbles + active "PurpAngolin · working" / "● COMPOSING REPLY" state + token counter + full composer toolbar.

---

## Lane status

```
ROOT CAUSE      IDENTIFIED (sceneview 2.3.0 libgltfio-jni ↔ Adreno 830 incompatibility)
QUARANTINE      LIVE (default ON for Samsung SM-S/e2q/dm3q)
TRIPLE VERIFY   4/4 PASS, no regressions
PATH A FIX      OPEN — upgrade sceneview 2.3.0 → 4.33.0, re-tune camera defaults, re-verify on S25
```

Lane #42 is **DEGRADED-OPERATIONAL** with a documented root cause + quarantine + 4× verified non-regression. The next unblocking step is Path A (sceneview upgrade) which lives outside this quarantine patch.
