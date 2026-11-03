# C5: "does the release app start?" Installs an APK on the running emulator or phone (clearing its data first), launches it, waits up to
# 20 s and prints PASS only if the PIN setup screen ("New PIN") is on screen. FAIL (with the reason) when logcat shows a FATAL EXCEPTION
# for com.mamre.billing, when the app process is gone, or when the PIN setup screen never appears.
# Run:  powershell -ExecutionPolicy Bypass -File tools\release\smoke-start.ps1 -Apk C:\dev\mamre-team\out\release\mamre-1.0.0-rc1.apk
# Needs one emulator or phone visible to adb. It UNINSTALLS the app first, so any data on that device is deleted: use an emulator.
param([Parameter(Mandatory = $true)][string]$Apk, [string]$Serial, [int]$WaitSeconds = 20)
$ErrorActionPreference = 'Continue'   # adb prints on stderr; every result below is decided by output or exit code
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'com.mamre.billing'
function Adb { & $adb @(if ($Serial) { '-s', $Serial }) @args }
function Fail([string]$reason) { Write-Host "FAIL  smoke-start: $reason"; exit 1 }

# The emulator-only guard (Doc 1 A-26) lives in emulator-guard.ps1 and is pinned by SmokeStartGuardTest.
. (Join-Path $PSScriptRoot 'emulator-guard.ps1')

if (-not (Test-Path $Apk)) { Fail "APK not found: $Apk" }
if (-not (Test-Path $adb)) { Fail "adb not found at $adb" }
Assert-Emulator
if ((Adb get-state) -notmatch 'device') { Fail 'no emulator visible to adb' }

Adb uninstall $pkg 2>&1 | Out-Null
$out = (Adb install $Apk 2>&1) -join ' '
if ($out -notmatch 'Success') { Fail "install failed: $out" }
Adb logcat -c | Out-Null
Adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$seen = $false
do {
    Start-Sleep -Milliseconds 1000
    $log = (Adb logcat -d -s AndroidRuntime:E 2>&1) -join "`n"
    if ($log -match 'FATAL EXCEPTION' -and $log -match "Process: $([regex]::Escape($pkg))") {
        # the root cause is the last "Caused by" line; fall back to the first java.lang exception line
        $lines = $log -split "`n"
        $line = ($lines | Where-Object { $_ -cmatch 'Caused by' } | Select-Object -Last 1)
        if (-not $line) { $line = ($lines | Where-Object { $_ -cmatch 'java\.lang\.\w+' } | Select-Object -First 1) }
        if (-not $line) { $line = 'FATAL EXCEPTION (no detail logged)' }
        Fail "the app crashed on start: $(($line -replace '^.*AndroidRuntime:\s*', '').Trim())"
    }
    if (-not ((Adb shell pidof $pkg 2>&1) -join '').Trim()) { continue }
    Adb shell uiautomator dump /sdcard/smoke-ui.xml 2>&1 | Out-Null
    $xml = (Adb exec-out cat /sdcard/smoke-ui.xml 2>&1) -join "`n"
    if ($xml -match 'text="New PIN"' -or $xml -match 'text="Save PIN"') { $seen = $true; break }
} while ((Get-Date) -lt $deadline)

if ($seen) { Write-Host 'PASS  smoke-start: the app started and shows the PIN setup screen (New PIN)'; exit 0 }
if (-not ((Adb shell pidof $pkg 2>&1) -join '').Trim()) { Fail 'the app process is gone and no crash was logged' }
Fail "the PIN setup screen did not appear within $WaitSeconds s"
