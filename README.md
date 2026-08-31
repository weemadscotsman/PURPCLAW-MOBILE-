# PurpClaw Mobile

PurpClaw becomes delegated work on a phone: give yourself a job, put the phone in your
pocket, come back when done.

## Delegation model

```text
  🎙️ Voice request or text
        │
        ▼
  WorkSession (persistent; survives AppWidget / transaction / Service)
        │
        ▼
  plan + acceptance + providers
        │
        ▼
  EXECUTE ── WorkManager (deferrable/retryable) OR foreground service (active)
        │
        ▼
  VERIFY ── completion gates on evidence, not model boredom
        │
        ▼
  NOTIFICATION ── [OPEN CODE] [OPEN RESULT] [RECEIPT] [RESUME] [CANCEL]
```

## Build

**Prerequisites:** Android Studio (or the standalone Gradle 9.3.1 binary) + Android SDK.

### Gradle

```bash
# Direct 9.3.1 binary (wrapper in this repo is missing)
/c/Users/Admin/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle \
  --no-daemon --project-dir=. assembleDebug

# Adjust the path for your machine's Gradle cache location.
```

Or open in Android Studio: `Select Open` → `purp mobile`.

### Quick device install

```bash
adb -s RZCY9172MDP install -r app/build/outputs/apk/debug/app-debug.apk
adb -s RZCY9172MDP shell am start -n com.aistudio.purpclaw.osv7/com.example.MainActivity
```

## Package / activity

| Component                 | Purpose                                  |
| ------------------------- | ---------------------------------------- |
| `com.aistudio.purpclaw`   | Application root                         |
| `app-debug.apk`           | 253 MB debug build                       |
| `com.example.MainActivity` | Entry surface (Galaxy/Compose)           |

## Hardware quarantine

`libpenguin.so` (gltfio spine) is dlopen-error-quarantined on the Samsung S25 (Adreno 830,
2026-08-28). It is expected at app start, expected to not crash the process; the 2D fallback
remains active and intentional.

## Status

Build verified 2026-08-31. APK `app-debug.apk` at `app/build/outputs/apk/debug/`.