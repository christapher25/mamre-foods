# C1: builds the signed release APK, verifies it, copies it to the team out folder and prints the SHA-256 values.
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$android = Join-Path $root 'android'
if (-not (Test-Path (Join-Path $android 'keystore.properties'))) { throw 'android\keystore.properties is missing. Run tools\release\make-keystore.ps1 first.' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

Push-Location $android
try {
    & .\gradlew.bat --no-daemon assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed with exit code $LASTEXITCODE" }
    $version = ((& .\gradlew.bat -q printVersion) | Where-Object { $_ -match 'versionName=' }) -replace '.*versionName=', ''
} finally { Pop-Location }
$version = $version.Trim()

$apk = Join-Path $android 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "No signed APK at $apk" }
$apksigner = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object Name | Select-Object -Last 1 | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' }
$certs = & $apksigner verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed with exit code $LASTEXITCODE" }
$certs | Write-Host

$outDir = 'C:\dev\mamre-team\out\release'
New-Item -ItemType Directory -Force $outDir | Out-Null
$dest = Join-Path $outDir "mamre-$version.apk"
Copy-Item $apk $dest -Force
Write-Host ''
Write-Host "APK:                    $dest"
Write-Host ("APK SHA-256:            " + (Get-FileHash $dest -Algorithm SHA256).Hash)
$certLine = $certs | Where-Object { $_ -match 'certificate SHA-256 digest' } | Select-Object -First 1
Write-Host ("Signing certificate:    " + ($certLine -replace '.*digest:\s*', ''))
