# WorkSession Persistence + Background Execution + Notifications
## PURPCLAW Mobile — 2026-08-31

---

## What We Are Building

The operator speaks a job: *"Build me X, research Y, make this document."*
PurpClaw creates a persistent WorkSession. The operator leaves the app. The job
keeps running. When done, the operator gets a notification with real actions —
not "your task is complete" with no way to see what was done.

```
🎙️ Voice request → persistent WorkSession
  → foreground service (Android survives app switch)
  → provider/tool continuation
  → save artifacts to phone workspace
  → verify
  → 🔔 "Build complete" notification
      [OPEN RESULT] [OPEN CODE] [RECEIPT]
  → tap lands directly on the artifact / code / receipt
```

---

## Existing Architecture (what we have)

| Component | Exists | Location |
|-----------|--------|----------|
| Turn tracking (`activeTurnJob`, `activeTurnToken`, `activeTurnId`) | ✅ | MainViewModel:310-312 |
| `cancelActiveTurn()` | ✅ | MainViewModel:329-344 |
| `BoundedToolContinuation` (6 steps, cancellable) | ✅ | BoundedToolContinuation.kt |
| Turn completion → persisted to DB | ✅ | MainViewModel:2260 |
| Notification channel (FOREGROUND) | ✅ | ToolRuntimeEngine:77-91 |
| `android.notification.send` tool | ✅ | ToolRuntimeEngine:719-731 |
| `NotificationCompat` imports | ✅ | ToolRuntimeEngine:17 |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE permissions | ✅ | AndroidManifest:19-21 |
| Artifact file write → phone workspace | ✅ | ToolRuntimeEngine:698-716 |
| `browserEmbedRequest` StateFlow (opens Dual View) | ✅ | ToolRuntimeEngine:707 |
| Work session persistence (SessionMeshCoordinator) | Partial | SessionMeshCoordinator.kt |

---

## What Is Missing (the gaps)

### Gap 1 — Activity background → turn cancelled
When the app goes to background, `viewModelScope` cancels. `activeTurnJob` dies.
The job is not "paused" — it is killed. The operator returns to find the work
未完成 with no result and no explanation.

### Gap 2 — No foreground service owns the work
The app has `FOREGROUND_SERVICE` permission declared but no registered service.
Android can kill a background app at any time. A foreground service with a live
notification is the only reliable way to keep a long-running agent job alive.

### Gap 3 — Completion → meaningless notification
The existing `android.notification.send` fires a raw text notification with no
actions. Tapping it opens the app but doesn't land on the artifact or result.

### Gap 4 — No WorkSession state persistence
If the app is killed (system RAM pressure, user force-stop), the active job's
state is gone. The operator has no way to discover what was running or resume it.

### Gap 5 — Artifacts not linked to notifications
The tool chain produces files (`android.file.write`, `android.artifact.preview`).
The completion notification has no idea what was produced and can't offer
[OPEN RESULT] / [OPEN CODE] without that context.

---

## Implementation Plan

### Phase 1 — WorkSessionService (foreground service)

**New file:** `core/runtime/WorkSessionService.kt`

```
AndroidManifest entries needed:
- <service android:name=".core.runtime.WorkSessionService"
           android:foregroundServiceType="dataSync"
           android:exported="false" />
- Uses permission FOREGROUND_SERVICE_DATA_SYNC
```

**Notification channels (add to ToolRuntimeEngine or WorkSessionService):**
- Channel `purpclaw_worksession`: IMPORTANCE_LOW, no sound — ongoing work indicator
- Channel `purpclaw_completion`: IMPORTANCE_HIGH, sound+vibration — completion + actions

**Service behaviour:**
- `onCreate`: create notification channels, register instance
- `onStartCommand(intent, flags, startId)`:
  - Extract `workSessionId`, `objective`, `action` from intent
  - `action = "start"`: start foreground with ongoing notification
    - Title: "PurpClaw working…"
    - Text: first 60 chars of objective
    - Ongoing (no dismiss)
  - `action = "complete"`: fire completion notification then stop
  - `action = "blocked"`: fire blocked notification then stop
  - `action = "cancel"`: cancel notification, stop
  - Return `START_STICKY` (restart if system kills)
- `onDestroy`: clear notification, clear instance reference

