# Shared emulator-only guard (C5 round 1, C2). Dot-source it, then call Assert-Emulator BEFORE any adb install, uninstall or clear:
#   . (Join-Path $PSScriptRoot 'emulator-guard.ps1'); ... Assert-Emulator
# The calling script must define $adb (path of adb.exe), $Serial (the -Serial parameter, may be empty) and Fail([string]) that stops it.
#
# Doc 1 A-26: there is no backup, so uninstalling the app deletes every bill. Scripts that uninstall the app run ONLY on the emulator:
# the serial must start with "emulator-" AND the device must report ro.kernel.qemu (or ro.boot.qemu) = 1. With more than one device
# attached, -Serial is required. There is no switch to skip this check, on purpose.
function Assert-Emulator {
    $refusal = "Refused: this script only runs on the emulator. It would delete the app's data on a real phone."
    if (-not $script:Serial) {
        $devices = @((& $adb devices 2>&1) | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
        if ($devices.Count -gt 1) { Fail "$refusal (more than one device is attached: pass -Serial emulator-5554)" }
        if ($devices.Count -eq 0) { Fail 'no emulator visible to adb' }
        $script:Serial = $devices[0]
    }
    if ($script:Serial -notlike 'emulator-*') { Fail $refusal }
    $qemu = ((& $adb -s $script:Serial shell getprop ro.kernel.qemu 2>&1) -join '').Trim()
    if ($qemu -ne '1') { $qemu = ((& $adb -s $script:Serial shell getprop ro.boot.qemu 2>&1) -join '').Trim() }
    if ($qemu -ne '1') { Fail $refusal }
}
