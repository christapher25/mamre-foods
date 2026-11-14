# C1: creates the Mamre release signing key ONCE. Run: powershell -ExecutionPolicy Bypass -File tools\release\make-keystore.ps1
# C2: -KeyDir and -TestPassword exist only so tools\release\upgrade-test.ps1 can make a THROWAWAY key without prompting.
# The Owner runs it with no parameters (key in %USERPROFILE%\mamre-keys, password typed twice, nothing echoed).
param([string]$KeyDir, [string]$TestPassword)
$ErrorActionPreference = 'Stop'
$keytool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
$ownerKeyDir = Join-Path $env:USERPROFILE 'mamre-keys'
if ($TestPassword) {
    # A test password may only ever make a key outside the Owner's key folder.
    if (-not $KeyDir) { throw '-TestPassword needs -KeyDir. Nothing was created.' }
    $full = [IO.Path]::GetFullPath($KeyDir).TrimEnd([char]92)
    $own = [IO.Path]::GetFullPath($ownerKeyDir).TrimEnd([char]92)
    if ($full -eq $own -or $full.StartsWith($own + [char]92, [StringComparison]::OrdinalIgnoreCase)) { throw '-TestPassword may not be used with the Owner key folder. Nothing was created.' }
}
$keyDir = if ($KeyDir) { $KeyDir } else { $ownerKeyDir }
$jks = Join-Path $keyDir 'mamre-release.jks'
$props = Join-Path $PSScriptRoot '..\..\android\keystore.properties'

if (-not (Test-Path $keytool)) { throw "keytool not found at $keytool" }
if (Test-Path $jks) { throw "A key already exists at $jks. This script never overwrites a key." }
if (Test-Path $props) { throw "$props already exists. Remove it yourself if you are sure; this script never overwrites it." }

function Read-Plain($prompt) {
    $s = Read-Host $prompt -AsSecureString
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}
if ($TestPassword) { $p1 = $TestPassword; $p2 = $TestPassword } else {
    $p1 = Read-Plain 'Choose a password for the key (at least 8 characters)'
    $p2 = Read-Plain 'Type the same password again'
}
if ($p1 -ne $p2) { throw 'The two passwords differ. Nothing was created.' }
if ($p1.Length -lt 8) { throw 'The password is shorter than 8 characters. Nothing was created.' }
if ($p1 -cmatch '[^ -~]') { throw 'The password may only use plain letters, digits and the usual keyboard symbols (no accents or other special characters). Nothing was created.' }
if ($p1.Contains('\') -or $p1 -ne $p1.Trim()) { throw 'The password must not contain a backslash and must not start or end with a space (the build settings file cannot hold them safely). Nothing was created.' }

New-Item -ItemType Directory -Force $keyDir | Out-Null
# 30 years = 10950 days. The password is passed through the environment, never on the command line or to the screen.
$env:MAMRE_KS_PASS = $p1
$ErrorActionPreference = 'Continue'   # keytool prints its progress on stderr; only its exit code counts
try {
    & $keytool -genkeypair -keystore $jks -alias mamre -keyalg RSA -keysize 4096 -validity 10950 `
        -dname 'CN=Mamre Foods, O=Mamre Foods' -storepass:env MAMRE_KS_PASS -keypass:env MAMRE_KS_PASS | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
} finally { $ErrorActionPreference = 'Stop'; Remove-Item Env:\MAMRE_KS_PASS -ErrorAction SilentlyContinue }

$esc = $jks.Replace('\', '/')   # java.util.Properties reads a backslash as an escape
@("storeFile=$esc", "storePassword=$p1", "keyAlias=mamre", "keyPassword=$p1") | Set-Content -Path $props -Encoding ASCII

Write-Host ''
Write-Host "The key was created: $jks"
Write-Host "Copy $jks to a USB stick and to your Google Drive."
Write-Host 'If this file is lost, the app can never be updated without deleting all its data.'
Write-Host 'Also keep the password somewhere safe. It is stored for the build in android\keystore.properties (never committed).'