**Completion notification actions:**
```
Title: "✅ ${objective.take(50)}"
Body: short result summary (from completionResult)

Action: "OPEN RESULT"
  → Intent: MAIN activity with extras
      action = ACTION_VIEW
      deepLink = "purpclaw://artifact/{filename}"
  → PendingIntent.FLAG_UPDATE_CURRENT

Action: "OPEN CODE"
  → Intent: MAIN activity
      deepLink = "purpclaw://workspace/{path}"
  → PendingIntent.FLAG_UPDATE_CURRENT

Action: "RECEIPT"
  → Intent: MAIN activity
      deepLink = "purpclaw://receipt/{workSessionId}"
```

---

### Phase 2 — WorkSession state model

**New file:** `core/model/WorkSession.kt`

```kotlin
data class WorkSession(
  val sessionId: String,          // "ws_${timestamp}_${uuid6}"
  val turnId: String,              // active turn this session wraps
  val objective: String,            // original operator request
  val state: WorkSessionState,
  val createdAtMs: Long,
  val completedAtMs: Long? = null,
  val artifacts: List<ArtifactRef> = emptyList(),
  val finalContent: String? = null,
  val errorMessage: String? = null,
  val stepsCompleted: Int = 0,
  val lastStatusMessage: String = ""
)

enum class WorkSessionState {
  ACTIVE,     // running, foreground notification active
  COMPLETED,  // finished successfully
  FAILED,     // finished with error
  CANCELLED,  // operator cancelled
}

data class ArtifactRef(
  val filename: String,
  val path: String,          // absolute phone path
  val mimeType: String,
  val sizeBytes: Long,
  val url: String            // file:///... URI for deep-link
)
```

---

### Phase 3 — MainViewModel integration

**Changes to `processTurn()` (around line 310):**

1. When `currentMode == WORK` and turn has tools → start WorkSessionService:
```kotlin
val workSessionId = "ws_${System.currentTimeMillis().toString(36)}_${UUID.randomUUID().toString().take(6)}"
val workSession = WorkSession(
  sessionId = workSessionId,
  turnId = liveTurnId,
  objective = prompt,
  state = WorkSessionState.ACTIVE,
  createdAtMs = System.currentTimeMillis()
)
saveWorkSession(workSession)  // Room or SharedPrefs persistence
WorkSessionService.start(
  context = context,
  workSessionId = workSessionId,
  objective = prompt,
  action = "start"
)
```

2. Track artifacts produced during tool loop (around line 2015-2018):
```kotlin
// After dispatch.record?.let { toolCallsList::add }
// If this tool wrote an artifact, record it
val artifactRef = extractArtifactRef(dispatch)  // inspects filename/content from record
if (artifactRef != null) {
  appendArtifactToWorkSession(workSessionId, artifactRef)
  // Also update the live foreground notification text
  WorkSessionService.updateProgress(
    context, workSessionId,
    "built ${artifacts.size} artifact(s)…"
  )
}
```

3. On turn completion (around line 2283-2289):
```kotlin
// Before clearing activeTurnId:
val finalContent = providerResult.content.take(200)
WorkSessionService.complete(
  context = context,
  workSessionId = workSessionId,
  objective = prompt,
  resultSummary = finalContent,
  artifacts = getWorkSession(workSessionId).artifacts
)
updateWorkSessionState(workSessionId, WorkSessionState.COMPLETED)
```

4. On turn failure/cancellation:
```kotlin
WorkSessionService.blocked(
  context = context,
  workSessionId = workSessionId,
  objective = prompt,
  errorMessage = errorMessage ?: "No response received"
)
updateWorkSessionState(workSessionId, WorkSessionState.FAILED)
```

5. `cancelActiveTurn()` (line 329): also call `WorkSessionService.cancel(context, workSessionId)`

---

### Phase 4 — ArtifactRef extraction

Add to `ToolRuntimeEngine` or as a utility:

