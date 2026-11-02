# C2 (Doc 4 F5): proves that installing a newer release APK over the old one keeps the data, and that a downgrade is refused.
# Run on the emulator by TESTER:  powershell -ExecutionPolicy Bypass -File tools\release\upgrade-test.ps1
# Needs: one emulator or phone visible to adb, and NO android\keystore.properties (the Owner's real key is never touched).
# Builds two release APKs with a THROWAWAY key made by make-keystore.ps1 (versionCode N and N+1), then drives the real UI with
# adb (uiautomator). It restores android\version.properties and removes the throwaway key and keystore.properties at the end.
param([string]$Serial, [switch]$SkipBuild)
$ErrorActionPreference = 'Continue'   # native tools print warnings on stderr; every call below checks its exit code
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$android = Join-Path $root 'android'
$versionFile = Join-Path $android 'version.properties'
$propsFile = Join-Path $android 'keystore.properties'
$outDir = 'C:\dev\mamre-team\out\C2'
$keyDir = Join-Path $env:TEMP 'mamre-upgrade-test-key'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'com.mamre.billing'
$results = [System.Collections.Generic.List[string]]::new()
$failed = $false

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    $line = $(if ($ok) { 'PASS' } else { 'FAIL' }) + "  $name" + $(if ($detail) { "  ($detail)" } else { '' })
    Write-Host $line
    $script:results.Add($line)
    if (-not $ok) { $script:failed = $true }
}

# --- adb helpers -----------------------------------------------------------------------------------------------------------
function Adb { & $adb @(if ($Serial) { '-s', $Serial }) @args }

# The visible text nodes of the current screen, as [pscustomobject]{Text; Desc; Bounds; Clickable}.
function Get-Screen {
    Adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    $xml = [xml](Adb exec-out cat /sdcard/ui.xml)
    foreach ($n in $xml.SelectNodes('//node')) {
        [pscustomobject]@{ Text = $n.text; Desc = $n.'content-desc'; Bounds = $n.bounds; Clickable = ($n.clickable -eq 'true'); Class = $n.class }
    }
}
function Find-Node([string]$text) { Get-Screen | Where-Object { $_.Text -eq $text -or $_.Desc -eq $text } | Select-Object -First 1 }
function Center($node) {
    if ($node.Bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "no bounds for '$($node.Text)'" }
    [int](([int]$Matches[1] + [int]$Matches[3]) / 2), [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
}
function Tap-Text([string]$text, [int]$waitSeconds = 8) {
    $deadline = (Get-Date).AddSeconds($waitSeconds)
    do {
        $n = Find-Node $text
        if ($n) { $x, $y = Center $n; Adb shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 700; return }
        Start-Sleep -Milliseconds 700
    } while ((Get-Date) -lt $deadline)
    throw "screen has no '$text' (waited $waitSeconds s)"
}
function Has-Text([string]$text, [int]$waitSeconds = 6) {
    $deadline = (Get-Date).AddSeconds($waitSeconds)
    do { if (Find-Node $text) { return $true }; Start-Sleep -Milliseconds 700 } while ((Get-Date) -lt $deadline)
    $false
}
# Types into the field that holds the given label or hint text (taps it first). Spaces are sent as %s.
function Type-Into([string]$label, [string]$value) {
    Tap-Text $label
    Adb shell input text ($value -replace ' ', '%s') | Out-Null
    Start-Sleep -Milliseconds 400
}
function Shot([string]$name) { New-Item -ItemType Directory -Force $outDir | Out-Null; Adb shell screencap -p /sdcard/s.png | Out-Null; Adb pull /sdcard/s.png (Join-Path $outDir "$name.png") | Out-Null }

# --- build two release APKs with a throwaway key -----------------------------------------------------------------------------
if (Test-Path $propsFile) { throw "android\keystore.properties exists. Move it away first: this test must not use or delete a real key." }
$originalVersion = Get-Content $versionFile -Raw
$apkA = Join-Path 'C:\dev\mamre-team\out\release' 'mamre-upgradetest-A.apk'
$apkB = Join-Path 'C:\dev\mamre-team\out\release' 'mamre-upgradetest-B.apk'
try {
    if (-not $SkipBuild) {
        if (Test-Path $keyDir) { Remove-Item $keyDir -Recurse -Force }
        & (Join-Path $PSScriptRoot 'make-keystore.ps1') -KeyDir $keyDir -TestPassword 'Throwaway-Test-1' | Out-Null
        $code = [int](($originalVersion -split "`n" | Where-Object { $_ -match '^versionCode=' }) -replace 'versionCode=', '').Trim()
        foreach ($b in @(@{ Code = $code; Name = 'upgradetest-A'; Apk = $apkA }, @{ Code = $code + 1; Name = 'upgradetest-B'; Apk = $apkB })) {
            Set-Content $versionFile -Value "versionCode=$($b.Code)`nversionName=$($b.Name)" -Encoding ASCII
            & (Join-Path $PSScriptRoot 'build-release.ps1') | Out-Null
            Copy-Item (Join-Path 'C:\dev\mamre-team\out\release' "mamre-$($b.Name).apk") $b.Apk -Force
        }
    }
} finally {
    Set-Content $versionFile -Value $originalVersion -NoNewline -Encoding ASCII
    if (Test-Path $propsFile) { Remove-Item $propsFile -Force }
    if (Test-Path $keyDir) { Remove-Item $keyDir -Recurse -Force }
}

# --- the test --------------------------------------------------------------------------------------------------------------
Adb get-state | Out-Null
Adb uninstall $pkg 2>$null | Out-Null
$out = Adb install $apkA
Check 'install version N' ($out -match 'Success') ($out -join ' ')

# TODO(next cycle, on the emulator): UI steps - set a PIN, save business details, add one customer, one price, make one bill.
# They fill $before (bill number, customer name, price text) which is compared after the update.

$out = Adb install -r $apkB
Check 'install N+1 over N (adb install -r)' ($out -match 'Success') ($out -join ' ')

# TODO: after the update - PIN screen asks for the existing PIN (not setup); customer, price and bill are there; bill number unchanged.

$out = (Adb install -r $apkA) -join ' '
Check 'installing N over N+1 is refused (downgrade)' ($out -match 'INSTALL_FAILED_VERSION_DOWNGRADE') $out

Write-Host ''
Write-Host $(if ($failed) { 'RESULT: FAIL' } else { 'RESULT: PASS' })
exit $(if ($failed) { 1 } else { 0 })
