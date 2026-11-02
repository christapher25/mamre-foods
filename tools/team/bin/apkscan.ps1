# Release unzip proof: counts each term in every file of an APK, as ASCII and as UTF-16LE.
# Usage: apkscan.ps1 -Apk <path to .apk> [-Out <report.txt>]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Out = '',
    [string[]]$Terms = @(
        'android.permission.INTERNET',
        'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'MANAGE_EXTERNAL_STORAGE',
        'QUERY_ALL_PACKAGES',
        'Demo', 'admin5', 'Example Street', 'Anytown', 'user1', 'Rajesh', 'FreshMart',
        'Fake', 'kotlin/jvm/internal/FakeKt'
    )
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
$work = Join-Path $env:TEMP ('apkscan-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$zip = Join-Path $work 'app.zip'
Copy-Item -Path $Apk -Destination $zip
$dest = Join-Path $work 'x'
Expand-Archive -Path $zip -DestinationPath $dest

$latin1 = [System.Text.Encoding]::GetEncoding(28591)
$counts = @{}
foreach ($t in $Terms) { $counts[$t] = 0 }
$files = Get-ChildItem -Path $dest -Recurse -File
foreach ($f in $files) {
    $text = $latin1.GetString([System.IO.File]::ReadAllBytes($f.FullName))
    foreach ($t in $Terms) {
        $ascii = [regex]::Escape($t)
        $utf16 = [regex]::Escape($latin1.GetString([System.Text.Encoding]::Unicode.GetBytes($t)))
        $counts[$t] += ([regex]::Matches($text, $ascii)).Count + ([regex]::Matches($text, $utf16)).Count
    }
}
$lines = @("APK: $Apk", "Files scanned: $($files.Count)", '')
foreach ($t in $Terms) { $lines += ('{0,-32} {1}' -f $t, $counts[$t]) }
$lines += ''
$lines += ("Fake outside kotlin/jvm/internal/FakeKt: {0}" -f ($counts['Fake'] - $counts['kotlin/jvm/internal/FakeKt']))
$report = $lines -join [Environment]::NewLine
Write-Output $report
if ($Out) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null
    Set-Content -Path $Out -Value $report -Encoding UTF8
}
Remove-Item -Recurse -Force -Path $work