```kotlin
fun extractArtifactRef(dispatch: ToolExecutionResult): ArtifactRef? {
  val record = dispatch.record ?: return null
  // android.file.write produces a filename in output
  if (record.toolName == "android.file.write") {
    val filename = runCatching {
      JSONObject(record.output).optString("filename")
    }.getOrNull() ?: return null
    val workspace = File(context.filesDir, "workspace")
    val file = File(workspace, filename)
    if (file.exists()) {
      return ArtifactRef(
        filename = filename,
        path = file.absolutePath,
        mimeType = guessMimeType(filename),
        sizeBytes = file.length(),
        url = file.toURI().toString()
      )
    }
  }
  return null
}

fun guessMimeType(filename: String): String = when(filename.extension.lowercase()) {
  "html", "htm" -> "text/html"
  "css" -> "text/css"
  "js" -> "application/javascript"
  "json" -> "application/json"
  "png" -> "image/png"
  "jpg", "jpeg" -> "image/jpeg"
  "mp4" -> "video/mp4"
  "pdf" -> "application/pdf"
  "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  else -> "application/octet-stream"
}
```

---

### Phase 5 — Deep-link handling in MainActivity

**New intent filter in AndroidManifest:**
```xml
<intent-filter>
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:scheme="purpclaw" android:host="artifact" />
</intent-filter>
<intent-filter>
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:scheme="purpclaw" android:host="workspace" />
</intent-filter>
<intent-filter>
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:scheme="purpclaw" android:host="receipt" />
</intent-filter>
```

**MainActivity.onCreate/onNewIntent handling:**
```kotlin
val deepLink = intent.dataString ?: return
when {
  deepLink.startsWith("purpclaw://artifact/") -> {
    val filename = deepLink.removePrefix("purpclaw://artifact/")
    openArtifactPreview(filename)
  }
  deepLink.startsWith("purpclaw://workspace/") -> {
    val path = URLDecoder.decode(deepLink.removePrefix("purpclaw://workspace/"), "UTF-8")
    openWorkspaceBrowser(path)
  }
  deepLink.startsWith("purpclaw://receipt/") -> {
    val sessionId = deepLink.removePrefix("purpclaw://receipt/")
    openReceipt(sessionId)
  }
}
```

---

### Phase 6 — WorkSession persistence (survive app kill)

Use Room or SharedPreferences to persist `WorkSession` state:
- Save on start, update on each artifact produced, update on completion
- On app cold start: check for any `ACTIVE` WorkSession → offer to resume or discard
- Resume path: re-attach WorkSessionService with same sessionId, restore UI state

---

## Notification Behaviour Examples

**During work:**
```
PurpClaw working…
"Build me a fake Netflix website…"  [animated ongoing indicator]
```

**Completion:**
```
✅ Fake Netflix website complete
Tap to open · 2 files · 10.7 KB
[OPEN RESULT]  [OPEN CODE]  [RECEIPT]
```

**Blocked/failed:**
```
⚠️ Build failed — no routes available
[RETRY]  [VIEW LOG]
```

**Progress update (periodic):**
```
PurpClaw working…
"Build me a fake…" — wrote 2 files, running verification…
```

---

## File List

| File | Action |
|------|--------|
| `core/runtime/WorkSessionService.kt` | CREATE |
| `core/model/WorkSession.kt` | CREATE |
| `core/runtime/ArtifactRefExtractor.kt` | CREATE (or merge into ToolRuntimeEngine) |
| `AndroidManifest.xml` | EDIT — add service + intent filters + FOREGROUND_SERVICE_DATA_SYNC permission |
| `MainViewModel.kt` | EDIT — start/update/complete WorkSession around turn lifecycle |
| `ToolRuntimeEngine.kt` | EDIT — add completion notification channel + update artifact tracking |
| `MainActivity.kt` | EDIT — deep-link intent handling |

---

## Acceptance Criteria

1. Submit WORK request → foreground notification appears immediately
2. Switch to another app → work keeps running, notification stays
3. Work completes → notification with [OPEN RESULT] / [OPEN CODE] / [RECEIPT]
4. Tap [OPEN RESULT] → lands directly on the artifact in Dual View
5. App killed and restarted → active WorkSession offered for resume
6. Periodic progress updates on the foreground notification (every ~30s)
7. Cancel from notification → cancels active turn + stops service
8. Notification never fires for CHAT mode (only WORK with tools)

---

## Constraints

- Do NOT use WorkManager for the main execution — WorkManager is for
  deferrable/retryable jobs. Long-running agent turns need a foreground service.
- Do NOT create a second background architecture. The service IS the existing
  runtime, just with Android lifecycle awareness.
- Do NOT fire completion notifications for CHAT-only turns.
- Do NOT fire notifications while `_isGenerating.value == false`.
