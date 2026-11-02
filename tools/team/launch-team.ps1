# Opens one Windows Terminal window with a tab per role (Doc 4 s3).
# Usage: powershell -ExecutionPolicy Bypass -File C:\dev\mamre\tools\team\launch-team.ps1 [-Roles LEAD,VERIFIER]
param([string[]]$Roles = @('LEAD', 'VERIFIER', 'DEV-A', 'DEV-B', 'DEV-C', 'TESTER'))
$bin = 'C:\dev\mamre-team\bin'
$parts = @()
foreach ($r in $Roles) {
    $f = Join-Path $bin "start-$r.cmd"
    if (-not (Test-Path $f)) { Write-Host "Missing $f. Run setup-team.ps1 first." -ForegroundColor Red; exit 1 }
    $parts += "new-tab --title $r cmd /k `"$f`""
}
$wtArgs = '-w mamre ' + ($parts -join ' ; ')
Start-Process -FilePath 'wt.exe' -ArgumentList $wtArgs
Write-Host "Opened: $($Roles -join ', ')"
Write-Host 'In EACH tab: press Shift+Tab until the footer shows auto mode.'
Write-Host 'If a tab shows the /loop line as a plain message instead of starting a loop, copy that tab''s line from C:\dev\mamre-team\START-LINES.txt and paste it.'
