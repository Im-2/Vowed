# Phase 6: Explore list, then the Join flow on a sample challenge (review before sign).
. "$PSScriptRoot\emu-lib.ps1"
& $script:adb shell input keyevent KEYCODE_BACK; Start-Sleep 1
"explore: " + (Tap-Text "Explore" 20); Start-Sleep 6
Screen-Texts | Select-Object -First 45
& $script:adb exec-out screencap -p > "$PSScriptRoot\..\docs\phase6-explore.png"
