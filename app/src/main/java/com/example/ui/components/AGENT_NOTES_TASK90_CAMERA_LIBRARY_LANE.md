---
name: AGENT_NOTES_TASK90_CAMERA_LIBRARY_LANE
description: Camera + media library lane contract — in-app capture (photo/video/audio), generic file attach, dual storage (app-private + MediaStore mirror), inline chat render, multimodal model attach, library surface with persistence. Build/verify scope for PurpClaw mobile on S25.
type: project
---

# Mobile Camera + Media Library — Lane #90 (2026-08-28 02:46Z)

## Why this lane exists

Operator asked (2026-08-28 02:36Z): **direct camera access in the app, photos show up in chat, persist in the in-app library for images/video/etc.** Subsequent decisions ratified: photo + video + audio + generic files on day one; dual storage (app-private primary, MediaStore mirror); chat shows inline thumbnail AND sends to model as multimodal context.

Probe findings (see `.tmp/camera_library_probe_report.md`):

- `CAMERA` and `RECORD_AUDIO` runtime permissions are already granted on the S25 install.
- `FOREGROUND_SERVICE_CAMERA` and `FOREGROUND_SERVICE_MICROPHONE` declared at install.
- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_VISUAL_USER_SELECTED` are DENIED — gallery bulk-read must NOT be assumed.
- No `androidx.core.content.FileProvider` declared — gap that must be closed for URI sharing.
- No `Pictures/PurpClaw`, `Movies/PurpClaw`, `Audio/PurpClaw` MediaStore folders.
- No `media/`, `chat_media/`, `thumbnails/` folders under app-private files.
- No composer / camera button / attach affordance / library surface in OS accessibility tree.

## Scope (in)

1. In-app camera preview, capture, retake/accept flow for **photo** (still JPEG).
2. In-app camera preview, capture, retake/accept flow for **video** (MP4, ≤60 s default).
3. In-app audio capture (voice memo, M4A, ≤5 min default) using existing `RECORD_AUDIO` permission.
4. Generic file attach via `Intent.ACTION_OPEN_DOCUMENT` (SAF) — covers PDFs, docs, anything the OS picker exposes.
5. Dual storage: app-private `filesDir/media/{photo,video,audio,file}/<uuid>.<ext>` + MediaStore mirror (`Pictures/PurpClaw`, `Movies/PurpClaw`, `Audio/PurpClaw`).
6. Library surface: persistent tab in bottom nav (or off-canvas drawer) showing all captured/attached assets, sorted newest-first, with thumbnail + timestamp + source label.
7. Chat composer attach affordance: chip/menu in composer row with four options (Camera, Video, Voice, File).
8. Inline chat render: captured asset shown as thumbnail (image) or play button (video/audio) inside the user turn bubble.
9. Multimodal model attach: when the user sends a turn containing a captured asset, vision-capable routes receive the asset URI as part of the turn input. Audio routes receive audio URI as input. Text-only routes receive a textual description placeholder (`[media:photo:<uri>]`) so the receipt shows what was sent.
10. Persisted library: assets survive `force-stop`, `reboot`, and APK reinstall (under app-private) — MediaStore mirror survives uninstall under PurpClaw subfolder if MediaStore retention is enabled (it is on S25 by default).
11. Foreground service contract for SDK 34+: video + audio recording must run inside a typed FGS (`cameraType` for video, `microphoneType` for audio).

## Out of scope (deferred)

- Cloud sync of media library (local-first product law holds).
- Face/people tagging, EXIF auto-rotation, image editing (crop/filters).
- GIF recording, slow-mo, portrait mode (camera2 advanced features).
- Multiple simultaneous captures (one at a time).
- Library sharing/export beyond the existing share-sheet contract.

## Hard rules

- **NO `WRITE_EXTERNAL_STORAGE`** on SDK 29+ — use scoped storage / MediaStore.
- **NO `READ_MEDIA_IMAGES` blanket grant** — use `PickVisualMedia` / `READ_MEDIA_VISUAL_USER_SELECTED` per-item.
- **NO `ContentResolver` queries without scope** — every query names the collection.
- **FileProvider is mandatory** before any `Intent.ACTION_VIEW` / share-sheet / model-routing of the captured URI. No raw `file://` URIs cross process.
- **Foreground service type MUST match** on SDK 34+: `FOREGROUND_SERVICE_TYPE_CAMERA` for video, `FOREGROUND_SERVICE_TYPE_MICROPHONE` for audio. Manifest must include the matching `<service>` declaration with `foregroundServiceType` and the corresponding runtime FGS-type call.
- **No raw byte arrays in the turn payload** — always URIs. The chat turn stores `MediaRef(uri, mimeType, byteSize, sha256, source, capturedAt)`.
- **Thumbnail at capture time** — 256 px longest edge JPEG, stored alongside the original. No on-the-fly resizing during list render.
- **Library entries must be canonical** — `id = UUID`, `createdAt = epoch ms`, `source ∈ {camera, voice, file, gallery}`. No derived IDs.
- **No silent fallback to gallery** — if the user tapped Camera and denied permission once, the second tap must show a permission rationale, not silently open the gallery.
- **Library tab order** — newest first, but never re-fetch on tab switch. Use `StateFlow` cache.

