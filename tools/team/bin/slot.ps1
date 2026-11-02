# Build slots for the Mamre team: at most $Slots Gradle runs (or the emulator) at once.
# Usage:  slot.ps1 acquire DEV-A   -> prints "SLOT n" (exit 0) or "BUSY" (exit 1)
#         slot.ps1 release DEV-A   -> frees every slot held by DEV-A
#         slot.ps1 status
param(
    [Parameter(Mandatory = $true)][ValidateSet('acquire', 'release', 'status')][string]$Action,
    [string]$Who = 'agent',
    [int]$Slots = 2,
    [int]$StaleMinutes = 60
)
$dir = 'C:\dev\mamre-team\locks'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

switch ($Action) {
    'acquire' {
        # A role holds at most one slot.
        foreach ($f in Get-ChildItem -Path $dir -Filter 'gradle-*.lock' -ErrorAction SilentlyContinue) {
            $owner = (Get-Content -Path $f.FullName -Raw -ErrorAction SilentlyContinue)
            if ($owner -and $owner.StartsWith("$Who ")) { Write-Output "SLOT $($f.BaseName) (already held)"; exit 0 }
        }
        for ($n = 1; $n -le $Slots; $n++) {
            $path = Join-Path $dir "gradle-$n.lock"
            if (Test-Path $path) {
                $age = (Get-Date) - (Get-Item $path).LastWriteTime
                if ($age.TotalMinutes -gt $StaleMinutes) { Remove-Item -Path $path -Force -ErrorAction SilentlyContinue }
            }
            try {
                $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
                $bytes = [System.Text.Encoding]::UTF8.GetBytes("$Who $(Get-Date -Format o)")
                $fs.Write($bytes, 0, $bytes.Length)
                $fs.Close()
                Write-Output "SLOT $n"
                exit 0
            } catch { }
        }
        Write-Output 'BUSY'
        exit 1
    }
    'release' {
        foreach ($f in Get-ChildItem -Path $dir -Filter 'gradle-*.lock' -ErrorAction SilentlyContinue) {
            $owner = (Get-Content -Path $f.FullName -Raw -ErrorAction SilentlyContinue)
            if ($owner -and $owner.StartsWith("$Who ")) { Remove-Item -Path $f.FullName -Force }
        }
        Write-Output 'released'
        exit 0
    }
    'status' {
        $any = $false
        foreach ($f in Get-ChildItem -Path $dir -Filter 'gradle-*.lock' -ErrorAction SilentlyContinue) {
            $any = $true
            Write-Output "$($f.Name): $(Get-Content -Path $f.FullName -Raw)"
        }
        if (-not $any) { Write-Output 'all slots free' }
        exit 0
    }
}
