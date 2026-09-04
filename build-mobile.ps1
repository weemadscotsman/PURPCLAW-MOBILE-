$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$lockFile = Join-Path $projectRoot ".purpclaw-mobile-build.lock"
$logFile = Join-Path $projectRoot ".purpclaw-mobile-build.log"

$pid | Out-Null  # suppress unused variable

function Write-Log {
    param([string]$Message)
    $stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "$stamp PID=$PID $Message" | Out-File -FilePath $logFile -Append -Encoding UTF8
    Write-Host "$stamp $Message"
}

# ── ACQUIRE LOCK ──────────────────────────────────────────────────────────────
$lock = $null
try {
    $lock = [System.IO.File]::Open($lockFile, "Create", "ReadWrite", "None")
    Write-Log "LOCK_ACQUIRED"
} catch {
    Write-Log "BUILD_ALREADY_RUNNING: Another process holds the lock."
    Write-Host "BUILD_ALREADY_RUNNING: Another process holds the build lock."
    exit 1
}

# ── BUILD ───────────────────────────────────────────────────────────────────
try {
    # Stop any stale daemon from previous crashed builds
    Write-Log "Stopping stale Gradle daemons..."
    & gradlew.bat --stop 2>$null | Out-Null

    # ── Clean on first run only (preserve incremental when mutex is held) ──
    # Check if app/build/outputs/apk/debug/app-debug.apk exists from a prior build
    $priorApk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $priorApk)) {
        Write-Log "No prior APK found — running clean build..."
        $cleanFlag = "--rerun-tasks"
    } else {
        Write-Log "Prior APK found — incremental build..."
        $cleanFlag = ""
    }

    $gradleCmd = {
        param($pf, $cf)
        & gradlew.bat :app:assembleDebug --no-daemon --max-workers=2 $cf --stacktrace 2>&1
    }

    Write-Log "BUILD_STARTED at $(Get-Date -Format 'HH:mm:ss')"
    $sw = [Diagnostics.Stopwatch]::StartNew()

    $result = & $gradleCmd $projectRoot $cleanFlag
    $exitCode = $LASTEXITCODE
    $elapsed = $sw.ElapsedMilliseconds

    $result | Out-File -FilePath (Join-Path $projectRoot ".purpclaw-mobile-build-last-output.log") -Encoding UTF8

    if ($exitCode -ne 0) {
        Write-Log "BUILD_FAILED exit=$exitCode elapsed=${elapsed}ms"
        Write-Host "BUILD_FAILED — see .purpclaw-mobile-build.log for details"
        exit $exitCode
    }

    Write-Log "BUILD_GREEN exit=0 elapsed=${elapsed}ms"

    # ── Verify APK actually exists ─────────────────────────────────────────
    if (Test-Path $priorApk) {
        $sha256 = (Get-FileHash $priorApk -Algorithm SHA256).Hash
        Write-Log "APK_SHA256=$sha256"
    }

} catch {
    Write-Log "BUILD_EXCEPTION: $_"
    Write-Host "BUILD_EXCEPTION — see .purpclaw-mobile-build.log"
    exit 1
} finally {
    # ── RELEASE LOCK ───────────────────────────────────────────────────────
    if ($lock) {
        $lock.Close()
        $lock.Dispose()
    }
    Remove-Item $lockFile -ErrorAction SilentlyContinue
    Write-Log "LOCK_RELEASED"
}

Write-Host "BUILD_GREEN — $elapsed ms"
exit 0