## Files in scope (to read, not yet edit in this session)

### Must read (builder lane will read these first)

- `app/src/main/AndroidManifest.xml` — declare FileProvider, FGS services with `foregroundServiceType`, `<queries>` block for camera intent.
- `app/src/main/java/com/example/ui/screens/CommandScreen.kt` — host for the composer attach chip, inline thumbnail render.
- `app/src/main/java/com/example/ui/MainViewModel.kt` — turn dispatch path (read-only; this lane does NOT modify it, but the multimodal attach field must be added).
- `app/src/main/java/com/example/core/runtime/ProviderRouter.kt` — extend `TurnInput` with `attachments: List<MediaRef>` (read-only contract; builder lane implements).
- `app/src/main/java/com/example/data/` — Room DB definitions for the new `media_library` table (read-only to discover current DAO patterns).
- `app/src/main/java/com/example/ui/components/AGENT_NOTES_TASK63_ANIMATION_AND_SEAT_ANCHOR.md` — reference style for spec structure.

### To write

- `app/src/main/res/xml/file_paths.xml` — FileProvider paths config (media subdirs + cache).
- `app/src/main/java/com/example/media/CameraCaptureActivity.kt` (or Compose component) — in-app camera preview + shutter + accept/retake.
- `app/src/main/java/com/example/media/VideoCaptureActivity.kt` — video recorder.
- `app/src/main/java/com/example/media/AudioCaptureActivity.kt` — voice memo recorder.
- `app/src/main/java/com/example/media/FilePickerLauncher.kt` — `ACTION_OPEN_DOCUMENT` helper.
- `app/src/main/java/com/example/media/MediaLibraryStore.kt` — Room DAO + repository for the `media_library` table.
- `app/src/main/java/com/example/media/MediaStoreMirror.kt` — `MediaStore.Images/Media.Video/Media.Audio` insert path for the mirror.
- `app/src/main/java/com/example/media/MediaThumbnailer.kt` — 256 px JPEG thumbnail generator.
- `app/src/main/java/com/example/media/MediaRef.kt` — canonical data class.
- `app/src/main/java/com/example/media/capture/CameraFgsService.kt` + `AudioCaptureFgsService.kt` — typed FGS for video + audio recording.
- `app/src/main/java/com/example/ui/components/MediaLibrarySurface.kt` — library list/grid composable.
- `app/src/main/java/com/example/ui/components/MediaAttachmentChip.kt` — composer chip + capture affordance.
- `app/src/main/java/com/example/ui/components/InlineMediaThumbnail.kt` — render inside chat bubble.
- `app/src/main/java/com/example/data/entity/MediaAssetEntity.kt` + `MediaAssetDao.kt` — Room schema.

## Data contracts

### `MediaRef` (canonical, immutable)

