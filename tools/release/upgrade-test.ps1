# C2 (Doc 4 F5): proves that installing a newer release APK over the old one keeps the data, and that a downgrade is refused.
# Run on the emulator by TESTER:  powershell -ExecutionPolicy Bypass -File tools\release\upgrade-test.ps1
# Needs: one emulator or phone visible to adb, and NO android\keystore.properties (the Owner's real key is never touched).
# Builds two release APKs with a THROWAWAY key made by make-keystore.ps1 (versionCode N and N+1), then drives the real UI with
# adb (uiautomator). It restores android\version.properties and removes the throwaway key and keystore.properties at the end.
param([string]$Serial, [switch]$SkipBuild, [switch]$BuildOnly)   # -BuildOnly then -SkipBuild lets Gradle and the emulator run one after the other (low memory)
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

function Fail([string]$reason) { Write-Host "FAIL  upgrade-test: $reason"; exit 1 }
# The emulator-only guard (Doc 1 A-26) is shared with smoke-start.ps1 and pinned by SmokeStartGuardTest.
. (Join-Path $PSScriptRoot 'emulator-guard.ps1')

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    $line = $(if ($ok) { 'PASS' } else { 'FAIL' }) + "  $name" + $(if ($detail) { "  ($detail)" } else { '' })
    Write-Host $line
    $script:results.Add($line)
    if (-not $ok) { $script:failed = $true }
}

# --- adb helpers -----------------------------------------------------------------------------------------------------------
function Adb { & $adb @(if ($Serial) { '-s', $Serial }) @args 2>&1 | ForEach-Object { "$_" } }   # stderr merged into the output as text (adb prints refusals there)

# The visible nodes of the current screen, as [pscustomobject]{Text; Desc; Bounds; Class}. The release app is FLAG_SECURE (no screenshots),
# but uiautomator can still read the text.
function Get-Screen {
    Adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
    $xml = [xml](Adb exec-out cat /sdcard/ui.xml)
    foreach ($n in $xml.SelectNodes('//node')) {
        [pscustomobject]@{ Text = $n.text; Desc = $n.'content-desc'; Bounds = $n.bounds; Class = $n.class }
    }
}
function Matches-Node($n, [string]$text) { $n.Text -eq $text -or $n.Desc -eq $text }
function Find-Node([string]$text, [switch]$Last) {
    if ([string]::IsNullOrWhiteSpace($text)) { throw "Find-Node: an empty label would match every node (a step that should have set it did not run)" }
    $found = @(Get-Screen | Where-Object { Matches-Node $_ $text })
    if ($found.Count -eq 0) { return $null }
    if ($Last) { $found[-1] } else { $found[0] }
}
function Center($node) {
    if ($node.Bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "no bounds for '$($node.Text)'" }
    [int](([int]$Matches[1] + [int]$Matches[3]) / 2), [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
}
function Tap-Xy($x, $y) { Adb shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 800 }
# -Last: a label that is both a screen title and a button/tab (Customers, Prices, More, Confirm invoice): the button is the last match.
function Tap-Text([string]$text, [int]$waitSeconds = 8, [switch]$Last) {
    $deadline = (Get-Date).AddSeconds($waitSeconds)
    do {
        $n = Find-Node $text -Last:$Last
        if ($n) { $x, $y = Center $n; Tap-Xy $x $y; return }
        Start-Sleep -Milliseconds 700
    } while ((Get-Date) -lt $deadline)
    throw "screen has no '$text' (waited $waitSeconds s)"
}
function Has-Text([string]$text, [int]$waitSeconds = 6) {
    $deadline = (Get-Date).AddSeconds($waitSeconds)
    do { if (Find-Node $text) { return $true }; Start-Sleep -Milliseconds 700 } while ((Get-Date) -lt $deadline)
    $false
}
function Hide-Keyboard {
    if ((Adb shell dumpsys input_method 2>&1 | Out-String) -match 'mInputShown=true') { Adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 800 }
}
# The label is a text node inside its field, so tapping the label focuses the field. The old text is cleared first, then the keyboard is
# hidden (it would cover the Save button).
function Type-Into([string]$label, [string]$value) {
    Tap-Text $label
    Adb shell input keyevent KEYCODE_MOVE_END | Out-Null
    Adb shell input keyevent (@('KEYCODE_DEL') * 60) | Out-Null
    Adb shell input text ($value -replace ' ', '%s') | Out-Null
    Start-Sleep -Milliseconds 400
    Hide-Keyboard
}
function Scroll-Down { Adb shell input swipe 540 1800 540 500 300 | Out-Null; Start-Sleep -Milliseconds 800 }
function Scroll-Up { Adb shell input swipe 540 600 540 1800 300 | Out-Null; Start-Sleep -Milliseconds 800 }
function Screen-Texts { @(Get-Screen | Where-Object { $_.Text } | ForEach-Object { $_.Text }) }
# The release app blocks screenshots (FLAG_SECURE), so the evidence is the text of the screen.
function Save-Screen([string]$name) { New-Item -ItemType Directory -Force $outDir | Out-Null; (Screen-Texts) | Set-Content (Join-Path $outDir "$name.txt") -Encoding UTF8 }

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
        }
    }
} finally {
    Set-Content $versionFile -Value $originalVersion -NoNewline -Encoding ASCII
    if (Test-Path $propsFile) { Remove-Item $propsFile -Force }
    if (Test-Path $keyDir) { Remove-Item $keyDir -Recurse -Force }
}

