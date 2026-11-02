# One-time setup of the Mamre parallel team (Doc 4). Safe to run again: it never deletes anything.
# Run from PowerShell:  powershell -ExecutionPolicy Bypass -File C:\dev\mamre\tools\team\setup-team.ps1
$ErrorActionPreference = 'Continue'   # native tools write progress to stderr; exit codes are checked instead
$root = 'C:\dev'
$repo = Join-Path $root 'mamre'
$team = Join-Path $root 'mamre-team'
$kit = Join-Path $repo 'tools\team'
$worktrees = @('mamre-devA', 'mamre-devB', 'mamre-devC', 'mamre-test', 'mamre-verify')

function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "STOP: $text" -ForegroundColor Red; exit 1 }

Step 'Checking tools'
foreach ($c in @('git', 'gh', 'claude', 'wt')) {
    if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { Fail "'$c' is not installed or not on PATH. See OWNER steps in the chat." }
}
gh auth status *> $null
if ($LASTEXITCODE -ne 0) { Fail "gh is not logged in. Run: gh auth login" }
git config --global core.longpaths true

Step 'Updating the main checkout'
Set-Location $repo
if (git status --porcelain) { Fail "C:\dev\mamre has uncommitted changes. Commit or stash them first." }
git switch main
if ($LASTEXITCODE -ne 0) { Fail 'could not switch to main' }
git pull --ff-only
if ($LASTEXITCODE -ne 0) { Fail 'git pull failed (main has local commits?)' }
if (-not (Test-Path (Join-Path $repo 'docs\04-team-plan.txt'))) { Fail "docs\04-team-plan.txt is not on main yet. Merge the team kit PR first." }

Step 'Java and Android SDK for every shell'
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (-not [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User') -and (Test-Path $jbr)) {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $jbr, 'User'); Write-Host "JAVA_HOME set to $jbr"
}
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (-not [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User') -and (Test-Path $sdk)) {
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User'); Write-Host "ANDROID_HOME set to $sdk"
}
$env:JAVA_HOME = $jbr; $env:ANDROID_HOME = $sdk

Step 'Gradle memory caps (16 GB laptop)'
$gradleHome = Join-Path $env:USERPROFILE '.gradle'
New-Item -ItemType Directory -Force -Path $gradleHome | Out-Null
$gp = Join-Path $gradleHome 'gradle.properties'
if (-not (Test-Path $gp)) { New-Item -ItemType File -Path $gp | Out-Null }
$existing = Get-Content -Path $gp -ErrorAction SilentlyContinue
$wanted = [ordered]@{
    'org.gradle.jvmargs'           = '-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
    'kotlin.daemon.jvmargs'        = '-Xmx1g'
    'org.gradle.workers.max'       = '2'
    'org.gradle.daemon.idletimeout' = '900000'
}
foreach ($k in $wanted.Keys) {
    if (-not ($existing | Where-Object { $_ -match "^\s*$([regex]::Escape($k))\s*=" })) {
        Add-Content -Path $gp -Value "$k=$($wanted[$k])"; Write-Host "added $k"
    } else { Write-Host "kept existing $k" }
}

Step 'Team folder'
foreach ($d in @('', 'tasks', 'reports', 'locks', 'out', 'bin', 'roles')) { New-Item -ItemType Directory -Force -Path (Join-Path $team $d) | Out-Null }
Copy-Item -Path (Join-Path $kit 'bin\*') -Destination (Join-Path $team 'bin') -Force
Copy-Item -Path (Join-Path $kit 'roles\*') -Destination (Join-Path $team 'roles') -Force
foreach ($f in Get-ChildItem -Path (Join-Path $kit 'board')) {
    $target = Join-Path $team $f.Name
    if (-not (Test-Path $target)) { Copy-Item -Path $f.FullName -Destination $target } else { Write-Host "kept existing $($f.Name)" }
}
foreach ($f in Get-ChildItem -Path (Join-Path $kit 'tasks')) {
    $target = Join-Path $team "tasks\$($f.Name)"
    if (-not (Test-Path $target)) { Copy-Item -Path $f.FullName -Destination $target } else { Write-Host "kept existing tasks\$($f.Name)" }
}

Step 'local.properties (Android SDK path for new folders)'
$lp = Get-ChildItem -Path $root -Directory -Filter 'mamre*' |
    ForEach-Object { Join-Path $_.FullName 'android\local.properties' } |
    Where-Object { Test-Path $_ } | Select-Object -First 1
$lpText = $null
if ($lp) { $lpText = Get-Content -Path $lp -Raw; Write-Host "using $lp" }
elseif (Test-Path $sdk) { $lpText = 'sdk.dir=' + ($sdk.Replace('\', '\\').Replace(':', '\:')); Write-Host "created from $sdk" }
else { Fail "No Android SDK found at $sdk. Open Android Studio once so it installs the SDK." }

Step 'Worktrees'
git fetch origin --prune
foreach ($w in $worktrees) {
    $path = Join-Path $root $w
    if (Test-Path $path) { Write-Host "exists: $path (left as it is)" }
    else {
        git worktree add --detach $path origin/main
        if ($LASTEXITCODE -ne 0) { Fail "could not create $path" }
        Write-Host "created: $path"
    }
    $target = Join-Path $path 'android\local.properties'
    if ((Test-Path (Join-Path $path 'android')) -and -not (Test-Path $target)) { Set-Content -Path $target -Value $lpText -NoNewline }
}
$mainLp = Join-Path $repo 'android\local.properties'
if (-not (Test-Path $mainLp)) { Set-Content -Path $mainLp -Value $lpText -NoNewline }

Step 'Start files for each tab'
$roles = @(
    @{ Name = 'LEAD';     Dir = $repo;                          Model = 'opus';   Every = '15m'; Role = 'lead' },
    @{ Name = 'DEV-A';    Dir = (Join-Path $root 'mamre-devA'); Model = 'sonnet'; Every = '10m'; Role = 'dev' },
    @{ Name = 'DEV-B';    Dir = (Join-Path $root 'mamre-devB'); Model = 'sonnet'; Every = '10m'; Role = 'dev' },
    @{ Name = 'DEV-C';    Dir = (Join-Path $root 'mamre-devC'); Model = 'sonnet'; Every = '10m'; Role = 'dev' },
    @{ Name = 'TESTER';   Dir = (Join-Path $root 'mamre-test'); Model = 'sonnet'; Every = '20m'; Role = 'tester' },
    @{ Name = 'VERIFIER'; Dir = (Join-Path $root 'mamre-verify'); Model = 'opus'; Every = '20m'; Role = 'verifier' }
)
$lines = @()
foreach ($r in $roles) {
    $loop = "/loop $($r.Every) You are $($r.Name). Follow C:\dev\mamre-team\roles\$($r.Role).md and run one cycle."
    $cmd = @(
        '@echo off',
        "title $($r.Name)",
        "cd /d $($r.Dir)",
        "claude --model $($r.Model) --add-dir C:\dev\mamre-team `"$loop`""
    ) -join "`r`n"
    Set-Content -Path (Join-Path $team "bin\start-$($r.Name).cmd") -Value $cmd -Encoding ASCII
    $lines += "$($r.Name): $loop"
}
Set-Content -Path (Join-Path $team 'START-LINES.txt') -Value $lines -Encoding UTF8

Step 'Done'
git worktree list
Write-Host ''
Write-Host 'Next: powershell -ExecutionPolicy Bypass -File C:\dev\mamre\tools\team\launch-team.ps1' -ForegroundColor Green