```kotlin
data class MediaRef(
  val id: String,             // UUID
  val uri: String,            // content:// URI from FileProvider
  val mimeType: String,       // image/jpeg | video/mp4 | audio/mp4 | application/pdf | ...
  val byteSize: Long,
  val sha256: String,         // hex, lowercase
  val source: MediaSource,    // CAMERA | VIDEO_CAMERA | VOICE_MEMO | FILE_PICK | GALLERY_PICK
  val capturedAt: Long,       // epoch ms
  val widthPx: Int?,          // null for non-visual
  val heightPx: Int?,         // null for non-visual
  val durationMs: Long?,      // null for non-temporal
  val thumbnailUri: String?,  // content:// of 256 px JPEG
)
enum class MediaSource { CAMERA, VIDEO_CAMERA, VOICE_MEMO, FILE_PICK, GALLERY_PICK }
```

### `MediaAssetEntity` (Room)

```kotlin
@Entity(tableName = "media_library")
data class MediaAssetEntity(
  @PrimaryKey val id: String,
  val uri: String,
  val mimeType: String,
  val byteSize: Long,
  val sha256: String,
  val source: String,         // MediaSource.name
  val capturedAt: Long,
  val widthPx: Int?,
  val heightPx: Int?,
  val durationMs: Long?,
  val thumbnailUri: String?,
  val turnId: String?,        // FK to chat turn when attached; null if un-attached
  val mirrorMediaStoreId: Long?  // MediaStore _ID for the mirror copy
)
```

### `TurnInput` extension (ProviderRouter contract)

```kotlin
data class TurnInput(
  val text: String,
  val reasoningHint: String? = null,
  val attachments: List<MediaRef> = emptyList(),  // NEW
  val requestedRoute: String? = null,
  ...
)
```

Routes that support vision must accept `attachments.filter { it.mimeType.startsWith("image/") }`. Routes that support audio input must accept audio attachments. Text-only routes get the placeholder substitution.

### `MediaLibraryStore` contract

```kotlin
interface MediaLibraryStore {
  fun observeAll(): Flow<List<MediaAssetEntity>>
  fun observeForTurn(turnId: String): Flow<List<MediaAssetEntity>>
  suspend fun insert(asset: MediaAssetEntity): String  // returns id
  suspend fun delete(id: String): Boolean
  suspend fun sha256(uri: String): String
}
```

## Capture flow (photo as canonical example)

1. User taps **Camera** chip in composer → `MediaAttachmentChip` fires `CaptureRequest(CAMERA)`.
2. `CameraCaptureActivity` launches inside a typed FGS (`FOREGROUND_SERVICE_TYPE_CAMERA`) per SDK 34+ rules.
3. CameraX preview composable shows live feed with shutter, flip, flash controls.
4. Shutter captures to `filesDir/media/photo/<uuid>.jpg` (full-res JPEG).
5. `MediaThumbnailer.generate(uri, 256)` produces `filesDir/media/photo/<uuid>_thumb.jpg`.
6. `MediaStoreMirror.insertPhoto(displayName=<uuid>.jpg, bytes=full)` writes to `Pictures/PurpClaw/<uuid>.jpg` and stores `_ID`.
7. `MediaLibraryStore.insert(MediaAssetEntity(...))` writes the canonical row.
8. Activity returns `MediaRef(uri, mimeType=image/jpeg, ...)` to caller via `ActivityResultContracts.TakePicture` pattern.
9. Composer receives `MediaRef` and stores it in pending-attachment state (visible as a chip above the input).
10. User types text or hits send → turn dispatched with `TurnInput(text=..., attachments=[ref])`.
11. Vision-capable route reads the URI via `ContentResolver.openInputStream(uri)` and feeds it to the multimodal model.

## UI contracts

### Composer attach chip
- 36 dp circular icon to the LEFT of the text input.
- Tap → bottom-sheet with four tiles: Camera, Video, Voice, File.
- Each tile shows icon + label + permission state (granted / needs-permission).

### Inline thumbnail
- 96 dp × 96 dp rounded thumbnail (12 dp radius) inside user bubble, top-left.
- For image: `Image(painter = rememberAsyncImagePainter(ref.uri), ...)`.
- For video: thumbnail with small play-triangle overlay + duration badge.
- For audio: waveform icon + duration badge.
- For file: file-type icon + filename + byte size.
- Tap → full-screen viewer (`MediaViewerActivity`) with pinch-zoom, share-sheet, delete.

