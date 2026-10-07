# Phase 6 gate parts 1-3 in one go, so the Mock wallet's short PIN window does not lapse between steps.
. "$PSScriptRoot\emu-lib.ps1"
Reset-And-Connect
$env:SKIP_RESET = "1"
& "$PSScriptRoot\emu-squad2.ps1"
& "$PSScriptRoot\emu-squad3.ps1"