# --- the test --------------------------------------------------------------------------------------------------------------
if ($BuildOnly) { Write-Host "Built: $apkA and $apkB"; exit 0 }
Assert-Emulator
Adb get-state | Out-Null
# smoke-start.ps1 (same guard) installs A with its data cleared, starts it and passes only on the PIN setup screen.
& (Join-Path $PSScriptRoot 'smoke-start.ps1') -Apk $apkA -Serial $Serial
Check 'install version N and it starts (smoke-start.ps1)' ($LASTEXITCODE -eq 0)

# --- UI steps. Labels and positions come from the real release app on the emulator (uiautomator). A step that cannot find its label
# throws, and the phase is reported as one FAIL line with the reason, never a silent pass.
$testPin = '2468'
$customerName = 'Upgrade Customer'
$businessName = 'Upgrade Test Foods'
function Launch-App { Adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null; Start-Sleep -Seconds 3 }
function Go-Settings { Tap-Text 'Admin area'; Tap-Text 'More' -Last; Tap-Text 'Settings' }
# The More tab remembers its last nested screen (for example Settings), so after tapping it press Back until the More list shows.
function Go-SalesHome {
    Tap-Text 'More' -Last
    for ($i = 0; $i -lt 3 -and -not (Has-Text 'Sales area' 2); $i++) { Adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 800 }
    Tap-Text 'Sales area'
}