### Library surface
- Persistent bottom-nav tab (insert before Settings).
- Grid 3-col, 4:3 cells, 4 dp gutter.
- Sorted newest first.
- Pull-to-refresh.
- Tap → full-screen viewer.
- Long-press → multi-select with Delete / Share / Move-to-Project.

## Permission rationale strings (required for SDK 30+)

- `CAMERA`: "Take photos and videos inside PurpClaw chat and save them to your library."
- `RECORD_AUDIO`: "Record voice memos and video audio inside PurpClaw."
- `READ_MEDIA_VISUAL_USER_SELECTED`: "Pick photos and videos from your gallery to attach to chat."

## Acceptance battery (on-glass, S25)

1. **Capture photo** — tap Camera chip → in-app preview appears (not the system camera) → shutter → retake/accept → chip appears above composer.
2. **Send** — type "describe this" → tap send → message in chat shows the thumbnail inline + the assistant turn's telemetry shows the asset attached (`attachments: 1, image/jpeg`).
3. **Library** — tap Library tab → captured photo appears at top of grid.
4. **Relaunch** — `adb shell am force-stop com.aistudio.purpclaw.osv7` → relaunch → Library still shows the asset.
5. **MediaStore mirror** — `adb shell ls /sdcard/Pictures/PurpClaw/` shows `<uuid>.jpg` AND `adb shell content query --uri content://media/external/images/media --projection _id,_display_name --where "_display_name='<uuid>.jpg'"` returns the row.
6. **FileProvider grant** — tap thumbnail → full-screen viewer → tap share → share-sheet opens with `content://` URI (NOT `file://`).
7. **Video capture** — same flow with ≤60 s clip → video thumbnail + play button inline in chat → tap → inline player.
8. **Audio memo** — same flow with ≤5 min voice memo → waveform + duration inline in chat.
9. **File attach** — tap File → SAF picker → pick a PDF → chip shows PDF + size → send → assistant sees `[media:file:<uri>]`.
10. **Permission denial recovery** — revoke CAMERA via `adb shell pm revoke com.aistudio.purpclaw.osv7 android.permission.CAMERA` → tap Camera chip → rationale appears → tap "Allow" → settings opens → grant → return → camera launches.
11. **No SIGSEGV** — full capture flow does not crash `libcamera2-jni` or the FGS.
12. **Foreground service type match** — `adb shell dumpsys activity services com.aistudio.purpclaw.osv7` shows the FGS running with the correct `foregroundServiceType`.

Until 12/12 PASS on glass, this lane is **NOT VERIFIED**.

## Risks / known unknowns

- `Min SDK` not yet confirmed for this app — if it predates SDK 29, scoped storage migration is required first.
- Room version + migration policy not yet audited — adding a new entity must respect the existing migration path or trigger a destructive fallback (the plan's "DB migration issue from earlier audit" hint suggests this is a real risk).
- CameraX vs Camera2 — the spec assumes CameraX (`androidx.camera`). If the existing app uses raw Camera2, the FGS contract is the same but the preview component differs.
- MediaStore on SDK 36 — `IS_PENDING` flag is required for staged writes; spec assumes builder lane uses it correctly.
- Multimodal model support — none of the bundled providers have been verified to accept image URIs from `ContentResolver.openInputStream`. The ProviderRouter extension must include a per-route capability bit (`acceptsImageAttachments` / `acceptsAudioAttachments`).

## Definition of done

- All 12 acceptance points PASS on S25 (RZCY9172MDP).
- FileProvider authority declared in manifest with `<paths>` covering `media/`.
- FGS services declared with correct `foregroundServiceType`.
- MediaStore mirror verified on `Pictures/PurpClaw`, `Movies/PurpClaw`, `Audio/PurpClaw`.
- Library tab visible in bottom nav, persists across `force-stop` + relaunch.
- Inline chat render works for all four media types.
- Multimodal attach passes through ProviderRouter to vision-capable route.
- No source files outside the scope list were modified.
- Build is clean (`BUILD SUCCESSFUL`), no lint regressions, no new warnings.
- Triple-verified by an independent verifier agent with on-glass artifacts.
