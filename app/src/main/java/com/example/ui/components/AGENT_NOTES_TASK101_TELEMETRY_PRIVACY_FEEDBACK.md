# AGENT_NOTES_TASK101 — Telemetry / Privacy & Diagnostics / Feedback

Lane #101. Spec only. No code changes yet. Owner: Eddie decides when build starts.

## 1. Bug surface (what's missing)

- No single Settings entry for diagnostics or privacy. Counters (if any) live scattered across `ProviderRouter`, `VoiceModeController`, `CapabilitySubsystem`; operator has no honest read.
- No way for an operator to export a redacted bundle. Today an Eddie-loop probe requires hitting each subsystem by hand.
- No feedback channel. Bug reports leave as chat turns mixed with conversation; receipts get lost.
- No privacy controls. Every call leaves the device; toggles do not exist.
- `AuditProofScreen` covers *capability truth* (LIVE/DEGRADED) — that is a different axis. This lane does NOT duplicate it. It owns *behavioural counters over time* + *user-facing privacy contract*.

## 2. Settings hierarchy — where the new entry slots

- `SETTINGS / AI & MODELS` (existing `AiModelsSettingsScreen`) gains one new section in the tab strip: `DIAGNOSTICS`.
  - Insert between `LIBRARY` and `SPEND` (counter-intuitive but intentional: privacy gates every other tab).
  - `librarian law` (2026-08-26): keep section insertion order stable to not reshuffle the strip — append at index 1, not the tail.
- New screen route: `com.example.ui.screens.DiagnosticsScreen` (companion file, pattern parity with `AuditProofScreen`).
- Route surface: an `onOpenDiagnostics` callback from `AiModelsSettingsScreen` into a top-level entry **also** reachable from the home drawer header (locked icon, top-right). Two entry points, one screen.
- Tab labels stay uppercase monospace, `SectionCard` styling reused (purp pattern), testTag prefix `diagnostics_*`.

## 3. Data sources (counters) and increment triggers

Counters live in a new `core/runtime/DiagnosticsCounters.kt` (singleton, `MutableStateFlow<DiagnosticsSnapshot>`). All increments are additive and idempotent per turn; persisted to `SharedPreferences` (`purp_diag_counters`, JSON-encoded) so reset survives restart.

| Counter key | Increments when | Type |
|---|---|---|
| `turns_sent` | A user turn leaves `ProviderRouter.sendTurn` (post-slash intent gate; CHAT only NO, EXECUTE/WORK YES) | long |
| `replies_received` | First token of assistant reply arrives (SSE stream open OR non-stream 200 body) | long |
| `model_switches` | `ProviderRouter.setSelectedModel` flips resolved model OR AUTO elects new winner mid-session | long |
| `failovers` | `ProviderRouter` failover branch executes (NOT a soft retry within same provider) | long |
| `lease_arms` | `ToolRuntimeEngine` arms a `RUN_TOOL` lease (WORK/EXECUTE only) | long |
| `api_errors_4xx` | Final HTTP status is 4xx after retries exhausted, class=4 | long |
| `api_errors_5xx` | Final HTTP status is 5xx, class=5 | long |
| `api_errors_network` | IOException/Timeout/SSL failure with no response | long |
| `crashes_caught` | `Thread.setDefaultUncaughtExceptionHandler` reaped (only when Crash toggle ON, see §4) | long |
| `tts_renders` | `kokoro_worker` returns a wav chunk | long |
| `stt_captures` | `VoiceModeController` finalises a transcript | long |

Each counter has `first_at` and `last_at` unix ms.

## 4. Toggle defaults + reset/revoke contract

Two `Switch` toggles, default **OFF** both. Persisted in `SharedPreferences` (`purp_diag_prefs`), read on cold start.

- `share_anonymous_usage` (default OFF)
  - OFF: counters increment locally. NO outbound telemetry call exists in this build (no remote endpoint wired). OFF is the fail-closed default.
  - ON: counters still increment; future build may add an opt-in upload endpoint behind the same flag. OFF path must still be correct today.
- `crash_reports` (default OFF)
  - OFF: `Thread.setDefaultUncaughtExceptionHandler` is a NO-OP pass-through; Android default handler runs unchanged.
  - ON: reaper writes a redacted crash line (stack + class name + timestamp only) to `filesDir/diag/crash_<unix>.txt`, then defers to the Android default so the OS still kills the process.

Reset: a "Reset counters" button (red outline, confirm dialog) zeros counters and deletes any diag files under `filesDir/diag/` and `/sdcard/Download/purp_diag_*.json` (the latter via `MediaStore` delete on Android 10+, scoped storage on 11+). Does NOT touch the toggles.

Revoke: toggling OFF while ON must immediately drop in-flight writes — `DiagnosticsCounters.flush()` returns and the handler stack pops within 50 ms.

## 5. Redaction rules — explicit allowed / denied lists

The diagnostic bundle is a single JSON object. **Allow list** (permitted fields):

- counters (§3) — all keys + `first_at` / `last_at`
- `app_version` (semver string)
- `app_build` (long, from `BuildConfig`)
- `android_sdk` (int)
- `device_bucket` (hashed: SHA-256 of `Build.MANUFACTURER + Build.MODEL`, first 8 hex chars only)
- `region_bucket` (ISO-3166-1 alpha-2 from `Locale.getDefault().country`, e.g. `GB`, `US`)
- `routing_mode` (AUTO | MANUAL — coarse, no model id)
- `pool_summary` (counts only: free_pool=N, direct_pool=M; no model ids, no provider names beyond coarse enum)
- `last_50_turns_meta` (an array of `{at, mode, status_class, latency_ms}` — **no prompt, no reply, no model id**)
- `error_class_counts` (top-10 error classes by frequency — message body truncated to 64 chars, no URLs)