$before = @{}
try {
    Launch-App
    Check 'fresh install asks to choose a PIN (setup screen)' (Has-Text 'Save PIN')
    Type-Into 'New PIN' $testPin
    Type-Into 'New PIN again' $testPin
    Tap-Text 'Save PIN'
    Check 'PIN saved, Home is shown' (Has-Text 'New invoice')

    Go-Settings
    Type-Into 'Owner name' 'Test Owner'; Tap-Text 'Save owner name'
    Type-Into 'Business name' $businessName; Tap-Text 'Save business details'
    Check 'business name is shown in Settings after saving' (Has-Text $businessName)

    Tap-Text 'Customers' -Last
    Tap-Text 'Add customer'
    Type-Into 'Name' $customerName
    Tap-Text 'Retail'; Tap-Text 'Cash'
    Scroll-Down
    Tap-Text 'Save customer'
    Check 'customer saved and listed' (Has-Text $customerName)

    # One default price: Mamre Chapathi for Retail = 77 (the first reference product ships in every build). The list has one
    # "Set new price" per product and customer type, in the order Restaurant, Shop, Retail, Catering: the third one is Retail.
    Tap-Text 'Prices' -Last
    Scroll-Down
    $buttons = @(Get-Screen | Where-Object { $_.Text -eq 'Set new price' } | Sort-Object { [int](($_.Bounds -replace '^\[\d+,(\d+)\].*', '$1')) })
    if ($buttons.Count -lt 3) { throw "expected the Retail 'Set new price' button, found $($buttons.Count) buttons" }
    $x, $y = Center $buttons[2]; Tap-Xy $x $y
    Type-Into 'New price per packet' '77'
    # The "Effective from" field has no text node of its own (checked with uiautomator): it is the clickable box right under the price
    # input, so tap 100 px below the bottom edge of the price input.
    $priceInput = Get-Screen | Where-Object { $_.Class -like '*EditText' } | Select-Object -First 1
    if (-not $priceInput) { throw 'the price input was not found' }
    if ($priceInput.Bounds -notmatch '\[\d+,\d+\]\[\d+,(\d+)\]') { throw 'no bounds for the price input' }
    Tap-Xy 540 ([int]$Matches[1] + 100)
    Save-Screen 'upgrade-date-picker'
    $today = $null
    for ($i = 0; $i -lt 8 -and -not $today; $i++) { Start-Sleep -Milliseconds 700; $today = Get-Screen | Where-Object { $_.Desc -like 'Today,*' -or $_.Text -like 'Today,*' } | Select-Object -First 1 }
    if (-not $today) { throw "the date picker has no 'Today' entry" }
    $x, $y = Center $today; Tap-Xy $x $y
    Tap-Text 'OK'
    Tap-Text 'Save price'
    Check 'default price saved (Retail $77.00)' (Has-Text '$77.00')

    # One bill: Sales area > New invoice > Retail > the customer > two packets > Continue > Payment (Cash, full) > Continue > Confirm.
    Go-SalesHome
    Tap-Text 'New invoice'
    Tap-Text 'Retail'
    Tap-Text $customerName
    Tap-Text 'Add one packet'; Tap-Text 'Add one packet'
    Tap-Text 'Continue'
    Tap-Text 'Cash'
    Tap-Text 'Continue'
    Tap-Text 'Confirm invoice' -Last
    Tap-Text 'Confirm'
    Start-Sleep -Seconds 3
    $receipt = Screen-Texts
    Save-Screen 'upgrade-before-receipt'
    $number = $receipt | ForEach-Object { if ($_ -match '(MAM-[A-Z0-9]+-\d+)') { $Matches[1] } } | Select-Object -First 1
    $before.BillNo = $number
    Check 'a bill was made and has a bill number' ([bool]$number) $number
    Tap-Text 'Done'
} catch { Check 'create data through the UI' $false $_.Exception.Message }

$out = (Adb install -r $apkB | Out-String)
Check 'install N+1 over N (adb install -r)' ($out -match 'Success') $out.Trim()

try {
    Adb shell am force-stop $pkg | Out-Null
    Launch-App
    Check 'after the update the app asks for the existing PIN (not setup)' ((Has-Text 'Open') -and -not (Has-Text 'Save PIN'))
    Type-Into 'PIN' $testPin; Tap-Text 'Open'
    Check 'after the update the PIN is accepted and Home is shown' (Has-Text 'New invoice')

    Go-Settings
    Check 'business name survived the update' (Has-Text $businessName)
    Tap-Text 'Customers' -Last
    Check 'customer survived the update' (Has-Text $customerName)
    Tap-Text 'Prices' -Last
    Scroll-Down
    Check 'default price survived the update ($77.00)' (Has-Text '$77.00')

    Go-SalesHome
    Tap-Text "Today's invoices"
    Start-Sleep -Seconds 2
    $after = Screen-Texts
    Save-Screen 'upgrade-after-invoices'
    $found = [bool]($before.BillNo -and ($after | Where-Object { $_ -like "*$($before.BillNo)*" }))
    Check 'the bill is listed after the update, with the SAME bill number' $found "before: $($before.BillNo)"
} catch { Check 'check the data after the update' $false $_.Exception.Message }

$out = (Adb install -r $apkA | Out-String)
Check 'installing N over N+1 is refused (downgrade)' ($out -match 'INSTALL_FAILED_VERSION_DOWNGRADE') $out.Trim()

Write-Host ''
Write-Host $(if ($failed) { 'RESULT: FAIL' } else { 'RESULT: PASS' })
exit $(if ($failed) { 1 } else { 0 })
