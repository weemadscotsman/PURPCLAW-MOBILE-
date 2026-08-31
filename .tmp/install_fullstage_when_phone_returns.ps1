$ErrorActionPreference = "Continue"

$adbPath = "C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$apkPath = "E:\god folder\02_ACTIVE_PROJECTS\PURPCLAW\purp mobile\app\build\outputs\apk\debug\app-debug.apk"
$proofPath = "E:\god folder\02_ACTIVE_PROJECTS\PURPCLAW\purp mobile\.tmp\purp_avatar_fullstage_installed.png"
$logPath = "E:\god folder\02_ACTIVE_PROJECTS\PURPCLAW\purp mobile\.tmp\install_fullstage_when_phone_returns.log"
$serial = "RZCY9172MDP"
$package = "com.aistudio.purpclaw.osv7"
$activity = "com.example.MainActivity"
$deadline = (Get-Date).AddHours(3)

"$(Get-Date -Format o) watcher started" | Set-Content -LiteralPath $logPath

while ((Get-Date) -lt $deadline) {
  $deviceLines = & $adbPath devices 2>&1
  if ($deviceLines -match "$serial\s+device") {
    "$(Get-Date -Format o) device detected" | Add-Content -LiteralPath $logPath
    $installOutput = & $adbPath -s $serial install -r $apkPath 2>&1
    $installOutput | Add-Content -LiteralPath $logPath
    if ($LASTEXITCODE -eq 0) {
      & $adbPath -s $serial shell am force-stop $package | Add-Content -LiteralPath $logPath
      & $adbPath -s $serial shell am start -n "$package/$activity" | Add-Content -LiteralPath $logPath
      Start-Sleep -Seconds 10
      & $adbPath -s $serial shell screencap -p /sdcard/purp_avatar_fullstage_installed.png | Add-Content -LiteralPath $logPath
      & $adbPath -s $serial pull /sdcard/purp_avatar_fullstage_installed.png $proofPath | Add-Content -LiteralPath $logPath
      "$(Get-Date -Format o) install and proof capture complete" | Add-Content -LiteralPath $logPath
      exit 0
    }
  } else {
    # Samsung occasionally remains visible to Windows while ADB has fallen
    # out of USB transport mode. This is harmless with no device and repairs
    # that state automatically when the cable returns.
    & $adbPath usb 2>&1 | Out-Null
  }
  Start-Sleep -Seconds 3
}

"$(Get-Date -Format o) watcher expired without device" | Add-Content -LiteralPath $logPath
exit 1