**Deny list** (must never appear in bundle or on disk):

- prompt text, reply text, tool arguments, tool return values
- model identifiers (raw or hashed by model name), API keys, vault secrets, OAuth tokens
- file paths beyond the diag directory itself (e.g. no `/sdcard/Download/user_photo.jpg`)
- `ANDROID_ID`, `AdvertisingId`, `IMEI`, `MAC`, IP addresses, account email
- contact names, calendar entries, install referrer

A `Redactor.redact(input: String): String` is a single pure function used by the bundle builder AND any logging path. Unit test: feed deny-listed strings and assert scrubbed.

## 6. UI mockup (ASCII)

```
+============================================================+
| SETTINGS / DIAGNOSTICS & PRIVACY                  [locked] |
| Counters · Redacted bundle · Feedback                       |
+------------------------------------------------------------+
| [COUNTERS] [BUNDLE] [FEEDBACK] [PRIVACY]   <- tabs         |
+------------------------------------------------------------+
| COUNTERS panel (when COUNTERS active):                     |
|   turns_sent       1,284       first 2026-08-12 last now   |
|   replies_received 1,279                                    |
|   model_switches      42                                    |
|   failovers           17                                    |
|   lease_arms         203                                    |
|   api_errors_4xx      31                                    |
|   api_errors_5xx       8                                    |
|   api_errors_network  14                                    |
|   crashes_caught       0                                    |
|   tts_renders         88                                    |
|   stt_captures        62                                    |
|                                                            |
|   [ Reset counters ]  (red outline, confirm dialog)        |
+------------------------------------------------------------+
| BUNDLE panel:                                              |
|   Redacted JSON preview (monospace, scroll, ~400 height)   |
|   [ Copy to clipboard ]  [ Save to /sdcard/Download ]      |
+------------------------------------------------------------+
| FEEDBACK panel:                                            |
|   [ Send feedback email ]                                  |
|     opens intent chooser -> feedback@placeholder.tld       |
|     subject: "PurpClaw mobile feedback <unix>"             |
|     body: same redacted bundle as plain text               |
+------------------------------------------------------------+
| PRIVACY panel:                                             |
|   Share anonymous usage        [ OFF ]                     |
|     Counter board still works; nothing leaves the phone    |
|   Crash reports                [ OFF ]                     |
|     Uncaught exceptions use Android default; nothing to disk|
+------------------------------------------------------------+
```

Tab strip mirrors `AuditProofScreen` pattern: row of `Surface` chips, horizontal scroll, click to swap. `SectionCard` shells each panel.

## 7. Files to touch when implementation begins

- NEW `core/runtime/DiagnosticsCounters.kt` — counter singleton + `SharedPreferences` JSON
- NEW `core/runtime/Redactor.kt` — allow/deny scrubber, pure function
- NEW `core/runtime/CrashReaper.kt` — `Thread.setDefaultUncaughtExceptionHandler` toggle-aware hook
- NEW `core/runtime/DiagnosticBundleBuilder.kt` — assembles JSON from counters + `RoutingState`
- NEW `ui/screens/DiagnosticsScreen.kt` — composable + tabs + dialogs
- EDIT `ui/screens/AiModelsSettingsScreen.kt` — add `DIAGNOSTICS` tab + `onOpenDiagnostics` callback at index 1
- EDIT `ui/PurpClawApp.kt` — instantiate `DiagnosticsCounters` early, wire `CrashReaper`, surface export/share intents
- EDIT `ui/MainViewModel.kt` — expose `DiagnosticsCounters.snapshot` as `StateFlow<DiagnosticsSnapshot>` collected by the screen
- NEW `AndroidManifest.xml` updates: no NEW permissions needed (`WRITE_EXTERNAL_STORAGE` is REJECTED — use `MediaStore.Downloads` on API 29+); add `<queries>` for email intent visibility on API 30+
- NEW unit tests: `RedactorTest` (deny list enforcement), `DiagnosticsCountersTest` (increment + persist), `DiagnosticBundleBuilderTest` (shape + size < 8 KB)

## 8. Triple-verify steps

1. **Static checks** — `RedactorTest` green; `DiagnosticsCountersTest` green; bundle JSON shape matches §5 allow list verbatim; bytes < 8 KB; round-trip JSON parse.
2. **On-device probe** with toggles both OFF: launch diag screen, run 5 chat + 2 EXECUTE turns, confirm counters move and **no file appears under `/sdcard/Download/` or `filesDir/diag/`**; toggle both ON, re-run 5 turns, confirm ONE file lands and bundle redaction grep returns 0 hits for "sk-", "Bearer", "@", "/sdcard", "@gmail", "ANDROID_ID".
3. **Fail-closed toggle test** — toggle both OFF mid-session while a crash reaper is armed (force `throw` in a coroutine on a debug-only path); confirm: handler is detached within 50 ms, no file written, Android default kill still happens.

## 9. Out of scope (explicit)

- No remote telemetry upload endpoint is built. `share_anonymous_usage=ON` is recorded but currently a no-op for transport.
- No deep-link from AuditProofScreen — that screen owns capability truth; this lane owns behaviour counters + privacy contract.
- No per-model id in bundle ever. Even hashed model ids are out — coarse bucket "model_switches: int" only.
- No session id, conversation id, or thread id in bundle. Operates on counters over time only.
- No retroactive disable of already-shipped counters. OFF on cold start is the only contract.
- No cloud sync of preferences. Toggles live only in app-local `SharedPreferences`.
